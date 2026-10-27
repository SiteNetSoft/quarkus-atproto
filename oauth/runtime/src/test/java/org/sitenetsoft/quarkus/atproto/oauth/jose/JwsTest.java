package org.sitenetsoft.quarkus.atproto.oauth.jose;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.multikey.KeyPairs;
import org.sitenetsoft.multikey.KeyType;

class JwsTest {

    private static final KeyPair KEY = KeyPairs.generate(KeyType.P256);

    @Test
    void verifiesOwnSignature() {
        String jws = Jws.sign(Map.of("typ", "JWT"), Map.of("sub", "x", "n", 1), KEY.getPrivate());
        assertTrue(Jws.verify(jws, KEY.getPublic()));
        assertFalse(Jws.verify(jws, KeyPairs.generate(KeyType.P256).getPublic()));
        String[] parts = jws.split("\\.");
        // tampered payload
        String forged = parts[0] + "." + b64("{\"sub\":\"y\",\"n\":1}") + "." + parts[2];
        assertFalse(Jws.verify(forged, KEY.getPublic()));
        // P1363 compact signature: 64 bytes
        assertEquals(64, Base64.getUrlDecoder().decode(parts[2]).length);
    }

    @Test
    void headerAndClaimsBase64urlNoPadding() {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        // lengths chosen so standard base64 would need padding and would contain '+' or '/'
        claims.put("s", "??>>~~");
        String jws = Jws.sign(header, claims, KEY.getPrivate());
        String[] parts = jws.split("\\.");
        assertEquals(3, parts.length);
        for (String p : parts) {
            assertFalse(p.contains("="), p);
            assertFalse(p.contains("+"), p);
            assertFalse(p.contains("/"), p);
        }
        Map<String, Object>[] parsed = Jws.parseUnverified(jws);
        assertEquals("ES256", parsed[0].get("alg"));
        assertEquals("JWT", parsed[0].get("typ"));
        assertEquals("??>>~~", parsed[1].get("s"));
        assertArrayEquals(parts[1].getBytes(StandardCharsets.US_ASCII),
                b64(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void rejectsOtherAlgorithmsAndMalformedInput() {
        assertThrows(IllegalArgumentException.class,
                () -> Jws.sign(Map.of("alg", "none"), Map.of(), KEY.getPrivate()));
        // alg is always set by Jws: any caller value is refused, even ES256 or null
        assertThrows(IllegalArgumentException.class,
                () -> Jws.sign(Map.of("alg", "ES256"), Map.of(), KEY.getPrivate()));
        Map<String, Object> nullAlg = new java.util.HashMap<>();
        nullAlg.put("alg", null);
        assertThrows(IllegalArgumentException.class, () -> Jws.sign(nullAlg, Map.of(), KEY.getPrivate()));
        assertThrows(IllegalArgumentException.class, () -> Jws.parseUnverified("a.b"));
        String hs = b64("{\"alg\":\"HS256\"}") + "." + b64("{}") + "." + b64("x");
        assertFalse(Jws.verify(hs, KEY.getPublic()));
        assertFalse(Jws.verify("not a jws", KEY.getPublic()));
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
