package org.sitenetsoft.quarkus.atproto.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;

import org.sitenetsoft.multikey.Algorithm;
import org.sitenetsoft.multikey.Signatures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A compact JWS (ES256 only) as the fakes read and write it. */
record Jwt(ObjectNode header, ObjectNode claims, byte[] signingInput, byte[] signature) {

  static final ObjectMapper MAPPER = new ObjectMapper();
  static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  /** Thrown for anything that is not a well-formed compact JWS with JSON-object header and payload. */
  static final class MalformedJwtException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    MalformedJwtException(String message) {
      super(message);
    }
  }

  static Jwt parse(String compact) {
    if (compact == null) {
      throw new MalformedJwtException("missing");
    }
    String[] parts = compact.split("\\.", -1);
    if (parts.length != 3) {
      throw new MalformedJwtException("not a compact JWS");
    }
    try {
      Base64.Decoder d = Base64.getUrlDecoder();
      JsonNode header = MAPPER.readTree(d.decode(parts[0]));
      JsonNode claims = MAPPER.readTree(d.decode(parts[1]));
      if (!(header instanceof ObjectNode h) || !(claims instanceof ObjectNode c)) {
        throw new MalformedJwtException("header or payload is not a JSON object");
      }
      return new Jwt(h, c, (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII), d.decode(parts[2]));
    } catch (IOException | IllegalArgumentException e) {
      throw new MalformedJwtException("undecodable: " + e.getMessage());
    }
  }

  static String sign(Map<String, Object> header, Map<String, Object> claims, PrivateKey key) {
    try {
      String input = B64.encodeToString(MAPPER.writeValueAsBytes(header)) + "."
          + B64.encodeToString(MAPPER.writeValueAsBytes(claims));
      byte[] sig = Signatures.sign(Algorithm.ES256, key, input.getBytes(StandardCharsets.US_ASCII));
      return input + "." + B64.encodeToString(sig);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** ES256 verification; high-S is accepted, as the reference (jose-based) servers do. */
  boolean verifiesWith(PublicKey key) {
    try {
      return Signatures.verifyAllowHighS(Algorithm.ES256, key, signingInput, signature);
    } catch (RuntimeException e) {
      return false;
    }
  }

  String text(String claim) {
    JsonNode n = claims.get(claim);
    return n != null && n.isTextual() ? n.asText() : null;
  }

  /** A numeric claim in seconds, or {@code null}. */
  Long seconds(String claim) {
    JsonNode n = claims.get(claim);
    return n != null && n.isNumber() ? n.asLong() : null;
  }

  static String sha256(String s) {
    try {
      return B64.encodeToString(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
