package org.sitenetsoft.quarkus.atproto.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sitenetsoft.quarkus.atproto.test.TestClient.json;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.multikey.Jwk;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

class FakeAuthorizationServerTest {

  static FakeAtproto fake;
  static FakeAccount alice;
  /** Plays the Quarkus app: serves a confidential client's metadata document and JWKS. */
  static HttpServer app;
  static final KeyPair CLIENT_KEY = TestClient.ecKey();
  static String clientId;
  static String appBase;
  static final String SCOPE = "atproto repo:site.standard.document blob:image/*";

  TestClient client;

  @BeforeAll
  static void startAll() throws IOException {
    fake = FakeAtproto.start();
    alice = fake.account("alice.test");
    app = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    appBase = "http://127.0.0.1:" + app.getAddress().getPort();
    clientId = appBase + "/oauth-client-metadata.json";
    Map<String, Object> jwk = new LinkedHashMap<>(Jwk.of(CLIENT_KEY.getPublic()));
    jwk.put("kid", "k1");
    jwk.put("alg", "ES256");
    jwk.put("use", "sig");
    serve("/jwks.json", Map.of("keys", List.of(jwk)));
    Map<String, Object> md = new LinkedHashMap<>();
    md.put("client_id", clientId);
    md.put("client_name", "Test");
    md.put("client_uri", appBase);
    md.put("redirect_uris", List.of(appBase + "/callback"));
    md.put("grant_types", List.of("authorization_code", "refresh_token"));
    md.put("response_types", List.of("code"));
    md.put("scope", SCOPE);
    md.put("dpop_bound_access_tokens", true);
    md.put("application_type", "web");
    md.put("token_endpoint_auth_method", "private_key_jwt");
    md.put("token_endpoint_auth_signing_alg", "ES256");
    md.put("jwks_uri", appBase + "/jwks.json");
    serve("/oauth-client-metadata.json", md);
    app.start();
  }

