package org.sitenetsoft.quarkus.atproto.test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.multikey.Jwk;
import org.sitenetsoft.multikey.KeyPairs;
import org.sitenetsoft.multikey.KeyType;

import com.fasterxml.jackson.databind.JsonNode;

import io.vertx.core.Handler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * An AT Protocol OAuth authorization server (https://atproto.com/specs/oauth) on loopback, strict where the real one
 * is strict:
 * <ul>
 * <li>{@code GET /.well-known/oauth-authorization-server}: {@code issuer} = this server's origin, PAR required,
 * {@code iss} in responses, client metadata documents, ES256 DPoP, {@code none} and {@code private_key_jwt}.</li>
 * <li>{@code POST /oauth/par}: a DPoP proof is required (the first one without the server nonce gets {@code 400
 * use_dpop_nonce} with {@code DPoP-Nonce}); the client metadata document is fetched from {@code client_id} (or the
 * {@code http://localhost?redirect_uri=...&scope=...} development form is accepted as a public client) and checked;
 * a confidential client must authenticate with a {@code private_key_jwt} assertion verified against its
 * {@code jwks_uri} ({@code iss} = {@code sub} = client_id, {@code aud} = issuer, fresh {@code iat}, unused
 * {@code jti}); {@code response_type=code}, PKCE {@code S256}, {@code state}, a registered {@code redirect_uri} and a
 * scope that contains {@code atproto}, parses and is within the client's registered scope. Answers {@code 201} with a
 * single-use {@code request_uri}.</li>
 * <li>{@code GET /oauth/authorize?client_id&request_uri}: auto-approves for the {@code login_hint} account (or
 * {@link #signInAs}, or the first account) and redirects to {@code redirect_uri?code&state&iss}.</li>
 * <li>{@code POST /oauth/token}: DPoP, client authentication, single-use codes bound to the client, redirect URI,
 * PKCE verifier and the PAR DPoP key; refresh tokens are single use and bound to the DPoP key (re-use:
 * {@code invalid_grant}). Access tokens are ES256 JWTs with {@code cnf.jkt}; the response carries {@code sub} and
 * {@code scope}.</li>
 * <li>{@code POST /oauth/revoke}: revokes the whole grant of an access or refresh token.</li>
 * </ul>
 * Every response carries {@code DPoP-Nonce}. Knobs and counters are reset by {@link FakeAtproto#clear()}.
 */
public final class FakeAuthorizationServer {

  static final String JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
  static final String REQUEST_URI_PREFIX = "urn:ietf:params:oauth:request_uri:";
  private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43}");
  private static final Pattern VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");
  private static final Duration PAR_TTL = Duration.ofMinutes(5);
  private static final Duration CODE_TTL = Duration.ofMinutes(5);
  private static final SecureRandom RANDOM = new SecureRandom();

  /** An OAuth error answer. */
  static final class OAuthError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final int status;
    final String error;

    OAuthError(int status, String error, String description) {
      super(description);
      this.status = status;
      this.error = error;
    }
  }

  /** A client as resolved from its metadata document (or the localhost development form). */
  record Client(String clientId, boolean confidential, boolean loopbackDev, List<String> redirectUris,
      Set<String> scopes, URI jwksUri, JsonNode jwks) {
  }

  private record ParRequest(Client client, String redirectUri, String state, String challenge, String scope,
      String loginHint, String jkt, Instant expires, AtomicBoolean used) {
  }

  private record Code(ParRequest par, Did did, Instant expires) {
  }

  private record Grant(String id, String clientId, Did did, String scope, String jkt) {
  }

  private record RefreshToken(Grant grant, AtomicBoolean used) {
  }

  /** An issued access token as the PDS sees it after {@link #introspect}. */
  record AccessToken(String jti, Grant grant, Instant expires) {
    Did did() {
      return grant.did();
    }

    String scope() {
      return grant.scope();
    }

    String jkt() {
      return grant.jkt();
    }
  }

  private final FakeAtproto fake;
  private final DpopVerifier dpop = new DpopVerifier();
  private final KeyPair signingKey = KeyPairs.generate(KeyType.P256);
  private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
      .connectTimeout(Duration.ofSeconds(5)).build();
  private final List<Recorded> requests = new CopyOnWriteArrayList<>();

  private final Map<String, ParRequest> parRequests = new ConcurrentHashMap<>();
  private final Map<String, Code> codes = new ConcurrentHashMap<>();
  private final Map<String, RefreshToken> refreshTokens = new ConcurrentHashMap<>();
  private final Map<String, AccessToken> accessTokens = new ConcurrentHashMap<>();
  private final Set<String> expiredAccessTokens = ConcurrentHashMap.newKeySet();
  private final Set<String> revokedGrants = ConcurrentHashMap.newKeySet();
  private final Map<String, Instant> assertionJtis = new ConcurrentHashMap<>();

  private final AtomicInteger parCount = new AtomicInteger();
  private final AtomicInteger tokenCount = new AtomicInteger();
  private final AtomicInteger refreshCount = new AtomicInteger();
  private final AtomicInteger revokeCount = new AtomicInteger();

  private volatile boolean wrongIss;
  private volatile Did wrongSub;
  private volatile boolean dropAtprotoScope;
  private final AtomicBoolean denyNext = new AtomicBoolean();
  private volatile Duration accessTokenTtl = Duration.ofMinutes(15);
  private final List<String> rejectedScopePrefixes = new CopyOnWriteArrayList<>();
  private volatile FakeAccount signInAs;

  FakeAuthorizationServer(FakeAtproto fake) {
    this.fake = fake;
  }

  Router router(io.vertx.core.Vertx vertx) {
    Router router = Http.router(vertx, requests);
    router.route().handler(ctx -> {
      ctx.addHeadersEndHandler(v -> ctx.response().headers().set("DPoP-Nonce", dpop.nonce()));
      ctx.next();
    });
    router.get("/.well-known/oauth-authorization-server").handler(ctx -> Http.json(ctx, 200, metadata()));
    router.post("/oauth/par").blockingHandler(guard(this::par), false);
    router.get("/oauth/authorize").blockingHandler(guard(this::authorize), false);
    router.post("/oauth/token").blockingHandler(guard(this::token), false);
    router.post("/oauth/revoke").blockingHandler(guard(this::revoke), false);
    return router;
  }

  private static Handler<RoutingContext> guard(Handler<RoutingContext> h) {
    return ctx -> {
      try {
        h.handle(ctx);
      } catch (OAuthError e) {
        Http.oauthError(ctx, e.status, e.error, e.getMessage());
      } catch (DpopVerifier.DpopException e) {
        Http.oauthError(ctx, 400, e.error(), e.getMessage());
      } catch (RuntimeException e) {
        Http.oauthError(ctx, 500, "server_error", String.valueOf(e));
      }
    };
  }

  /** The issuer: this server's origin, {@code http://127.0.0.1:<port>}. */
  public String issuer() {
    return fake.asUrl().toString();
  }

  /** The key access tokens are signed with. */
  public PublicKey accessTokenKey() {
    return signingKey.getPublic();
  }

  public Map<String, Object> metadata() {
    String iss = issuer();
    Map<String, Object> md = new LinkedHashMap<>();
    md.put("issuer", iss);
    md.put("authorization_endpoint", iss + "/oauth/authorize");
    md.put("token_endpoint", iss + "/oauth/token");
    md.put("pushed_authorization_request_endpoint", iss + "/oauth/par");
    md.put("revocation_endpoint", iss + "/oauth/revoke");
    md.put("require_pushed_authorization_requests", true);
    md.put("authorization_response_iss_parameter_supported", true);
    md.put("client_id_metadata_document_supported", true);
    md.put("dpop_signing_alg_values_supported", List.of("ES256"));
    md.put("token_endpoint_auth_methods_supported", List.of("none", "private_key_jwt"));
    md.put("token_endpoint_auth_signing_alg_values_supported", List.of("ES256"));
    md.put("scopes_supported", List.of("atproto", "transition:generic", "transition:chat.bsky", "transition:email"));
    md.put("response_types_supported", List.of("code"));
    md.put("response_modes_supported", List.of("query", "fragment", "form_post"));
    md.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
    md.put("code_challenge_methods_supported", List.of("S256"));
    md.put("subject_types_supported", List.of("public"));
    md.put("request_parameter_supported", false);
    md.put("request_uri_parameter_supported", true);
    md.put("require_request_uri_registration", true);
    md.put("protected_resources", List.of(fake.pdsUrl().toString()));
    return md;
  }

  // ---- PAR --------------------------------------------------------------------------------------------------------

  private void par(RoutingContext ctx) {
    Map<String, List<String>> form = Http.form(Http.bodyText(ctx));
    DpopVerifier.Proof proof = dpop.verify(ctx.request().getHeader("DPoP"), "POST", Http.requestUrl(ctx), null);
    Client client = resolveClient(required(form, "client_id"));
    authenticate(client, form);
    if (!"code".equals(required(form, "response_type"))) {
      throw new OAuthError(400, "unsupported_response_type", "response_type must be code");
    }
    String challenge = required(form, "code_challenge");
    if (!"S256".equals(single(form, "code_challenge_method"))) {
      throw new OAuthError(400, "invalid_request", "code_challenge_method must be S256");
    }
    if (!CHALLENGE.matcher(challenge).matches()) {
      throw new OAuthError(400, "invalid_request", "code_challenge is not a base64url SHA-256");
    }
    String state = required(form, "state");
    String redirectUri = required(form, "redirect_uri");
    if (!redirectAllowed(client, redirectUri)) {
      throw new OAuthError(400, "invalid_request", "redirect_uri is not registered by the client");
    }
    String scope = required(form, "scope");
    checkScope(client, scope);
    String dpopJkt = single(form, "dpop_jkt");
    if (dpopJkt != null && !dpopJkt.equals(proof.jkt())) {
      throw new OAuthError(400, "invalid_request", "dpop_jkt does not match the DPoP proof");
    }
    String requestUri = REQUEST_URI_PREFIX + "req-" + random();
    parRequests.put(requestUri, new ParRequest(client, redirectUri, state, challenge, scope, single(form, "login_hint"),
        proof.jkt(), Instant.now().plus(PAR_TTL), new AtomicBoolean()));
    parCount.incrementAndGet();
    Http.json(ctx, 201, Map.of("request_uri", requestUri, "expires_in", PAR_TTL.toSeconds()));
  }

  private void checkScope(Client client, String scope) {
    ScopeRules rules;
    try {
      rules = ScopeRules.parse(scope);
    } catch (ScopeRules.InvalidScopeException e) {
      throw new OAuthError(400, "invalid_scope", e.getMessage());
    }
    if (!rules.hasAtproto()) {
      throw new OAuthError(400, "invalid_scope", "The atproto scope is required");
    }
    for (String s : rules.scopes()) {
      if (!client.scopes().contains(s)) {
        throw new OAuthError(400, "invalid_scope", "Scope not registered by the client: " + s);
      }
      for (String prefix : rejectedScopePrefixes) {
        if (s.startsWith(prefix)) {
          throw new OAuthError(400, "invalid_scope", "Scope not supported: " + s);
        }
      }
    }
  }

  // ---- authorize --------------------------------------------------------------------------------------------------

  private void authorize(RoutingContext ctx) {
    String clientId = ctx.request().getParam("client_id");
    String requestUri = ctx.request().getParam("request_uri");
    if (clientId == null || requestUri == null) {
      throw new OAuthError(400, "invalid_request", "client_id and request_uri are required");
    }
    ParRequest par = parRequests.remove(requestUri);
    if (par == null || par.expires().isBefore(Instant.now())) {
      throw new OAuthError(400, "invalid_request", "Unknown or expired request_uri");
    }
    if (!par.client().clientId().equals(clientId)) {
      throw new OAuthError(400, "invalid_request", "client_id does not match the pushed request");
    }
    Map<String, String> params = new LinkedHashMap<>();
    Optional<FakeAccount> account = pickAccount(par.loginHint());
    if (denyNext.getAndSet(false) || account.isEmpty()) {
      params.put("error", "access_denied");
      params.put("error_description", "The user denied the request");
    } else {
      String code = "cod-" + random();
      codes.put(code, new Code(par, account.get().did(), Instant.now().plus(CODE_TTL)));
      params.put("code", code);
    }
    params.put("state", par.state());
    params.put("iss", wrongIss ? "http://wrong-issuer.invalid" : issuer());
    StringBuilder location = new StringBuilder(par.redirectUri());
    char sep = par.redirectUri().contains("?") ? '&' : '?';
    for (Map.Entry<String, String> e : params.entrySet()) {
      location.append(sep).append(enc(e.getKey())).append('=').append(enc(e.getValue()));
      sep = '&';
    }
    ctx.response().setStatusCode(302).putHeader("Location", location.toString()).putHeader("Cache-Control", "no-store")
        .end();
  }

  private Optional<FakeAccount> pickAccount(String loginHint) {
    if (signInAs != null) {
      return Optional.of(signInAs);
    }
    if (loginHint != null) {
      Optional<FakeAccount> hinted = fake.findAccount(loginHint);
      if (hinted.isPresent()) {
        return hinted;
      }
    }
    return fake.accounts().stream().findFirst();
  }

  // ---- token ------------------------------------------------------------------------------------------------------

  private void token(RoutingContext ctx) {
    Map<String, List<String>> form = Http.form(Http.bodyText(ctx));
    DpopVerifier.Proof proof = dpop.verify(ctx.request().getHeader("DPoP"), "POST", Http.requestUrl(ctx), null);
    Client client = resolveClient(required(form, "client_id"));
    authenticate(client, form);
    String grantType = required(form, "grant_type");
    switch (grantType) {
      case "authorization_code" -> {
        Code code = codes.remove(required(form, "code"));
        if (code == null || code.expires().isBefore(Instant.now())) {
          throw new OAuthError(400, "invalid_grant", "Unknown, used or expired code");
        }
        ParRequest par = code.par();
        if (!par.client().clientId().equals(client.clientId())) {
          throw new OAuthError(400, "invalid_grant", "The code was issued to another client");
        }
        if (!par.redirectUri().equals(single(form, "redirect_uri"))) {
          throw new OAuthError(400, "invalid_grant", "redirect_uri does not match the authorization request");
        }
        String verifier = single(form, "code_verifier");
        if (verifier == null || !VERIFIER.matcher(verifier).matches() || !Jwt.sha256(verifier).equals(par.challenge())) {
          throw new OAuthError(400, "invalid_grant", "PKCE verification failed");
        }
        if (!par.jkt().equals(proof.jkt())) {
          throw new OAuthError(400, "invalid_grant", "DPoP key differs from the pushed authorization request");
        }
        String scope = par.scope();
        Grant grant = new Grant(random(), client.clientId(), code.did(), scope, proof.jkt());
        tokenCount.incrementAndGet();
        Http.json(ctx, 200, issue(grant));
      }
      case "refresh_token" -> {
        RefreshToken rt = refreshTokens.get(required(form, "refresh_token"));
        if (rt == null || revokedGrants.contains(rt.grant().id())) {
          throw new OAuthError(400, "invalid_grant", "Unknown or revoked refresh token");
        }
        if (!rt.grant().clientId().equals(client.clientId())) {
          throw new OAuthError(400, "invalid_grant", "The refresh token was issued to another client");
        }
        if (!rt.grant().jkt().equals(proof.jkt())) {
          throw new OAuthError(400, "invalid_grant", "DPoP key differs from the one the session is bound to");
        }
        if (!rt.used().compareAndSet(false, true)) {
          throw new OAuthError(400, "invalid_grant", "Refresh token replayed");
        }
        refreshCount.incrementAndGet();
        Http.json(ctx, 200, issue(rt.grant()));
      }
      default -> throw new OAuthError(400, "unsupported_grant_type", "Unsupported grant_type " + grantType);
    }
  }

  private Map<String, Object> issue(Grant grant) {
    Instant now = Instant.now();
    Instant exp = now.plus(accessTokenTtl);
    String jti = random();
    String scope = grant.scope();
    if (dropAtprotoScope) {
      List<String> kept = new ArrayList<>();
      for (String s : scope.split(" ")) {
        if (!s.equals("atproto")) {
          kept.add(s);
        }
      }
      scope = String.join(" ", kept);
    }
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", issuer());
    claims.put("aud", fake.pdsUrl().toString());
    claims.put("sub", grant.did().value());
    claims.put("client_id", grant.clientId());
    claims.put("scope", grant.scope());
    claims.put("cnf", Map.of("jkt", grant.jkt()));
    claims.put("jti", jti);
    claims.put("iat", now.getEpochSecond());
    claims.put("exp", exp.getEpochSecond());
    String accessToken = Jwt.sign(Map.of("typ", "at+jwt", "alg", "ES256"), claims, signingKey.getPrivate());
    accessTokens.put(jti, new AccessToken(jti, grant, exp));
    String refreshToken = "ref-" + random();
    refreshTokens.put(refreshToken, new RefreshToken(grant, new AtomicBoolean()));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("access_token", accessToken);
    body.put("token_type", "DPoP");
    body.put("expires_in", Math.max(0, accessTokenTtl.toSeconds()));
    body.put("refresh_token", refreshToken);
    body.put("scope", scope);
    body.put("sub", (wrongSub != null ? wrongSub : grant.did()).value());
    return body;
  }

  // ---- revoke -----------------------------------------------------------------------------------------------------

  private void revoke(RoutingContext ctx) {
    Map<String, List<String>> form = Http.form(Http.bodyText(ctx));
    if (ctx.request().getHeader("DPoP") != null) {
      dpop.verify(ctx.request().getHeader("DPoP"), "POST", Http.requestUrl(ctx), null);
    }
    String clientId = single(form, "client_id");
    if (clientId != null) {
      authenticate(resolveClient(clientId), form);
    }
    String token = required(form, "token");
    RefreshToken rt = refreshTokens.get(token);
    if (rt != null) {
      revokedGrants.add(rt.grant().id());
    } else {
      try {
        Jwt jwt = Jwt.parse(token);
        AccessToken at = jwt.verifiesWith(accessTokenKey()) ? accessTokens.get(jwt.text("jti")) : null;
        if (at != null) {
          revokedGrants.add(at.grant().id());
        }
      } catch (Jwt.MalformedJwtException e) {
        // unknown tokens are ignored (RFC 7009)
      }
    }
    revokeCount.incrementAndGet();
    ctx.response().setStatusCode(200).putHeader("Cache-Control", "no-store").end();
  }

  /**
   * Checks an access token presented to the PDS: signed by this server, issued here, not expired (by time or by
   * {@link FakePds#expireTokens()}) and not revoked.
   */
  AccessToken introspect(String token) {
    Jwt jwt;
    try {
      jwt = Jwt.parse(token);
    } catch (Jwt.MalformedJwtException e) {
      throw new IllegalArgumentException("Malformed access token");
    }
    if (!jwt.verifiesWith(accessTokenKey()) || !issuer().equals(jwt.text("iss"))) {
      throw new IllegalArgumentException("Access token not issued by this server");
    }
    AccessToken at = accessTokens.get(String.valueOf(jwt.text("jti")));
    if (at == null) {
      throw new IllegalArgumentException("Unknown access token");
    }
    if (expiredAccessTokens.contains(at.jti()) || !at.expires().isAfter(Instant.now())) {
      throw new IllegalArgumentException("\"exp\" claim timestamp check failed");
    }
    if (revokedGrants.contains(at.grant().id())) {
      throw new IllegalArgumentException("Token has been revoked");
    }
    return at;
  }

  void expireAccessTokens() {
    expiredAccessTokens.addAll(accessTokens.keySet());
  }

  // ---- clients ----------------------------------------------------------------------------------------------------

  Client resolveClient(String clientId) {
    URI uri;
    try {
      uri = URI.create(clientId);
    } catch (IllegalArgumentException e) {
      throw new OAuthError(400, "invalid_client", "client_id is not a URL");
    }
    if ("http".equals(uri.getScheme()) && "localhost".equals(uri.getHost())) {
      return devClient(uri);
    }
    if (uri.getScheme() == null || uri.getHost() == null || uri.getRawFragment() != null) {
      throw new OAuthError(400, "invalid_client", "client_id must be an absolute URL");
    }
    JsonNode md = fetchJson(uri, "client metadata");
    if (!clientId.equals(text(md, "client_id"))) {
      throw new OAuthError(400, "invalid_client_metadata", "client_id in the document does not match its URL");
    }
    List<String> redirects = strings(md.get("redirect_uris"));
    if (redirects.isEmpty()) {
      throw new OAuthError(400, "invalid_client_metadata", "redirect_uris is required");
    }
    if (!md.path("dpop_bound_access_tokens").asBoolean(false)) {
      throw new OAuthError(400, "invalid_client_metadata", "dpop_bound_access_tokens must be true");
    }
    if (!strings(md.get("grant_types")).contains("authorization_code")) {
      throw new OAuthError(400, "invalid_client_metadata", "grant_types must include authorization_code");
    }
    if (!strings(md.get("response_types")).contains("code")) {
      throw new OAuthError(400, "invalid_client_metadata", "response_types must include code");
    }
    String scope = text(md, "scope");
    if (scope == null || !List.of(scope.split(" +")).contains("atproto")) {
      throw new OAuthError(400, "invalid_client_metadata", "scope must include atproto");
    }
    String method = md.has("token_endpoint_auth_method") ? text(md, "token_endpoint_auth_method") : "none";
    boolean confidential;
    if ("private_key_jwt".equals(method)) {
      confidential = true;
      if (!"ES256".equals(text(md, "token_endpoint_auth_signing_alg"))) {
        throw new OAuthError(400, "invalid_client_metadata", "token_endpoint_auth_signing_alg must be ES256");
      }
      if (!md.has("jwks_uri") && !md.has("jwks")) {
        throw new OAuthError(400, "invalid_client_metadata", "jwks_uri or jwks is required for private_key_jwt");
      }
    } else if ("none".equals(method)) {
      confidential = false;
    } else {
      throw new OAuthError(400, "invalid_client_metadata", "Unsupported token_endpoint_auth_method " + method);
    }
    URI jwksUri = md.has("jwks_uri") ? URI.create(text(md, "jwks_uri")) : null;
    return new Client(clientId, confidential, false, redirects, new LinkedHashSet<>(List.of(scope.split(" +"))),
        jwksUri, md.get("jwks"));
  }

  private static Client devClient(URI uri) {
    if (uri.getPort() != -1 || !(uri.getRawPath() == null || uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
        || uri.getRawFragment() != null) {
      throw new OAuthError(400, "invalid_client", "A localhost client_id is exactly http://localhost plus a query");
    }
    Map<String, List<String>> q = Http.form(uri.getRawQuery());
    List<String> redirects = q.getOrDefault("redirect_uri", List.of("http://127.0.0.1/", "http://[::1]/"));
    for (String r : redirects) {
      URI ru = URI.create(r);
      if (!"http".equals(ru.getScheme()) || !("127.0.0.1".equals(ru.getHost()) || "[::1]".equals(ru.getHost()))) {
        throw new OAuthError(400, "invalid_client", "Localhost client redirect_uri must be http://127.0.0.1 or [::1]");
      }
    }
    List<String> scopes = q.getOrDefault("scope", List.of("atproto"));
    if (scopes.size() != 1) {
      throw new OAuthError(400, "invalid_client", "scope given more than once");
    }
    return new Client(uri.toString(), false, true, redirects, new LinkedHashSet<>(List.of(scopes.get(0).split(" +"))),
        null, null);
  }

  private static boolean redirectAllowed(Client client, String redirectUri) {
    if (client.redirectUris().contains(redirectUri)) {
      return true;
    }
    if (!client.loopbackDev()) {
      return false;
    }
    // RFC 8252: the port of a loopback redirect is not compared
    URI actual = URI.create(redirectUri);
    for (String r : client.redirectUris()) {
      URI registered = URI.create(r);
      if ("http".equals(actual.getScheme()) && registered.getHost().equals(actual.getHost())
          && pathOf(registered).equals(pathOf(actual))
          && String.valueOf(registered.getRawQuery()).equals(String.valueOf(actual.getRawQuery()))) {
        return true;
      }
    }
    return false;
  }

  private static String pathOf(URI u) {
    return u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
  }

  private void authenticate(Client client, Map<String, List<String>> form) {
    String type = single(form, "client_assertion_type");
    String assertion = single(form, "client_assertion");
    if (!client.confidential()) {
      if (type != null || assertion != null) {
        throw new OAuthError(401, "invalid_client", "A public client must not send a client assertion");
      }
      return;
    }
    if (!JWT_BEARER.equals(type) || assertion == null) {
      throw new OAuthError(401, "invalid_client", "private_key_jwt client authentication is required");
    }
    Jwt jwt;
    try {
      jwt = Jwt.parse(assertion);
    } catch (Jwt.MalformedJwtException e) {
      throw new OAuthError(401, "invalid_client", "Malformed client assertion");
    }
    if (!"ES256".equals(jwt.header().path("alg").asText())) {
      throw new OAuthError(401, "invalid_client", "Client assertion must be ES256");
    }
    if (!client.clientId().equals(jwt.text("iss")) || !client.clientId().equals(jwt.text("sub"))) {
      throw new OAuthError(401, "invalid_client", "Client assertion iss and sub must be the client_id");
    }
    JsonNode aud = jwt.claims().get("aud");
    boolean audOk = aud != null && (aud.isTextual() ? aud.asText().equals(issuer())
        : aud.isArray() && strings(aud).contains(issuer()));
    if (!audOk) {
      throw new OAuthError(401, "invalid_client", "Client assertion aud must be the issuer");
    }
    long now = Instant.now().getEpochSecond();
    Long iat = jwt.seconds("iat");
    if (iat == null || iat > now + 60 || iat < now - 300) {
      throw new OAuthError(401, "invalid_client", "Client assertion iat missing or not recent");
    }
    Long exp = jwt.seconds("exp");
    if (exp != null && exp < now) {
      throw new OAuthError(401, "invalid_client", "Client assertion expired");
    }
    String jti = jwt.text("jti");
    if (jti == null) {
      throw new OAuthError(401, "invalid_client", "Client assertion jti is required");
    }
    JsonNode jwks = client.jwks() != null ? client.jwks() : fetchJson(client.jwksUri(), "client JWKS");
    String kid = jwt.header().path("kid").asText(null);
    boolean verified = false;
    for (JsonNode k : jwks.path("keys")) {
      if (kid != null && k.has("kid") && !kid.equals(k.path("kid").asText())) {
        continue;
      }
      if (!"EC".equals(k.path("kty").asText()) || !"P-256".equals(k.path("crv").asText())) {
        continue;
      }
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> jwk = Jwt.MAPPER.convertValue(k, Map.class);
        jwk.remove("d");
        if (jwt.verifiesWith(Jwk.publicKey(jwk))) {
          verified = true;
          break;
        }
      } catch (RuntimeException e) {
        // unusable key: try the next one
      }
    }
    if (!verified) {
      throw new OAuthError(401, "invalid_client", "Client assertion signature does not verify with the client's keys");
    }
    assertionJtis.values().removeIf(t -> t.isBefore(Instant.now().minusSeconds(600)));
    if (assertionJtis.putIfAbsent(client.clientId() + " " + jti, Instant.now()) != null) {
      throw new OAuthError(401, "invalid_client", "Client assertion jti replayed");
    }
  }

  private JsonNode fetchJson(URI uri, String what) {
    try {
      HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
          .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
      if (r.statusCode() != 200) {
        throw new OAuthError(400, "invalid_client_metadata", what + " at " + uri + " answered " + r.statusCode());
      }
      JsonNode node = Jwt.MAPPER.readTree(r.body());
      if (node == null || !node.isObject()) {
        throw new OAuthError(400, "invalid_client_metadata", what + " at " + uri + " is not a JSON object");
      }
      return node;
    } catch (IOException e) {
      throw new OAuthError(400, "invalid_client_metadata", what + " at " + uri + " could not be fetched: " + e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OAuthError(500, "server_error", "interrupted");
    }
  }

  // ---- knobs and counters -----------------------------------------------------------------------------------------

  /** Callbacks carry a wrong {@code iss} (until {@link FakeAtproto#clear()}). */
  public void wrongIss() {
    wrongIss = true;
  }

  /** Token responses carry {@code sub} = {@code did} instead of the account's DID. */
  public void wrongSub(Did did) {
    wrongSub = did;
  }

  /** Token responses carry the granted scope without {@code atproto}. */
  public void dropAtprotoScope() {
    dropAtprotoScope = true;
  }

  /** The next authorization is denied ({@code error=access_denied}). */
  public void denyNext() {
    denyNext.set(true);
  }

  /** Rotates the DPoP nonce after every {@code n} accepted proofs ({@code 0}: never). */
  public void rotateNonceEvery(int n) {
    dpop.rotateNonceEvery(n);
  }

  /** Rotates the DPoP nonce now. */
  public void rotateNonceNow() {
    dpop.rotateNonce();
  }

  /** Lifetime of access tokens issued from now on (default 15 minutes; zero or negative issues expired tokens). */
  public void accessTokenTtl(Duration ttl) {
    accessTokenTtl = ttl;
  }

  /** PAR answers {@code invalid_scope} when any requested scope starts with {@code prefix}. */
  public void rejectScopesStartingWith(String prefix) {
    rejectedScopePrefixes.add(prefix);
  }

  /** Authorizations sign in as {@code account}, whatever the {@code login_hint}. */
  public void signInAs(FakeAccount account) {
    signInAs = account;
  }

  /** The current DPoP nonce. */
  public String nonce() {
    return dpop.nonce();
  }

  /** Successful pushed authorization requests. */
  public int parCount() {
    return parCount.get();
  }

  /** Successful authorization code exchanges. */
  public int tokenCount() {
    return tokenCount.get();
  }

  /** Successful refreshes. */
  public int refreshCount() {
    return refreshCount.get();
  }

  /** Accepted revocation requests. */
  public int revokeCount() {
    return revokeCount.get();
  }

  public List<Recorded> requests() {
    return List.copyOf(requests);
  }

  void clear() {
    wrongIss = false;
    wrongSub = null;
    dropAtprotoScope = false;
    denyNext.set(false);
    accessTokenTtl = Duration.ofMinutes(15);
    rejectedScopePrefixes.clear();
    signInAs = null;
    dpop.reset();
    parCount.set(0);
    tokenCount.set(0);
    refreshCount.set(0);
    revokeCount.set(0);
    parRequests.clear();
    codes.clear();
    refreshTokens.clear();
    accessTokens.clear();
    expiredAccessTokens.clear();
    revokedGrants.clear();
    assertionJtis.clear();
    requests.clear();
  }

  // ---- helpers ----------------------------------------------------------------------------------------------------

  private static String single(Map<String, List<String>> form, String name) {
    List<String> v = form.get(name);
    if (v == null) {
      return null;
    }
    if (v.size() != 1) {
      throw new OAuthError(400, "invalid_request", name + " given more than once");
    }
    return v.get(0);
  }

  private static String required(Map<String, List<String>> form, String name) {
    String v = single(form, name);
    if (v == null || v.isEmpty()) {
      throw new OAuthError(400, "invalid_request", name + " is required");
    }
    return v;
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v != null && v.isTextual() ? v.asText() : null;
  }

  private static List<String> strings(JsonNode n) {
    List<String> out = new ArrayList<>();
    if (n != null && n.isArray()) {
      n.forEach(e -> out.add(e.asText()));
    }
    return out;
  }

  static String random() {
    byte[] b = new byte[24];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static String enc(String s) {
    return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
