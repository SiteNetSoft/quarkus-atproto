package org.sitenetsoft.quarkus.atproto.oauth.jose;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.sitenetsoft.multikey.Algorithm;
import org.sitenetsoft.multikey.MultikeyException;
import org.sitenetsoft.multikey.Signatures;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Compact JWS (RFC 7515) with ES256 only: base64url without padding, the signature in IEEE P1363 form (64 bytes,
 * {@code r || s}). Built on the JDK through {@code multikey}; no JOSE library.
 */
public final class Jws {

    /** The only algorithm this client signs or verifies with. */
    public static final String ES256 = "ES256";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private Jws() {
    }

    /**
     * Signs {@code claims} with {@code key} (P-256). {@code alg} is set to {@code ES256}; a header asking for another
     * algorithm is refused.
     */
    public static String sign(Map<String, Object> header, Map<String, Object> claims, PrivateKey key) {
        Object alg = header.get("alg");
        if (alg != null && !ES256.equals(alg)) {
            throw new IllegalArgumentException("Only ES256 is supported, not " + alg);
        }
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("alg", ES256);
        h.putAll(header);
        String signingInput = encode(h) + "." + encode(claims);
        byte[] sig;
        try {
            sig = Signatures.sign(Algorithm.ES256, key, signingInput.getBytes(StandardCharsets.US_ASCII));
        } catch (MultikeyException e) {
            throw new IllegalArgumentException("Cannot sign with this key: " + e.getMessage());
        }
        return signingInput + "." + B64.encodeToString(sig);
    }

    /**
     * Header ({@code [0]}) and claims ({@code [1]}) of a compact JWS, <strong>without checking the signature</strong>.
     *
     * @throws IllegalArgumentException when {@code jws} is not three base64url parts with JSON-object header and claims
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object>[] parseUnverified(String jws) {
        String[] parts = split(jws);
        return new Map[] {decode(parts[0], "header"), decode(parts[1], "claims")};
    }

    /**
     * Whether {@code jws} is an ES256 compact JWS whose signature verifies with {@code key}. High-S signatures are
     * accepted (low-S is an AT Protocol repository rule, not a JOSE one). Malformed input is {@code false}.
     */
    public static boolean verify(String jws, PublicKey key) {
        try {
            String[] parts = split(jws);
            if (!ES256.equals(decode(parts[0], "header").get("alg"))) {
                return false;
            }
            byte[] sig = B64D.decode(parts[2]);
            byte[] input = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
            return Signatures.verifyAllowHighS(Algorithm.ES256, key, input, sig);
        } catch (IllegalArgumentException | MultikeyException e) {
            return false;
        }
    }

    /** base64url without padding. */
    static String b64(byte[] bytes) {
        return B64.encodeToString(bytes);
    }

    private static String[] split(String jws) {
        if (jws == null) {
            throw new IllegalArgumentException("JWS is null");
        }
        String[] parts = jws.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new IllegalArgumentException("Not a compact JWS (three non-empty parts)");
        }
        return parts;
    }

    private static String encode(Map<String, Object> json) {
        try {
            return B64.encodeToString(JSON.writeValueAsBytes(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise JWS member: " + e.getOriginalMessage());
        }
    }

    private static Map<String, Object> decode(String part, String what) {
        try {
            return JSON.readValue(B64D.decode(part), MAP);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("JWS " + what + " is not base64url JSON object");
        }
    }
}
