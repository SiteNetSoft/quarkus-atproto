package org.sitenetsoft.quarkus.atproto.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sitenetsoft.multikey.Jwk;
import org.sitenetsoft.multikey.JwkThumbprint;

/** Each test changes exactly one field of an otherwise valid proof and expects that field's check to reject it. */
class DpopVerifierTest {

  static final String URL = "http://127.0.0.1:1234/oauth/par";
  final KeyPair key = TestClient.ecKey();
  DpopVerifier verifier;
  Map<String, Object> header;
  Map<String, Object> claims;
  KeyPair signer;

  @BeforeEach
  void validProof() {
    verifier = new DpopVerifier();
    header = new LinkedHashMap<>();
    header.put("typ", "dpop+jwt");
    header.put("alg", "ES256");
    header.put("jwk", Jwk.of(key.getPublic()));
    claims = new LinkedHashMap<>();
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("htm", "POST");
    claims.put("htu", URL);
    claims.put("iat", Instant.now().getEpochSecond());
    claims.put("nonce", verifier.nonce());
    signer = key;
  }

  String proof() {
    return TestClient.jwt(header, claims, signer);
  }

  DpopVerifier.DpopException rejected(String accessToken) {
    return assertThrows(DpopVerifier.DpopException.class, () -> verifier.verify(proof(), "POST", URL, accessToken));
  }

  void assertInvalid(String messagePart) {
    DpopVerifier.DpopException e = rejected(null);
    assertEquals("invalid_dpop_proof", e.error());
    assertTrue(e.getMessage().contains(messagePart), e.getMessage());
  }

  @Test
  void acceptsTheBaseline() {
    DpopVerifier.Proof p = verifier.verify(proof(), "POST", URL + "?q=1", null);
    assertEquals(JwkThumbprint.sha256(Jwk.of(key.getPublic())), p.jkt());
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("ath", TestClient.sha256("tok"));
    verifier.verify(proof(), "POST", URL, "tok");
  }

  @Test
  void signatureByAnotherKey() {
    signer = TestClient.ecKey();
    assertInvalid("bad signature");
  }

  @Test
  void wrongTyp() {
    header.put("typ", "jwt");
    assertInvalid("typ");
  }

  @Test
  void algOtherThanEs256() {
    header.put("alg", "ES384");
    assertInvalid("alg");
  }

  @Test
  void iatTooOld() {
    claims.put("iat", Instant.now().getEpochSecond() - 90);
    assertInvalid("iat");
  }

  @Test
  void iatInTheFuture() {
    claims.put("iat", Instant.now().getEpochSecond() + 90);
    assertInvalid("iat");
  }

  @Test
  void athWithoutAccessToken() {
    claims.put("ath", TestClient.sha256("tok"));
    assertInvalid("ath without an access token");
  }

  @Test
  void athForAnotherToken() {
    claims.put("ath", TestClient.sha256("other"));
    DpopVerifier.DpopException e = rejected("tok");
    assertTrue(e.getMessage().contains("ath does not match"), e.getMessage());
  }

  @Test
  void htmIsCaseSensitive() {
    claims.put("htm", "post");
    assertInvalid("htm");
  }

  @Test
  void htuOfAnotherUrl() {
    claims.put("htu", "http://127.0.0.1:1234/oauth/token");
    assertInvalid("htu");
  }

  @Test
  void replayedJti() {
    verifier.verify(proof(), "POST", URL, null);
    assertInvalid("jti replayed");
  }

  @Test
  void staleNonce() {
    verifier.rotateNonce();
    DpopVerifier.DpopException e = rejected(null);
    assertEquals("use_dpop_nonce", e.error());
  }

  @Test
  void privateJwk() {
    header.put("jwk", Jwk.ofPrivate(key.getPrivate(), key.getPublic()));
    assertInvalid("private");
  }
}