  static void serve(String path, Object body) {
    app.createContext(path, ex -> {
      byte[] b = TestClient.JSON.writeValueAsBytes(body);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, b.length);
      ex.getResponseBody().write(b);
      ex.close();
    });
  }

  @AfterAll
  static void stopAll() {
    app.stop(0);
    fake.close();
  }

  @BeforeEach
  void reset() {
    fake.clear();
    client = new TestClient();
  }

  URI par() {
    return fake.asUrl().resolve("/oauth/par");
  }

  URI token() {
    return fake.asUrl().resolve("/oauth/token");
  }

  String assertion(KeyPair key, String aud) {
    Map<String, Object> header = Map.of("alg", "ES256", "typ", "JWT", "kid", "k1");
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", clientId);
    claims.put("sub", clientId);
    claims.put("aud", aud);
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("iat", Instant.now().getEpochSecond());
    return TestClient.jwt(header, claims, key);
  }

  Map<String, String> confidentialPar(String verifier, KeyPair assertionKey) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("client_id", clientId);
    p.put("response_type", "code");
    p.put("code_challenge", TestClient.sha256(verifier));
    p.put("code_challenge_method", "S256");
    p.put("state", "st-1");
    p.put("redirect_uri", appBase + "/callback");
    p.put("scope", SCOPE);
    p.put("login_hint", "alice.test");
    p.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
    p.put("client_assertion", assertion(assertionKey, fake.asUrl().toString()));
    return p;
  }

  Map<String, String> devPar(String verifier, String scope) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("client_id", TestClient.devClientId(TestClient.REDIRECT, scope));
    p.put("response_type", "code");
    p.put("code_challenge", TestClient.sha256(verifier));
    p.put("code_challenge_method", "S256");
    p.put("state", "st-2");
    p.put("redirect_uri", TestClient.REDIRECT);
    p.put("scope", scope);
    return p;
  }

  @Test
  void metadataHasRequiredFlagsAndIssuerIsOrigin() {
    JsonNode md = json(client.get(fake.asUrl().resolve("/.well-known/oauth-authorization-server")));
    String origin = "http://127.0.0.1:" + fake.asUrl().getPort();
    assertEquals(origin, md.get("issuer").asText());
    assertTrue(md.get("require_pushed_authorization_requests").asBoolean());
    assertTrue(md.get("authorization_response_iss_parameter_supported").asBoolean());
    assertTrue(md.get("client_id_metadata_document_supported").asBoolean());
    assertEquals("ES256", md.get("dpop_signing_alg_values_supported").get(0).asText());
    assertEquals(origin + "/oauth/par", md.get("pushed_authorization_request_endpoint").asText());
  }

  @Test
  void parRequiresNonceThenSucceeds() {
    Map<String, String> p = confidentialPar("v".repeat(50), CLIENT_KEY);
    HttpResponse<String> first = client.postAs(par(), p);
    assertEquals(400, first.statusCode(), first.body());
    assertEquals("use_dpop_nonce", json(first).get("error").asText());
    assertTrue(first.headers().firstValue("DPoP-Nonce").isPresent());
    p.put("client_assertion", assertion(CLIENT_KEY, fake.asUrl().toString()));
    HttpResponse<String> second = client.postAs(par(), p);
    assertEquals(201, second.statusCode(), second.body());
    assertTrue(json(second).get("request_uri").asText().startsWith("urn:ietf:params:oauth:request_uri:"));
    assertEquals(1, fake.as().parCount());
  }

  @Test
  void parWithoutDpopIsRejected() {
    HttpResponse<String> r = client.send(java.net.http.HttpRequest.newBuilder(par())
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(TestClient.form(devPar("v".repeat(50), "atproto"))))
        .build());
    assertEquals(400, r.statusCode());
    assertEquals("invalid_dpop_proof", json(r).get("error").asText());
  }

  @Test
  void rejectsBadClientAssertion() {
    HttpResponse<String> wrongKey = client.postAsWithNonce(par(), confidentialPar("v".repeat(50), TestClient.ecKey()));
    assertEquals(401, wrongKey.statusCode(), wrongKey.body());
    assertEquals("invalid_client", json(wrongKey).get("error").asText());

    Map<String, String> wrongAud = confidentialPar("v".repeat(50), CLIENT_KEY);
    wrongAud.put("client_assertion", assertion(CLIENT_KEY, "http://elsewhere.example"));
    HttpResponse<String> r2 = client.postAsWithNonce(par(), wrongAud);
    assertEquals("invalid_client", json(r2).get("error").asText());

    Map<String, String> missing = confidentialPar("v".repeat(50), CLIENT_KEY);
    missing.remove("client_assertion");
    missing.remove("client_assertion_type");
    assertEquals("invalid_client", json(client.postAsWithNonce(par(), missing)).get("error").asText());

    Map<String, String> replayed = confidentialPar("v".repeat(50), CLIENT_KEY);
    assertEquals(201, client.postAsWithNonce(par(), replayed).statusCode());
    assertEquals("invalid_client", json(client.postAs(par(), replayed)).get("error").asText());
    assertEquals(1, fake.as().parCount());
  }

  @Test
  void parValidatesPkceRedirectAndScope() {
    Map<String, String> plain = devPar("v".repeat(50), "atproto");
    plain.put("code_challenge_method", "plain");
    assertEquals("invalid_request", json(client.postAsWithNonce(par(), plain)).get("error").asText());

    Map<String, String> redirect = devPar("v".repeat(50), "atproto");
    redirect.put("redirect_uri", "http://127.0.0.1:1/other");
    assertEquals("invalid_request", json(client.postAs(par(), redirect)).get("error").asText());

    Map<String, String> noAtproto = devPar("v".repeat(50), "transition:generic");
    assertEquals("invalid_scope", json(client.postAs(par(), noAtproto)).get("error").asText());

    Map<String, String> include = devPar("v".repeat(50), "atproto include:site.standard.authFull");
    assertEquals("invalid_scope", json(client.postAs(par(), include)).get("error").asText());

    Map<String, String> notInMetadata = confidentialPar("v".repeat(50), CLIENT_KEY);
    notInMetadata.put("scope", "atproto transition:generic");
    assertEquals("invalid_scope", json(client.postAs(par(), notInMetadata)).get("error").asText());

    fake.as().rejectScopesStartingWith("repo:");
    Map<String, String> knob = devPar("v".repeat(50), "atproto repo:site.standard.document");
    assertEquals("invalid_scope", json(client.postAs(par(), knob)).get("error").asText());
  }

  @Test
  void confidentialFlowIssuesBoundTokens() {
    String verifier = TestClient.random();
    HttpResponse<String> p = client.postAsWithNonce(par(), confidentialPar(verifier, CLIENT_KEY));
    assertEquals(201, p.statusCode(), p.body());
    String requestUri = json(p).get("request_uri").asText();
    HttpResponse<String> auth = client.get(fake.asUrl().resolve("/oauth/authorize?client_id=" + TestClient.enc(clientId)
        + "&request_uri=" + TestClient.enc(requestUri)));
    assertEquals(302, auth.statusCode());
    URI location = URI.create(auth.headers().firstValue("Location").orElseThrow());
    assertTrue(location.toString().startsWith(appBase + "/callback?"));
    Map<String, String> cb = TestClient.query(location);
    assertEquals("st-1", cb.get("state"));
    assertEquals(fake.asUrl().toString(), cb.get("iss"));

    // the request_uri is single use
    assertEquals(400, client.get(fake.asUrl().resolve("/oauth/authorize?client_id=" + TestClient.enc(clientId)
        + "&request_uri=" + TestClient.enc(requestUri))).statusCode());

    Map<String, String> t = tokenParams(cb.get("code"), "wrong-verifier-" + "x".repeat(40));
    assertEquals("invalid_grant", json(client.postAsWithNonce(token(), t)).get("error").asText());

    // a failed exchange burns the code (as real servers do), so start over
    verifier = TestClient.random();
    String code = authorize(confidentialPar(verifier, CLIENT_KEY));
    Map<String, String> noAssertion = tokenParams(code, verifier);
    noAssertion.remove("client_assertion");
    noAssertion.remove("client_assertion_type");
    assertEquals("invalid_client", json(client.postAs(token(), noAssertion)).get("error").asText());

    HttpResponse<String> ok = client.postAs(token(), tokenParams(code, verifier));
    assertEquals(200, ok.statusCode(), ok.body());
    JsonNode body = json(ok);
    assertEquals("DPoP", body.get("token_type").asText());
    assertEquals(alice.did().value(), body.get("sub").asText());
    assertEquals(SCOPE, body.get("scope").asText());
    assertNotNull(body.get("refresh_token"));
    assertTrue(body.get("expires_in").asInt() > 0);
    JsonNode at = TestClient.claims(body.get("access_token").asText());
    assertEquals(client.jkt(), at.get("cnf").get("jkt").asText());
    assertEquals(alice.did().value(), at.get("sub").asText());
    assertEquals(1, fake.as().tokenCount());

    // the code is single use
    assertEquals("invalid_grant", json(client.postAs(token(), tokenParams(code, verifier))).get("error").asText());
  }

  String authorize(Map<String, String> parParams) {
    HttpResponse<String> p = client.postAsWithNonce(par(), parParams);
    assertEquals(201, p.statusCode(), p.body());
    HttpResponse<String> auth = client.get(fake.asUrl().resolve("/oauth/authorize?client_id="
        + TestClient.enc(parParams.get("client_id")) + "&request_uri=" + TestClient.enc(json(p).get("request_uri").asText())));
    assertEquals(302, auth.statusCode());
    return TestClient.query(URI.create(auth.headers().firstValue("Location").orElseThrow())).get("code");
  }

  Map<String, String> tokenParams(String code, String verifier) {
    Map<String, String> t = new LinkedHashMap<>();
    t.put("grant_type", "authorization_code");
    t.put("code", code);
    t.put("code_verifier", verifier);
    t.put("redirect_uri", appBase + "/callback");
    t.put("client_id", clientId);
    t.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
    t.put("client_assertion", assertion(CLIENT_KEY, fake.asUrl().toString()));
    return t;
  }

  @Test
  void tokenRequiresTheParDpopKey() {
    String verifier = TestClient.random();
    String code = authorize(confidentialPar(verifier, CLIENT_KEY));
    TestClient other = new TestClient();
    other.asNonce = client.asNonce;
    assertEquals("invalid_grant", json(other.postAsWithNonce(token(), tokenParams(code, verifier))).get("error").asText());
  }

  @Test
  void refreshTokenSingleUse() {
    TestClient.Session s = client.login(fake, alice, "atproto transition:generic");
    String clientId = TestClient.devClientId(TestClient.REDIRECT, "atproto transition:generic");
    Map<String, String> refresh = Map.of("grant_type", "refresh_token", "refresh_token", s.refreshToken(),
        "client_id", clientId);
    HttpResponse<String> first = client.postAsWithNonce(token(), refresh);
    assertEquals(200, first.statusCode(), first.body());
    JsonNode body = json(first);
    assertFalse(body.get("refresh_token").asText().equals(s.refreshToken()));
    assertEquals(alice.did().value(), body.get("sub").asText());
    HttpResponse<String> again = client.postAsWithNonce(token(), refresh);
    assertEquals(400, again.statusCode());
    assertEquals("invalid_grant", json(again).get("error").asText());
    assertEquals(1, fake.as().refreshCount());
    assertEquals(1, fake.as().tokenCount());

    // another DPoP key cannot use the new refresh token
    TestClient other = new TestClient();
    Map<String, String> stolen = Map.of("grant_type", "refresh_token", "refresh_token",
        body.get("refresh_token").asText(), "client_id", clientId);
    assertEquals("invalid_grant", json(other.postAsWithNonce(token(), stolen)).get("error").asText());
  }

  @Test
  void revokedRefreshTokenIsRejected() {
    TestClient.Session s = client.login(fake, alice, "atproto");
    String clientId = TestClient.devClientId(TestClient.REDIRECT, "atproto");
    HttpResponse<String> rev = client.postAsWithNonce(fake.asUrl().resolve("/oauth/revoke"),
        Map.of("token", s.refreshToken(), "client_id", clientId));
    assertEquals(200, rev.statusCode(), rev.body());
    assertEquals(1, fake.as().revokeCount());
    HttpResponse<String> r = client.postAsWithNonce(token(), Map.of("grant_type", "refresh_token",
        "refresh_token", s.refreshToken(), "client_id", clientId));
    assertEquals("invalid_grant", json(r).get("error").asText());
  }

  @Test
  void knobsShapeTheResponses() {
    Did mallory = Did.parse("did:plc:aaaaaaaaaaaaaaaaaaaaaaaa");
    fake.as().wrongSub(mallory);
    fake.as().dropAtprotoScope();
    TestClient.Session s = client.login(fake, alice, "atproto transition:generic");
    assertEquals(mallory.value(), s.tokenResponse().get("sub").asText());
    assertEquals("transition:generic", s.tokenResponse().get("scope").asText());

    fake.clear();
    fake.as().wrongIss();
    fake.as().denyNext();
    TestClient c = new TestClient();
    HttpResponse<String> p = c.postAsWithNonce(par(), devPar("v".repeat(50), "atproto"));
    HttpResponse<String> auth = c.get(fake.asUrl().resolve("/oauth/authorize?client_id="
        + TestClient.enc(TestClient.devClientId(TestClient.REDIRECT, "atproto")) + "&request_uri="
        + TestClient.enc(json(p).get("request_uri").asText())));
    Map<String, String> cb = TestClient.query(URI.create(auth.headers().firstValue("Location").orElseThrow()));
    assertEquals("access_denied", cb.get("error"));
    assertEquals("st-2", cb.get("state"));
    assertFalse(fake.asUrl().toString().equals(cb.get("iss")));
  }

  @Test
  void nonceRotation() {
    fake.as().rotateNonceEvery(1);
    client.login(fake, alice, "atproto");
    String stale = client.asNonce;
    // every successful DPoP request rotated the nonce; the one returned last is current
    HttpResponse<String> r = client.postAs(par(), devPar("v".repeat(50), "atproto"));
    assertEquals(201, r.statusCode(), r.body());
    TestClient other = new TestClient();
    other.asNonce = stale;
    HttpResponse<String> staleUse = other.postAs(par(), devPar("v".repeat(50), "atproto"));
    assertEquals("use_dpop_nonce", json(staleUse).get("error").asText());
  }
}
