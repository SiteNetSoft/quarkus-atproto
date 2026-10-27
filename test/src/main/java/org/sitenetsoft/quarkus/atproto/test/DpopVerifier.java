package org.sitenetsoft.quarkus.atproto.test;

import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.sitenetsoft.multikey.Jwk;
import org.sitenetsoft.multikey.JwkThumbprint;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Verifies DPoP proofs (RFC 9449) the way the AT Protocol authorization server and PDS do, and owns one server's
 * {@code DPoP-Nonce}. A proof must be a compact JWS with {@code typ: dpop+jwt}, {@code alg: ES256} and a public
 * P-256 {@code jwk} (no private members) that verifies its signature; claims {@code jti} (never seen before),
 * {@code htm} (exactly the request method, case-sensitive), {@code htu} (the request URL without query and fragment), {@code iat} (within 60
 * seconds of now), {@code ath} (base64url SHA-256 of the access token, required exactly when one is presented) and
 * {@code nonce} (this server's current nonce). Thread-safe.
 */
public final class DpopVerifier {

  /** The maximum distance between a proof's {@code iat} and now. */
  public static final Duration IAT_WINDOW = Duration.ofSeconds(60);

  /** A rejected proof; {@link #error()} is {@code use_dpop_nonce} or {@code invalid_dpop_proof}. */
  public static final class DpopException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String error;

    DpopException(String error, String message) {
      super(message);
      this.error = error;
    }

    public String error() {
      return error;
    }

    public boolean isNonceError() {
      return error.equals("use_dpop_nonce");
    }
  }

  /** A verified proof: the JWK thumbprint ({@code jkt}) of its key and its {@code jti}. */
  public record Proof(String jkt, String jti, Map<String, Object> jwk) {
  }

  private static final SecureRandom RANDOM = new SecureRandom();

  private final Clock clock;
  private final Map<String, Long> seenJti = new LinkedHashMap<>();
  private String nonce = newNonce();
  private int rotateEvery;
  private int sinceRotation;

  public DpopVerifier() {
    this(Clock.systemUTC());
  }

  public DpopVerifier(Clock clock) {
    this.clock = clock;
  }

  /** The nonce to send in {@code DPoP-Nonce}. */
  public synchronized String nonce() {
    return nonce;
  }

  /** Replaces the nonce now; proofs carrying the old one get {@code use_dpop_nonce}. */
  public synchronized void rotateNonce() {
    nonce = newNonce();
    sinceRotation = 0;
  }

  /** Rotates the nonce after every {@code n} successfully verified proofs ({@code 0}: never). */
  public synchronized void rotateNonceEvery(int n) {
    if (n < 0) {
      throw new IllegalArgumentException("n must be >= 0");
    }
    rotateEvery = n;
    sinceRotation = 0;
  }

  /** Forgets seen {@code jti}s and stops automatic rotation. */
  public synchronized void reset() {
    seenJti.clear();
    rotateEvery = 0;
    sinceRotation = 0;
  }

  /**
   * Verifies {@code proof} for a request.
   *
   * @param method the HTTP method
   * @param url the request URL; query and fragment are ignored
   * @param accessToken the presented access token, or {@code null} at the authorization server
   */
  public synchronized Proof verify(String proof, String method, String url, String accessToken) {
    if (proof == null || proof.isBlank()) {
      throw invalid("missing DPoP proof");
    }
    Jwt jwt;
    try {
      jwt = Jwt.parse(proof.trim());
    } catch (Jwt.MalformedJwtException e) {
      throw invalid("malformed DPoP proof (" + e.getMessage() + ")");
    }
    if (!"dpop+jwt".equals(text(jwt.header().get("typ")))) {
      throw invalid("typ must be dpop+jwt");
    }
    if (!"ES256".equals(text(jwt.header().get("alg")))) {
      throw invalid("alg must be ES256");
    }
    JsonNode jwkNode = jwt.header().get("jwk");
    if (jwkNode == null || !jwkNode.isObject()) {
      throw invalid("missing jwk header");
    }
    if (jwkNode.has("d")) {
      throw invalid("jwk must not contain private key material");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> jwk = Jwt.MAPPER.convertValue(jwkNode, Map.class);
    PublicKey key;
    try {
      if (!"EC".equals(jwk.get("kty")) || !"P-256".equals(jwk.get("crv"))) {
        throw invalid("jwk must be an EC P-256 key");
      }
      key = Jwk.publicKey(jwk);
    } catch (DpopException e) {
      throw e;
    } catch (RuntimeException e) {
      throw invalid("unusable jwk");
    }
    if (!jwt.verifiesWith(key)) {
      throw invalid("bad signature");
    }
    String jti = jwt.text("jti");
    if (jti == null || jti.isEmpty()) {
      throw invalid("missing jti");
    }
    if (!method.equals(jwt.text("htm"))) {
      throw invalid("htm does not match the request method");
    }
    String htu = jwt.text("htu");
    if (htu == null || !stripQuery(htu).equals(stripQuery(url))) {
      throw invalid("htu does not match the request URL");
    }
    Long iat = jwt.seconds("iat");
    long now = clock.millis() / 1000;
    if (iat == null || Math.abs(now - iat) > IAT_WINDOW.toSeconds()) {
      throw invalid("iat missing or not within " + IAT_WINDOW.toSeconds() + " s");
    }
    String ath = jwt.text("ath");
    if (accessToken != null) {
      if (ath == null || !ath.equals(Jwt.sha256(accessToken))) {
        throw invalid("ath does not match the access token");
      }
    } else if (ath != null) {
      throw invalid("ath without an access token");
    }
    pruneJti(now);
    if (seenJti.containsKey(jti)) {
      throw invalid("jti replayed");
    }
    if (!nonce.equals(jwt.text("nonce"))) {
      throw new DpopException("use_dpop_nonce", "Authorization server requires nonce in DPoP proof");
    }
    seenJti.put(jti, now);
    if (rotateEvery > 0 && ++sinceRotation >= rotateEvery) {
      rotateNonce();
    }
    return new Proof(JwkThumbprint.sha256(jwk), jti, jwk);
  }

  private void pruneJti(long now) {
    for (Iterator<Long> it = seenJti.values().iterator(); it.hasNext();) {
      if (now - it.next() > 10 * IAT_WINDOW.toSeconds()) {
        it.remove();
      } else {
        break;
      }
    }
  }

  private static String stripQuery(String url) {
    int i = url.indexOf('?');
    int j = url.indexOf('#');
    int end = url.length();
    if (i >= 0) {
      end = i;
    }
    if (j >= 0 && j < end) {
      end = j;
    }
    return url.substring(0, end);
  }

  private static String text(JsonNode n) {
    return n != null && n.isTextual() ? n.asText() : null;
  }

  private static DpopException invalid(String message) {
    return new DpopException("invalid_dpop_proof", message);
  }

  private static String newNonce() {
    byte[] b = new byte[16];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }
}
