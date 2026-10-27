package org.sitenetsoft.quarkus.atproto.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sitenetsoft.quarkus.atproto.test.TestClient.json;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sitenetsoft.atproto.syntax.AtUri;
import org.sitenetsoft.atproto.syntax.Tid;

import com.fasterxml.jackson.databind.JsonNode;

class FakePdsTest {

  static FakeAtproto fake;
  static FakeAccount alice;
  TestClient client;

  static final String DOC = "{\"$type\":\"site.standard.document\",\"title\":\"Hello\"}";

  @BeforeAll
  static void startAll() {
    fake = FakeAtproto.start();
    alice = fake.account("alice.test");
  }

  @AfterAll
  static void stopAll() {
    fake.close();
  }

  @BeforeEach
  void reset() {
    fake.clear();
    client = new TestClient();
  }

  URI xrpc(String nsid) {
    return fake.pdsUrl().resolve("/xrpc/" + nsid);
  }

  String create(String rkey) {
    return "{\"repo\":\"" + alice.did().value() + "\",\"collection\":\"site.standard.document\""
        + (rkey == null ? "" : ",\"rkey\":\"" + rkey + "\"") + ",\"record\":" + DOC + "}";
  }

  @Test
  void protectedResourceMetadataPointsAtTheAs() {
    JsonNode md = json(client.get(fake.pdsUrl().resolve("/.well-known/oauth-protected-resource")));
    assertEquals(fake.pdsUrl().toString(), md.get("resource").asText());
    assertEquals(1, md.get("authorization_servers").size());
    assertEquals(fake.asUrl().toString(), md.get("authorization_servers").get(0).asText());
  }

