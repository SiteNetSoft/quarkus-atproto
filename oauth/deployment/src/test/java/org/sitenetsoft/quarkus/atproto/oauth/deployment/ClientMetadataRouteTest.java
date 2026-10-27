package org.sitenetsoft.quarkus.atproto.oauth.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.multikey.Jwk;
import org.sitenetsoft.multikey.JwkThumbprint;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientIdentity;
import org.sitenetsoft.quarkus.atproto.oauth.jose.ClientAssertions;
import org.sitenetsoft.quarkus.atproto.oauth.jose.Jws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.QuarkusUnitTest;
import io.quarkus.test.common.http.TestHTTPResource;

public class ClientMetadataRouteTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(Http.class)
                    .addAsResource("test-client-key-1.pem")
                    .addAsResource("test-client-key-2.jwk"))
            .overrideConfigKey("quarkus.atproto.oauth.client-base-url", "https://example.org/")
            .overrideConfigKey("quarkus.atproto.oauth.client-name", "Test App")
            .overrideConfigKey("quarkus.atproto.oauth.signing-key.location",
                    "test-client-key-1.pem,test-client-key-2.jwk");

    @TestHTTPResource("/oauth-client-metadata.json")
    URI metadata;

    @TestHTTPResource("/oauth/jwks.json")
    URI jwks;

    @Inject
    ClientIdentity identity;

    @Test
    void confidentialDocument() throws Exception {
        HttpResponse<String> r = Http.get(metadata);
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("content-type").orElse("").startsWith("application/json"));
        JsonNode expected = JSON.readTree("""
                {
                  "client_id": "https://example.org/oauth-client-metadata.json",
                  "client_name": "Test App",
                  "client_uri": "https://example.org",
                  "redirect_uris": ["https://example.org/atproto/oauth/callback"],
                  "grant_types": ["authorization_code", "refresh_token"],
                  "response_types": ["code"],
                  "scope": "atproto",
                  "dpop_bound_access_tokens": true,
                  "application_type": "web",
                  "token_endpoint_auth_method": "private_key_jwt",
                  "token_endpoint_auth_signing_alg": "ES256",
                  "jwks_uri": "https://example.org/oauth/jwks.json"
                }
                """);
        assertEquals(expected, JSON.readTree(r.body()));
        assertTrue(identity.confidential());
        assertEquals("https://example.org/oauth-client-metadata.json", identity.clientId());
        assertEquals(URI.create("https://example.org/atproto/oauth/callback"), identity.redirectUri());
    }

    @Test
    @SuppressWarnings("unchecked")
    void jwksHasNoPrivateMembers() throws Exception {
        HttpResponse<String> r = Http.get(jwks);
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("content-type").orElse("").startsWith("application/json"));
        Map<String, Object> doc = JSON.readValue(r.body(), Map.class);
        java.util.List<Map<String, Object>> keys = (java.util.List<Map<String, Object>>) doc.get("keys");
        assertEquals(2, keys.size(), "every configured key is published");
        for (Map<String, Object> k : keys) {
            assertEquals(Set.of("kty", "crv", "x", "y", "kid", "alg", "use"), k.keySet());
            assertFalse(k.containsKey("d"));
            assertEquals("ES256", k.get("alg"));
            Map<String, Object> pub = new LinkedHashMap<>(k);
            pub.keySet().retainAll(Set.of("kty", "crv", "x", "y"));
            assertEquals(JwkThumbprint.sha256(pub), k.get("kid"), "kid is the RFC 7638 thumbprint");
        }
        // key 1 is a PKCS#8 PEM holding only the private key: its public half was derived correctly
        assertEquals("XduS7qn4J9BUF3pXxL88tCke2zFvnPU3iW2ghAa3iQ8", keys.get(0).get("x"));
        assertEquals("qTQO9L79lTFDfzahEVCkEqlFogreuktUB2BTRNxLzqI", keys.get(0).get("y"));
        assertEquals("xZnw8z7qRYaC6IajQF7TV56eYIQtAk7GlXWYz1W1KnM", keys.get(1).get("x"));

        // the first key signs client assertions, and the published key verifies them
        String assertion = ClientAssertions.assertion(identity.signingKey().orElseThrow(), identity.clientId(),
                "https://as.example", Instant.now());
        Map<String, Object>[] parsed = Jws.parseUnverified(assertion);
        assertEquals(keys.get(0).get("kid"), parsed[0].get("kid"));
        assertEquals(identity.clientId(), parsed[1].get("iss"));
        assertEquals(identity.clientId(), parsed[1].get("sub"));
        assertEquals("https://as.example", parsed[1].get("aud"));
        assertTrue(parsed[1].containsKey("jti"));
        assertTrue(parsed[1].containsKey("iat"));
        assertTrue(Jws.verify(assertion, Jwk.publicKey(keys.get(0))));
        assertFalse(Jws.verify(assertion, Jwk.publicKey(keys.get(1))));
    }
}
