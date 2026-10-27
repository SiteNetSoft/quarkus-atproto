package org.sitenetsoft.quarkus.atproto.test;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.sitenetsoft.atproto.syntax.AtUri;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.atproto.syntax.RecordKey;
import org.sitenetsoft.atproto.syntax.Tid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.vertx.core.Handler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * A PDS (resource server) on loopback:
 * <ul>
 * <li>{@code GET /.well-known/oauth-protected-resource}: {@code resource} = this origin,
 * {@code authorization_servers: [asUrl]}.</li>
 * <li>{@code GET /.well-known/atproto-did} for {@code Host: <handle>}, and {@code GET /wk/<handle>/.well-known/atproto-did}
 * (the base URL for {@code quarkus.atproto.identity.https-well-known-override}, see
 * {@link FakeAtproto#wellKnownBase(String)}).</li>
 * <li>{@code com.atproto.repo.createRecord}, {@code putRecord}, {@code deleteRecord}, {@code uploadBlob} (POST) and
 * {@code getRecord}, {@code describeRepo} (GET). Writes require {@code Authorization: DPoP <token>} plus a
 * {@code DPoP} proof checked by {@link DpopVerifier} against this PDS's own nonce ({@code htm}, {@code htu},
 * {@code iat} within 60 s, unique {@code jti}, {@code ath}); the token must be one the fake authorization server
 * issued, unexpired, unrevoked, for the repo's account, and bound ({@code cnf.jkt}) to the proof's key. Scopes are
 * enforced with {@link ScopeRules}: {@code createRecord} needs {@code create}, {@code putRecord} {@code create} and
 * {@code update}, {@code deleteRecord} {@code delete}, {@code uploadBlob} a matching {@code blob:} scope. Reads are
 * public (a presented token is still checked).</li>
 * </ul>
 * Failures: {@code 401} with {@code WWW-Authenticate: DPoP error="use_dpop_nonce" | "invalid_dpop_proof" |
 * "invalid_token"}, {@code 403} for a missing scope or a foreign repo, {@code 400} XRPC errors
 * ({@code InvalidRequest}, {@code InvalidRecord}, {@code InvalidSwap}, {@code RecordNotFound}, {@code RepoNotFound}).
 * Every response carries {@code DPoP-Nonce}.
 */
public final class FakePds {

  private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

  /** A rejected XRPC request. */
  private static final class XrpcError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final int status;
    final String error;
    final String wwwAuthenticate;

    XrpcError(int status, String error, String message, String wwwAuthenticate) {
      super(message);
      this.status = status;
      this.error = error;
      this.wwwAuthenticate = wwwAuthenticate;
    }
  }

  private final FakeAtproto fake;
  private final DpopVerifier dpop = new DpopVerifier();
  private final List<Recorded> requests = new CopyOnWriteArrayList<>();
  private final Map<AtUri, JsonNode> records = new LinkedHashMap<>();
  private final Map<AtUri, String> cids = new LinkedHashMap<>();
  private final Map<String, byte[]> blobs = new LinkedHashMap<>();
  private final Map<String, String> commits = new LinkedHashMap<>();

  private volatile List<String> authorizationServers;

  FakePds(FakeAtproto fake) {
    this.fake = fake;
  }

  Router router(io.vertx.core.Vertx vertx) {
    Router router = Http.router(vertx, requests);
    router.route().handler(ctx -> {
      ctx.addHeadersEndHandler(v -> ctx.response().headers().set("DPoP-Nonce", dpop.nonce()));
      ctx.next();
    });
    router.get("/.well-known/oauth-protected-resource").handler(ctx -> Http.json(ctx, 200, protectedResource()));
    router.get("/.well-known/atproto-did").handler(ctx -> {
      io.vertx.core.net.HostAndPort a = ctx.request().authority();
      wellKnown(ctx, a == null ? "" : a.host());
    });
    router.get("/wk/:handle/.well-known/atproto-did").handler(ctx -> wellKnown(ctx, ctx.pathParam("handle")));
    router.post("/xrpc/com.atproto.repo.createRecord").blockingHandler(guard(this::createRecord), false);
    router.post("/xrpc/com.atproto.repo.putRecord").blockingHandler(guard(this::putRecord), false);
    router.post("/xrpc/com.atproto.repo.deleteRecord").blockingHandler(guard(this::deleteRecord), false);
    router.post("/xrpc/com.atproto.repo.uploadBlob").blockingHandler(guard(this::uploadBlob), false);
    router.get("/xrpc/com.atproto.repo.getRecord").blockingHandler(guard(this::getRecord), false);
    router.get("/xrpc/com.atproto.repo.describeRepo").blockingHandler(guard(this::describeRepo), false);
    router.route("/xrpc/*").handler(ctx -> Http.xrpcError(ctx, 501, "MethodNotImplemented", "Method Not Implemented"));
    return router;
  }

  private static Handler<RoutingContext> guard(Handler<RoutingContext> h) {
    return ctx -> {
      try {
        h.handle(ctx);
      } catch (XrpcError e) {
        if (e.wwwAuthenticate != null) {
          ctx.response().putHeader("WWW-Authenticate", e.wwwAuthenticate);
        }
        Http.xrpcError(ctx, e.status, e.error, e.getMessage());
      } catch (RuntimeException e) {
        Http.xrpcError(ctx, 500, "InternalServerError", String.valueOf(e));
      }
    };
  }

  /**
   * The {@code authorization_servers} the protected-resource metadata lists instead of {@code [asUrl]} (until
   * {@link FakeAtproto#clear()}).
   */
  public void authorizationServers(List<String> servers) {
    authorizationServers = List.copyOf(servers);
  }

  public Map<String, Object> protectedResource() {
    Map<String, Object> md = new LinkedHashMap<>();
    md.put("resource", fake.pdsUrl().toString());
    List<String> servers = authorizationServers;
    md.put("authorization_servers", servers != null ? servers : List.of(fake.asUrl().toString()));
    md.put("scopes_supported", List.of());
    md.put("bearer_methods_supported", List.of("header"));
    md.put("resource_documentation", "https://atproto.com");
    return md;
  }

  private void wellKnown(RoutingContext ctx, String handle) {
    Optional<FakeAccount> account = fake.findAccount(handle.toLowerCase(Locale.ROOT));
    if (account.isEmpty() || !account.get().handle().value().equals(handle.toLowerCase(Locale.ROOT))) {
      ctx.response().setStatusCode(404).putHeader("Content-Type", "text/plain").end("User not found");
      return;
    }
    ctx.response().setStatusCode(200).putHeader("Content-Type", "text/plain").end(account.get().did().value());
  }

  // ---- auth -------------------------------------------------------------------------------------------------------

  private FakeAuthorizationServer.AccessToken authenticate(RoutingContext ctx) {
    String authorization = ctx.request().getHeader("Authorization");
    if (authorization == null) {
      throw new XrpcError(401, "AuthenticationRequired", "Authentication Required", "DPoP algs=\"ES256\"");
    }
    if (!authorization.regionMatches(true, 0, "DPoP ", 0, 5)) {
      throw new XrpcError(401, "invalid_token", "DPoP-bound access tokens must use the DPoP scheme",
          "DPoP error=\"invalid_token\"");
    }
    String token = authorization.substring(5).trim();
    DpopVerifier.Proof proof;
    try {
      proof = dpop.verify(ctx.request().getHeader("DPoP"), ctx.request().method().name(), Http.requestUrl(ctx), token);
    } catch (DpopVerifier.DpopException e) {
      throw new XrpcError(401, e.error(), e.getMessage(),
          "DPoP error=\"" + e.error() + "\", error_description=\"" + e.getMessage().replace("\"", "'") + "\"");
    }
    FakeAuthorizationServer.AccessToken at;
    try {
      at = fake.as().introspect(token);
    } catch (IllegalArgumentException e) {
      throw new XrpcError(401, "invalid_token", e.getMessage(),
          "DPoP error=\"invalid_token\", error_description=\"" + e.getMessage().replace("\"", "'") + "\"");
    }
    if (!at.jkt().equals(proof.jkt())) {
      throw new XrpcError(401, "invalid_token", "DPoP key does not match the token binding",
          "DPoP error=\"invalid_token\"");
    }
    return at;
  }

  private static void requireScope(boolean granted, String needed) {
    if (!granted) {
      throw new XrpcError(403, "InsufficientScope", "Missing required scope \"" + needed + "\"",
          "DPoP error=\"insufficient_scope\"");
    }
  }

  private FakeAccount ownRepo(FakeAuthorizationServer.AccessToken at, String repo) {
    FakeAccount account = account(repo);
    if (!account.did().equals(at.did())) {
      throw new XrpcError(403, "Forbidden", "The token's account does not own repo " + repo, null);
    }
    return account;
  }

  private FakeAccount account(String repo) {
    if (repo == null) {
      throw new XrpcError(400, "InvalidRequest", "repo is required", null);
    }
    return fake.findAccount(repo)
        .orElseThrow(() -> new XrpcError(400, "RepoNotFound", "Could not find repo: " + repo, null));
  }

  // ---- writes -----------------------------------------------------------------------------------------------------

  private void createRecord(RoutingContext ctx) {
    FakeAuthorizationServer.AccessToken at = authenticate(ctx);
    ObjectNode in = jsonBody(ctx);
    FakeAccount account = ownRepo(at, text(in, "repo"));
    Nsid collection = collection(in);
    requireScope(scope(at).allowsRepo(collection, ScopeRules.Action.CREATE), "repo:" + collection + "?action=create");
    String rkeyText = text(in, "rkey");
    RecordKey rkey = rkeyText == null ? RecordKey.parse(Tid.next().value()) : rkey(rkeyText);
    JsonNode record = record(in, collection);
    AtUri uri = AtUri.of(account.did(), collection, rkey);
    synchronized (this) {
      checkSwapCommit(in, account);
      if (records.containsKey(uri)) {
        throw new XrpcError(400, "InvalidRequest", "Record already exists: " + uri, null);
      }
      Http.json(ctx, 200, write(account, uri, record));
    }
  }

  private void putRecord(RoutingContext ctx) {
    FakeAuthorizationServer.AccessToken at = authenticate(ctx);
    ObjectNode in = jsonBody(ctx);
    FakeAccount account = ownRepo(at, text(in, "repo"));
    Nsid collection = collection(in);
    ScopeRules scope = scope(at);
    requireScope(scope.allowsRepo(collection, ScopeRules.Action.CREATE)
        && scope.allowsRepo(collection, ScopeRules.Action.UPDATE),
        "repo:" + collection + "?action=create&action=update");
    RecordKey rkey = rkey(text(in, "rkey"));
    JsonNode record = record(in, collection);
    AtUri uri = AtUri.of(account.did(), collection, rkey);
    synchronized (this) {
      checkSwapCommit(in, account);
      checkSwapRecord(in, uri);
      Http.json(ctx, 200, write(account, uri, record));
    }
  }

  private void deleteRecord(RoutingContext ctx) {
    FakeAuthorizationServer.AccessToken at = authenticate(ctx);
    ObjectNode in = jsonBody(ctx);
    FakeAccount account = ownRepo(at, text(in, "repo"));
    Nsid collection = collection(in);
    requireScope(scope(at).allowsRepo(collection, ScopeRules.Action.DELETE), "repo:" + collection + "?action=delete");
    AtUri uri = AtUri.of(account.did(), collection, rkey(text(in, "rkey")));
    synchronized (this) {
      checkSwapCommit(in, account);
      checkSwapRecord(in, uri);
      records.remove(uri);
      cids.remove(uri);
      Http.json(ctx, 200, Map.of("commit", commit(account)));
    }
  }

  private void uploadBlob(RoutingContext ctx) {
    FakeAuthorizationServer.AccessToken at = authenticate(ctx);
    String mime = ctx.request().getHeader("Content-Type");
    if (mime == null || mime.isBlank()) {
      throw new XrpcError(400, "InvalidRequest", "Content-Type is required", null);
    }
    mime = mime.split(";")[0].trim().toLowerCase(Locale.ROOT);
    requireScope(scope(at).allowsBlob(mime), "blob:" + mime);
    byte[] data = ctx.body() == null || ctx.body().buffer() == null ? new byte[0] : ctx.body().buffer().getBytes();
    String cid = cid(0x55, data);
    synchronized (this) {
      blobs.put(cid, data);
    }
    Map<String, Object> blob = new LinkedHashMap<>();
    blob.put("$type", "blob");
    blob.put("ref", Map.of("$link", cid));
    blob.put("mimeType", mime);
    blob.put("size", data.length);
    Http.json(ctx, 200, Map.of("blob", blob));
  }

  private Map<String, Object> write(FakeAccount account, AtUri uri, JsonNode record) {
    String cid = cid(0x71, bytes(record));
    records.put(uri, record);
    cids.put(uri, cid);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("uri", uri.toString());
    out.put("cid", cid);
    out.put("commit", commit(account));
    out.put("validationStatus", "unknown");
    return out;
  }

  private Map<String, Object> commit(FakeAccount account) {
    String rev = Tid.next().value();
    String cid = cid(0x71, (account.did().value() + rev).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    commits.put(account.did().value(), cid);
    return Map.of("cid", cid, "rev", rev);
  }

  private void checkSwapCommit(ObjectNode in, FakeAccount account) {
    JsonNode swap = in.get("swapCommit");
    if (swap != null && !swap.isNull() && !swap.asText().equals(commits.get(account.did().value()))) {
      throw new XrpcError(400, "InvalidSwap", "Commit was at " + commits.get(account.did().value()), null);
    }
  }

  private void checkSwapRecord(ObjectNode in, AtUri uri) {
    if (!in.has("swapRecord")) {
      return;
    }
    JsonNode swap = in.get("swapRecord");
    String current = cids.get(uri);
    if (swap.isNull() ? current != null : !swap.asText().equals(current)) {
      throw new XrpcError(400, "InvalidSwap", "Record was at " + current, null);
    }
  }

  // ---- reads ------------------------------------------------------------------------------------------------------

  private void getRecord(RoutingContext ctx) {
    if (ctx.request().getHeader("Authorization") != null) {
      authenticate(ctx);
    }
    FakeAccount account = account(ctx.request().getParam("repo"));
    Nsid collection = nsid(ctx.request().getParam("collection"));
    AtUri uri = AtUri.of(account.did(), collection, rkey(ctx.request().getParam("rkey")));
    String wantedCid = ctx.request().getParam("cid");
    synchronized (this) {
      JsonNode value = records.get(uri);
      if (value == null || (wantedCid != null && !wantedCid.equals(cids.get(uri)))) {
        throw new XrpcError(400, "RecordNotFound", "Could not locate record: " + uri, null);
      }
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("uri", uri.toString());
      out.put("cid", cids.get(uri));
      out.put("value", value);
      Http.json(ctx, 200, out);
    }
  }

  private void describeRepo(RoutingContext ctx) {
    if (ctx.request().getHeader("Authorization") != null) {
      authenticate(ctx);
    }
    FakeAccount account = account(ctx.request().getParam("repo"));
    Set<String> collections = new LinkedHashSet<>();
    synchronized (this) {
      for (AtUri uri : records.keySet()) {
        if (uri.authority().toString().equals(account.did().value())) {
          uri.collection().ifPresent(c -> collections.add(c.value()));
        }
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("handle", account.handle().value());
    out.put("did", account.did().value());
    out.put("didDoc", fake.plc().document(account));
    out.put("collections", List.copyOf(collections));
    out.put("handleIsCorrect", true);
    Http.json(ctx, 200, out);
  }

  // ---- parsing ----------------------------------------------------------------------------------------------------

  private static ObjectNode jsonBody(RoutingContext ctx) {
    try {
      JsonNode n = Http.MAPPER.readTree(Http.bodyText(ctx));
      if (n instanceof ObjectNode o) {
        return o;
      }
    } catch (IOException e) {
      // fall through
    }
    throw new XrpcError(400, "InvalidRequest", "Request body must be a JSON object", null);
  }

  private static String text(JsonNode in, String field) {
    JsonNode n = in.get(field);
    return n != null && n.isTextual() ? n.asText() : null;
  }

  private static Nsid collection(ObjectNode in) {
    return nsid(text(in, "collection"));
  }

  private static Nsid nsid(String s) {
    if (s == null || !Nsid.isValid(s)) {
      throw new XrpcError(400, "InvalidRequest", "Invalid collection: " + s, null);
    }
    return Nsid.parse(s);
  }

  private static RecordKey rkey(String s) {
    if (s == null || !RecordKey.isValid(s)) {
      throw new XrpcError(400, "InvalidRequest", "Invalid rkey: " + s, null);
    }
    return RecordKey.parse(s);
  }

  private static JsonNode record(ObjectNode in, Nsid collection) {
    JsonNode record = in.get("record");
    if (record == null || !record.isObject()) {
      throw new XrpcError(400, "InvalidRequest", "record must be an object", null);
    }
    if (!collection.value().equals(text(record, "$type"))) {
      throw new XrpcError(400, "InvalidRecord", "Invalid $type: expected " + collection, null);
    }
    return record.deepCopy();
  }

  private static ScopeRules scope(FakeAuthorizationServer.AccessToken at) {
    return ScopeRules.parse(at.scope());
  }

  private static byte[] bytes(JsonNode n) {
    try {
      return Http.MAPPER.writeValueAsBytes(n);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A CIDv1 (sha2-256) in base32: codec {@code 0x71} (dag-cbor, {@code bafyrei...}) or {@code 0x55} (raw). */
  static String cid(int codec, byte[] data) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
      byte[] bin = new byte[4 + digest.length];
      bin[0] = 0x01;
      bin[1] = (byte) codec;
      bin[2] = 0x12;
      bin[3] = 0x20;
      System.arraycopy(digest, 0, bin, 4, digest.length);
      StringBuilder sb = new StringBuilder("b");
      int buffer = 0;
      int bits = 0;
      for (byte b : bin) {
        buffer = (buffer << 8) | (b & 0xff);
        bits += 8;
        while (bits >= 5) {
          sb.append(BASE32[(buffer >> (bits - 5)) & 31]);
          bits -= 5;
        }
      }
      if (bits > 0) {
        sb.append(BASE32[(buffer << (5 - bits)) & 31]);
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---- knobs and inspection ---------------------------------------------------------------------------------------

  /** Rotates this PDS's DPoP nonce now. */
  public void rotateNonceNow() {
    dpop.rotateNonce();
  }

  /** Rotates the DPoP nonce after every {@code n} accepted proofs ({@code 0}: never). */
  public void rotateNonceEvery(int n) {
    dpop.rotateNonceEvery(n);
  }

  /** Every access token issued so far is expired from now on ({@code 401 invalid_token}); refreshed ones work. */
  public void expireTokens() {
    fake.as().expireAccessTokens();
  }

  /** The current DPoP nonce. */
  public String nonce() {
    return dpop.nonce();
  }

  /** The stored records, in write order. */
  public synchronized Map<AtUri, JsonNode> records() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(records));
  }

  /** The CID of a stored record. */
  public synchronized Optional<String> cid(AtUri uri) {
    return Optional.ofNullable(cids.get(uri));
  }

  /** Uploaded blobs by CID. */
  public synchronized Map<String, byte[]> blobs() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(blobs));
  }

  /** Every request answered so far. */
  public List<Recorded> requests() {
    return List.copyOf(requests);
  }

  synchronized void clear() {
    authorizationServers = null;
    records.clear();
    cids.clear();
    blobs.clear();
    commits.clear();
    requests.clear();
    dpop.reset();
  }
}
