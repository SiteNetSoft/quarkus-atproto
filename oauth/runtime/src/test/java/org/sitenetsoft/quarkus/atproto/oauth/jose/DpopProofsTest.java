package org.sitenetsoft.quarkus.atproto.oauth.jose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.security.KeyPair;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.multikey.Jwk;
import org.sitenetsoft.multikey.KeyPairs;
import org.sitenetsoft.multikey.KeyType;

class DpopProofsTest {

    private static final KeyPair KEY = KeyPairs.generate(KeyType.P256);
    private static final Instant NOW = Instant.ofEpochSecond(1_761_500_000L);

    @Test
    void athIsSha256OfToken() {
        String proof = DpopProofs.proof(KEY, "GET", URI.create("https://pds.example/xrpc/x"), Optional.of("n0nce"),
                Optional.of("abc"), NOW);
        Map<String, Object> claims = Jws.parseUnverified(proof)[1];
        assertEquals("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0", claims.get("ath"));
        assertEquals("n0nce", claims.get("nonce"));
        assertEquals("GET", claims.get("htm"));
        assertEquals(NOW.getEpochSecond(), ((Number) claims.get("iat")).longValue());
        assertTrue(Jws.verify(proof, KEY.getPublic()));

        Map<String, Object> bare = Jws.parseUnverified(DpopProofs.proof(KEY, "POST",
                URI.create("https://as.example/oauth/par"), Optional.empty(), Optional.empty(), NOW))[1];
        assertFalse(bare.containsKey("ath"));
        assertFalse(bare.containsKey("nonce"));
    }

    @Test
    void htuStripsQuery() {
        String proof = DpopProofs.proof(KEY, "GET",
                URI.create("https://pds.example:8443/xrpc/com.atproto.repo.getRecord?repo=did%3Aplc%3Ax&rkey=1#frag"),
                Optional.empty(), Optional.empty(), NOW);
        assertEquals("https://pds.example:8443/xrpc/com.atproto.repo.getRecord",
                Jws.parseUnverified(proof)[1].get("htu"));
    }

    @Test
    void htuIsNormalisedOrigin() {
        String proof = DpopProofs.proof(KEY, "GET", URI.create("HTTPS://user:pw@PDS.Example:443/xrpc/A%20b?x=1"),
                Optional.empty(), Optional.empty(), NOW);
        assertEquals("https://pds.example/xrpc/A%20b", Jws.parseUnverified(proof)[1].get("htu"));
        assertEquals("http://127.0.0.1:8080/x", DpopProofs.htu(URI.create("http://127.0.0.1:8080/x")));
        assertEquals("http://pds.example/", DpopProofs.htu(URI.create("http://pds.example:80/")));
    }

    @Test
    void jtiUnique() {
        Set<Object> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Object jti = Jws.parseUnverified(DpopProofs.proof(KEY, "GET", URI.create("https://a.example/"),
                    Optional.empty(), Optional.empty(), NOW))[1].get("jti");
            assertEquals(22, ((String) jti).length(), "16 random bytes, base64url");
            assertTrue(seen.add(jti));
        }
    }

    @Test
    void jwkHeaderIsPublicOnly() {
        Map<String, Object> header = Jws.parseUnverified(DpopProofs.proof(KEY, "GET", URI.create("https://a.example/"),
                Optional.empty(), Optional.empty(), NOW))[0];
        assertEquals("dpop+jwt", header.get("typ"));
        assertEquals("ES256", header.get("alg"));
        @SuppressWarnings("unchecked")
        Map<String, Object> jwk = (Map<String, Object>) header.get("jwk");
        assertEquals(Set.of("kty", "crv", "x", "y"), jwk.keySet());
        assertEquals(KEY.getPublic(), Jwk.publicKey(jwk));
        assertNotEquals(null, jwk.get("x"));
    }
}
