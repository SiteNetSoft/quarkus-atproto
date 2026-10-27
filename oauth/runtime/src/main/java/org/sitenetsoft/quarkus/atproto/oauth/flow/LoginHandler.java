package org.sitenetsoft.quarkus.atproto.oauth.flow;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Singleton;

import org.jboss.logging.Logger;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.multikey.Jwk;
import org.sitenetsoft.multikey.KeyPairs;
import org.sitenetsoft.multikey.KeyType;
import org.sitenetsoft.quarkus.atproto.identity.AtprotoIdentity;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;
import org.sitenetsoft.quarkus.atproto.oauth.Origins;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientIdentity;
import org.sitenetsoft.quarkus.atproto.oauth.discovery.DiscoveredServer;
import org.sitenetsoft.quarkus.atproto.oauth.discovery.Discovery;
import org.sitenetsoft.quarkus.atproto.oauth.session.PendingAuthorization;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionCookie;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionStores;

import io.quarkus.security.identity.IdentityProviderManager;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code POST {login-path}} (form {@code handle=<handle | DID | server URL>}, optional {@code redirect=<local
 * path>}), <b>same-origin only</b> ({@link #crossSite}: 403 otherwise; {@code GET}: 405), so another site cannot start
 * a sign-in in the user's browser (login CSRF: a forced sign-in to the attacker's account): discovery, a new DPoP key,
 * PKCE and {@code state}, a pushed authorization request (with the fallback scope once on {@code invalid_scope} when
 * configured), the pending entry (bound to this browser by a binding cookie, see {@link SessionCookie}), then 302 to
 * the authorization endpoint with {@code client_id} and {@code request_uri} only.
 * <p>
 * {@code link} mode: the request must carry the application's own signed-in identity (401 otherwise, before anything
 * is sent anywhere); the pending entry records that user and the callback must be made by the same user.
 */
@Singleton
public class LoginHandler implements Handler<RoutingContext> {