  @Test
  void plcAndWellKnownServeTheAccount() throws IOException {
    JsonNode doc = json(client.get(fake.plcUrl().resolve("/" + alice.did().value())));
    assertEquals(alice.did().value(), doc.get("id").asText());
    assertEquals("at://alice.test", doc.get("alsoKnownAs").get(0).asText());
    assertEquals(alice.did().value() + "#atproto", doc.get("verificationMethod").get(0).get("id").asText());
    assertEquals("Multikey", doc.get("verificationMethod").get(0).get("type").asText());
    assertTrue(doc.get("verificationMethod").get(0).get("publicKeyMultibase").asText().startsWith("z"));
    JsonNode service = doc.get("service").get(0);
    assertEquals("#atproto_pds", service.get("id").asText());
    assertEquals("AtprotoPersonalDataServer", service.get("type").asText());
    assertEquals(fake.pdsUrl().toString(), service.get("serviceEndpoint").asText());
    assertTrue(alice.did().value().matches("did:plc:[a-z2-7]{24}"));
    assertEquals(404, client.get(fake.plcUrl().resolve("/did:plc:zzzzzzzzzzzzzzzzzzzzzzzz")).statusCode());

    HttpResponse<String> byPath = client.get(URI.create(fake.wellKnownBase("alice.test") + "/.well-known/atproto-did"));
    assertEquals(alice.did().value(), byPath.body().strip());
    // the JDK client refuses to set Host, so speak HTTP/1.1 by hand
    try (Socket socket = new Socket("127.0.0.1", fake.pdsUrl().getPort())) {
      socket.getOutputStream().write(("GET /.well-known/atproto-did HTTP/1.1\r\nHost: alice.test\r\n"
          + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      String raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(raw.startsWith("HTTP/1.1 200"), raw);
      assertTrue(raw.strip().endsWith(alice.did().value()), raw);
    }
    assertEquals(404, client.get(URI.create(fake.wellKnownBase("nobody.test") + "/.well-known/atproto-did")).statusCode());
  }

  @Test
  void requiresDpopNonceAndToken() {
    TestClient.Session s = client.login(fake, alice, "atproto transition:generic");
    client.pdsNonce = null;
    HttpResponse<String> first = client.xrpcPost(xrpc("com.atproto.repo.createRecord"), s.accessToken(), create(null),
        "application/json", client.proof("POST", xrpc("com.atproto.repo.createRecord"), null, s.accessToken()));
    assertEquals(401, first.statusCode());
    assertTrue(first.headers().firstValue("WWW-Authenticate").orElse("").contains("use_dpop_nonce"));
    assertTrue(first.headers().firstValue("DPoP-Nonce").isPresent());
    HttpResponse<String> anonymous = client.xrpcPost(xrpc("com.atproto.repo.createRecord"), null, create(null),
        "application/json", null);
    assertEquals(401, anonymous.statusCode());
    assertEquals(200, client.xrpc(s, xrpc("com.atproto.repo.createRecord"), create(null)).statusCode());
  }

  @Test
  void rejectsProofWithWrongAth() {
    TestClient.Session s = client.login(fake, alice, "atproto transition:generic");
    client.xrpc(s, xrpc("com.atproto.repo.createRecord"), create(null)); // learn the nonce
    URI url = xrpc("com.atproto.repo.createRecord");
    HttpResponse<String> r = client.xrpcPost(url, s.accessToken(), create(null), "application/json",
        client.proof("POST", url, client.pdsNonce, TestClient.sha256("another-token"), UUID.randomUUID().toString()));
    assertEquals(401, r.statusCode(), r.body());
    assertTrue(r.headers().firstValue("WWW-Authenticate").orElse("").contains("invalid_dpop_proof"));

    HttpResponse<String> wrongHtm = client.xrpcPost(url, s.accessToken(), create(null), "application/json",
        client.proof("GET", url, client.pdsNonce, s.accessToken()));
    assertEquals(401, wrongHtm.statusCode());
    HttpResponse<String> wrongHtu = client.xrpcPost(url, s.accessToken(), create(null), "application/json",
        client.proof("POST", xrpc("com.atproto.repo.putRecord"), client.pdsNonce, s.accessToken()));
    assertEquals(401, wrongHtu.statusCode());
    TestClient thief = new TestClient();
    HttpResponse<String> otherKey = thief.xrpcPost(url, s.accessToken(), create(null), "application/json",
        thief.proof("POST", url, client.pdsNonce, s.accessToken()));
    assertEquals(401, otherKey.statusCode());
    assertTrue(otherKey.body().contains("invalid_token"), otherKey.body());
  }

  @Test
  void rejectsReplayedJti() {
    TestClient.Session s = client.login(fake, alice, "atproto transition:generic");
    client.xrpc(s, xrpc("com.atproto.repo.createRecord"), create(null));
    URI url = xrpc("com.atproto.repo.createRecord");
    String proof = client.proof("POST", url, client.pdsNonce, s.accessToken());
    assertEquals(200, client.xrpcPost(url, s.accessToken(), create(null), "application/json", proof).statusCode());
    HttpResponse<String> replay = client.xrpcPost(url, s.accessToken(), create(null), "application/json", proof);
    assertEquals(401, replay.statusCode());
    assertTrue(replay.headers().firstValue("WWW-Authenticate").orElse("").contains("invalid_dpop_proof"));
  }

  @Test
  void scopeEnforced() {
    TestClient.Session s = client.login(fake, alice, "atproto repo:site.standard.document?action=create");
    assertEquals(200, client.xrpc(s, xrpc("com.atproto.repo.createRecord"), create("one")).statusCode());
    String put = "{\"repo\":\"" + alice.did().value() + "\",\"collection\":\"site.standard.document\",\"rkey\":\"one\","
        + "\"record\":" + DOC + "}";
    HttpResponse<String> r = client.xrpc(s, xrpc("com.atproto.repo.putRecord"), put);
    assertEquals(403, r.statusCode(), r.body());
    String del = "{\"repo\":\"" + alice.did().value() + "\",\"collection\":\"site.standard.document\",\"rkey\":\"one\"}";
    assertEquals(403, client.xrpc(s, xrpc("com.atproto.repo.deleteRecord"), del).statusCode());
    String other = "{\"repo\":\"" + alice.did().value() + "\",\"collection\":\"site.standard.publication\","
        + "\"record\":{\"$type\":\"site.standard.publication\"}}";
    assertEquals(403, client.xrpc(s, xrpc("com.atproto.repo.createRecord"), other).statusCode());
    assertEquals(403, client.xrpc(s, xrpc("com.atproto.repo.uploadBlob"), "png", "image/png").statusCode());
  }

  @Test
  void storesRecordUnderTid() {
    TestClient.Session s = client.login(fake, alice, "atproto transition:generic");
    HttpResponse<String> r = client.xrpc(s, xrpc("com.atproto.repo.createRecord"), create(null));
    assertEquals(200, r.statusCode(), r.body());
    JsonNode out = json(r);
    AtUri uri = AtUri.parse(out.get("uri").asText());
    assertEquals(alice.did().value(), uri.authority().toString());
    assertTrue(Tid.isValid(uri.rkey().orElseThrow().value()));
    assertTrue(out.get("cid").asText().startsWith("bafyrei"));
    assertEquals("Hello", fake.pds().records().get(uri).get("title").asText());

    HttpResponse<String> got = client.get(URI.create(xrpc("com.atproto.repo.getRecord") + "?repo="
        + alice.did().value() + "&collection=site.standard.document&rkey=" + uri.rkey().orElseThrow().value()));
    assertEquals(200, got.statusCode());
    assertEquals(out.get("cid").asText(), json(got).get("cid").asText());
    assertEquals("Hello", json(got).get("value").get("title").asText());

    String put = "{\"repo\":\"" + alice.did().value() + "\",\"collection\":\"site.standard.document\",\"rkey\":\""
        + uri.rkey().orElseThrow().value() + "\",\"swapRecord\":\"bafyreiaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
        + "\"record\":{\"$type\":\"site.standard.document\",\"title\":\"Changed\"}}";
    assertEquals("InvalidSwap", json(client.xrpc(s, xrpc("com.atproto.repo.putRecord"), put)).get("error").asText());
    HttpResponse<String> ok = client.xrpc(s, xrpc("com.atproto.repo.putRecord"),
        put.replace("bafyreiaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", out.get("cid").asText()));
    assertEquals(200, ok.statusCode(), ok.body());
    assertEquals("Changed", fake.pds().records().get(uri).get("title").asText());

    String del = "{\"repo\":\"" + alice.did().value() + "\",\"collection\":\"site.standard.document\",\"rkey\":\""
        + uri.rkey().orElseThrow().value() + "\"}";
    assertEquals(200, client.xrpc(s, xrpc("com.atproto.repo.deleteRecord"), del).statusCode());
    assertFalse(fake.pds().records().containsKey(uri));

    HttpResponse<String> blob = client.xrpc(s, xrpc("com.atproto.repo.uploadBlob"), "pngbytes", "image/png");
    assertEquals(200, blob.statusCode(), blob.body());
    JsonNode b = json(blob).get("blob");
    assertEquals("blob", b.get("$type").asText());
    assertEquals("image/png", b.get("mimeType").asText());
    assertEquals(8, b.get("size").asInt());
    assertTrue(b.get("ref").get("$link").asText().startsWith("bafkrei"));

    JsonNode repo = json(client.get(URI.create(xrpc("com.atproto.repo.describeRepo") + "?repo=alice.test")));
    assertEquals(alice.did().value(), repo.get("did").asText());

    assertTrue(fake.pds().requests().stream().anyMatch(q -> q.path().endsWith("createRecord") && q.status() == 200));
  }

  @Test
  void blobScopeEnforcedEndToEnd() {
    TestClient.Session s = client.login(fake, alice, "atproto blob:image/*");
    HttpResponse<String> png = client.xrpc(s, xrpc("com.atproto.repo.uploadBlob"), "png", "image/png");
    assertEquals(200, png.statusCode(), png.body());
    HttpResponse<String> text = client.xrpc(s, xrpc("com.atproto.repo.uploadBlob"), "hello", "text/plain");
    assertEquals(403, text.statusCode(), text.body());
    assertEquals("InsufficientScope", json(text).get("error").asText());
    assertEquals(1, fake.pds().blobs().size());
  }

  @Test
  void refreshTokenReplayRevokesTheSessionsAccessTokens() {
    TestClient.Session s = client.login(fake, alice, "atproto transition:generic");
    String clientId = TestClient.devClientId(TestClient.REDIRECT, "atproto transition:generic");
    java.util.Map<String, String> refresh = java.util.Map.of("grant_type", "refresh_token",
        "refresh_token", s.refreshToken(), "client_id", clientId);
    JsonNode refreshed = json(client.postAsWithNonce(fake.asUrl().resolve("/oauth/token"), refresh));
    TestClient.Session s2 = new TestClient.Session(refreshed.get("access_token").asText(),
        refreshed.get("refresh_token").asText(), refreshed);
    URI url = xrpc("com.atproto.repo.createRecord");
    assertEquals(200, client.xrpc(s2, url, create(null)).statusCode());

    HttpResponse<String> replay = client.postAsWithNonce(fake.asUrl().resolve("/oauth/token"), refresh);
    assertEquals("invalid_grant", json(replay).get("error").asText());

    HttpResponse<String> after = client.xrpc(s2, url, create(null));
    assertEquals(401, after.statusCode(), after.body());
    assertTrue(after.headers().firstValue("WWW-Authenticate").orElse("").contains("invalid_token"));
    assertTrue(after.body().contains("revoked"), after.body());
  }

  @Test
  void otherRepoIsForbidden() {
    FakeAccount bob = fake.account("bob.test");
    TestClient.Session s = client.login(fake, alice, "atproto transition:generic");
    String body = create(null).replace(alice.did().value(), bob.did().value());
    HttpResponse<String> r = client.xrpc(s, xrpc("com.atproto.repo.createRecord"), body);
    assertEquals(403, r.statusCode(), r.body());
  }

  @Test
  void knobsExpireTokensAndRotateNonce() {
    TestClient.Session s = client.login(fake, alice, "atproto transition:generic");
    assertEquals(200, client.xrpc(s, xrpc("com.atproto.repo.createRecord"), create(null)).statusCode());
    String before = client.pdsNonce;
    fake.pds().rotateNonceNow();
    URI url = xrpc("com.atproto.repo.createRecord");
    HttpResponse<String> stale = client.xrpcPost(url, s.accessToken(), create(null), "application/json",
        client.proof("POST", url, before, s.accessToken()));
    assertTrue(stale.headers().firstValue("WWW-Authenticate").orElse("").contains("use_dpop_nonce"));
    assertFalse(before.equals(client.pdsNonce));

    fake.pds().expireTokens();
    HttpResponse<String> expired = client.xrpc(s, url, create(null));
    assertEquals(401, expired.statusCode());
    assertTrue(expired.headers().firstValue("WWW-Authenticate").orElse("").contains("invalid_token"));

    // a refreshed token works again
    String clientId = TestClient.devClientId(TestClient.REDIRECT, "atproto transition:generic");
    JsonNode refreshed = json(client.postAsWithNonce(fake.asUrl().resolve("/oauth/token"),
        java.util.Map.of("grant_type", "refresh_token", "refresh_token", s.refreshToken(), "client_id", clientId)));
    TestClient.Session s2 = new TestClient.Session(refreshed.get("access_token").asText(),
        refreshed.get("refresh_token").asText(), refreshed);
    assertEquals(200, client.xrpc(s2, url, create(null)).statusCode());
  }

  @Test
  void reservedPortsAreStable() {
    int[] a = FakeAtproto.reservePorts("fake.atproto.test.ports");
    int[] b = FakeAtproto.reservePorts("fake.atproto.test.ports");
    assertEquals(3, a.length);
    assertEquals(a[0], b[0]);
    assertEquals(a[2], b[2]);
    try (FakeAtproto one = FakeAtproto.start(a[0], a[1], a[2]);
        FakeAtproto two = FakeAtproto.start(a[0], a[1], a[2])) {
      // the second start on the same ports takes over (QuarkusUnitTest loads test classes twice)
      assertEquals(a[1], two.asUrl().getPort());
      two.account("carol.test");
      assertEquals(200, new TestClient().get(two.plcUrl().resolve("/" + two.account("carol.test").did())).statusCode());
      assertEquals(one.account("carol.test").did(), two.account("carol.test").did());
    }
  }
}