    private static final Logger LOG = Logger.getLogger(LoginHandler.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthConfig config;
    private final ClientIdentity client;
    private final Discovery discovery;
    private final ParClient par;
    private final SessionStores stores;
    private final SessionCookie cookie;
    private final IdentityProviderManager identities;

    public LoginHandler(OAuthConfig config, ClientIdentity client, Discovery discovery, ParClient par,
            SessionStores stores, SessionCookie cookie, IdentityProviderManager identities) {
        this.config = config;
        this.client = client;
        this.discovery = discovery;
        this.par = par;
        this.stores = stores;
        this.cookie = cookie;
        this.identities = identities;
    }

    /** Where the login starts: the server, and the account when one was given. */
    private record Start(DiscoveredServer server, Optional<Did> expectedDid, Optional<Handle> handle,
            Optional<String> loginHint) {
    }

    private record Pushed(String requestUri, String scope) {
    }

    @Override
    public void handle(RoutingContext ctx) {
        if (ctx.request().method() != HttpMethod.POST) {
            // a GET is a cross-site top-level navigation away from a forced sign-in (login CSRF)
            ctx.response().setStatusCode(405).putHeader("Allow", "POST").putHeader("Cache-Control", "no-store")
                    .end("Start the sign-in with a POST from this site's form");
            return;
        }
        Optional<String> crossSite = crossSite(ctx);
        if (crossSite.isPresent()) {
            Responses.fail(ctx, config, "login", new LoginFailure(403, "cross_site_request", crossSite.get()));
            return;
        }
        Context context = ctx.vertx().getOrCreateContext();
        // the binding cookie goes out with the redirect only (not with a refusal)
        String binding = cookie.newBinding();
        Uni<Optional<String>> appPrincipal = config.mode() == OAuthConfig.Mode.LINK
                ? AppIdentity.required(ctx, identities, "link an AT Protocol account").map(Optional::of)
                : Uni.createFrom().item(Optional.empty());
        // read the request (query, form body) before any hop to another thread
        Uni<Map<String, String>> params = Responses.params(ctx);
        appPrincipal.chain(principal -> params.chain(p -> start(p, SessionCookie.bindingHash(binding), principal)))
                .subscribe().with(
                        location -> Responses.on(context, () -> {
                            cookie.addBinding(ctx, binding);
                            Responses.redirect(ctx, location);
                        }),
                        error -> Responses.on(context, () -> Responses.fail(ctx, config, "login", error)));
    }

    /**
     * Why the request is not same-origin, or empty when it is: {@code Sec-Fetch-Site} must be {@code same-origin} when
     * present; otherwise {@code Origin} must be this application's origin (of {@code client-base-url} for a
     * confidential client, of the request's own host for the development client); neither header: refused.
     */
    Optional<String> crossSite(RoutingContext ctx) {
        String fetchSite = ctx.request().getHeader("Sec-Fetch-Site");
        if (fetchSite != null) {
            return "same-origin".equalsIgnoreCase(fetchSite.strip()) ? Optional.empty()
                    : Optional.of("The sign-in must be started from this site (Sec-Fetch-Site: "
                            + AuthServerClient.abbreviate(fetchSite) + ")");
        }
        String origin = ctx.request().getHeader("Origin");
        if (origin == null) {
            return Optional.of("The sign-in must be started from this site (no Origin or Sec-Fetch-Site header)");
        }
        String expected;
        try {
            if (client.confidential() && client.baseUrl().isPresent()) {
                expected = Origins.of(client.baseUrl().get());
            } else {
                io.vertx.core.net.HostAndPort a = ctx.request().authority();
                if (a == null) {
                    throw new IllegalArgumentException("no Host");
                }
                expected = Origins.of(URI.create(ctx.request().scheme() + "://" + a.host()
                        + (a.port() > 0 ? ":" + a.port() : "")));
            }
        } catch (IllegalArgumentException e) {
            return Optional.of("The sign-in must be started from this site (unknown request origin)");
        }
        try {
            if (Origins.of(URI.create(origin.strip())).equals(expected)) {
                return Optional.empty();
            }
        } catch (IllegalArgumentException e) {
            // "null" or garbage: refused below
        }
        return Optional.of("The sign-in must be started from this site (Origin " + AuthServerClient.abbreviate(origin)
                + ")");
    }

    private Uni<String> start(Map<String, String> params, String browserBinding, Optional<String> appPrincipal) {
        String input = params.getOrDefault("handle", "").strip();
        if (input.isEmpty()) {
            throw new LoginFailure(400, "invalid_request", "Enter your handle (for example alice.bsky.social)");
        }
        String target = Responses.localTarget(params.get("redirect"), config.landingPage());
        return discover(input).chain(start -> {
            KeyPair dpopKey = KeyPairs.generate(KeyType.P256);
            String verifier = random(32);
            String state = random(32);
            return push(start, dpopKey, state, challenge(verifier)).chain(pushed -> {
                PendingAuthorization pending = new PendingAuthorization(start.server().as().issuer(),
                        start.server().as().tokenEndpoint(), start.expectedDid(), start.handle(), verifier,
                        Jwk.ofPrivate(dpopKey.getPrivate(), dpopKey.getPublic()), pushed.scope(), target,
                        browserBinding, Instant.now(), appPrincipal);
                return stores.putPending(state, pending, config.pending().ttl())
                        .map(v -> authorizeUrl(start.server().as().authorizationEndpoint(), pushed.requestUri()));
            });
        });
    }

    private Uni<Start> discover(String input) {
        if (input.startsWith("https://") || input.startsWith("http://")) {
            URI server;
            try {
                server = URI.create(input);
            } catch (IllegalArgumentException e) {
                throw new LoginFailure(400, "invalid_handle", "Not a handle, a DID or a server URL");
            }
            return discovery.resolveServer(server)
                    .map(s -> new Start(s, Optional.empty(), Optional.empty(), Optional.empty()));
        }
        if (input.startsWith("did:")) {
            Did did = Did.parse(input);
            return discovery.resolve(did).map(s -> new Start(s, Optional.of(did),
                    s.identity().map(AtprotoIdentity::handle).filter(h -> !Handle.INVALID.equals(h)),
                    Optional.of(did.value())));
        }
        Handle handle = Handle.normalise(input);
        return discovery.resolve(handle).map(s -> new Start(s, s.identity().map(AtprotoIdentity::did),
                Optional.of(handle), Optional.of(handle.value())));
    }

    private Uni<Pushed> push(Start start, KeyPair dpopKey, String state, String challenge) {
        String scope = config.scope();
        Uni<Pushed> first = par.push(start.server().as(), dpopKey, state, challenge, scope, start.loginHint())
                .map(uri -> new Pushed(uri, scope));
        Optional<String> fallback = config.fallbackScope().map(String::strip).filter(f -> !f.isEmpty())
                .filter(f -> !f.equals(scope));
        if (fallback.isEmpty()) {
            return first;
        }
        return first.onFailure(e -> e instanceof OAuthServerError o && "invalid_scope".equals(o.error()))
                .recoverWithUni(e -> {
                    LOG.infof("%s answered invalid_scope for '%s'; retrying once with the fallback scope '%s'",
                            start.server().as().issuer(), scope, fallback.get());
                    return par.push(start.server().as(), dpopKey, state, challenge, fallback.get(), start.loginHint())
                            .map(uri -> new Pushed(uri, fallback.get()));
                });
    }

    private String authorizeUrl(URI endpoint, String requestUri) {
        String base = endpoint.toString();
        return base + (endpoint.getRawQuery() == null ? "?" : "&") + "client_id=" + enc(client.clientId())
                + "&request_uri=" + enc(requestUri);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** {@code n} random bytes, base64url (32 bytes: a 43-character PKCE verifier or state). */
    static String random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** PKCE S256: base64url(SHA-256(verifier)). */
    static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
