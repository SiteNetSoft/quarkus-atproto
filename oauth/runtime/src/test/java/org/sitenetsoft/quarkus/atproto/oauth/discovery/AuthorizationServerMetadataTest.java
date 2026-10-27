package org.sitenetsoft.quarkus.atproto.oauth.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class AuthorizationServerMetadataTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode doc(String issuer) {
        ObjectNode d = JSON.createObjectNode();
        d.put("issuer", issuer);
        d.put("authorization_endpoint", issuer + "/oauth/authorize");
        d.put("token_endpoint", issuer + "/oauth/token");
        d.put("pushed_authorization_request_endpoint", issuer + "/oauth/par");
        d.put("revocation_endpoint", issuer + "/oauth/revoke");
        d.put("require_pushed_authorization_requests", true);
        d.put("authorization_response_iss_parameter_supported", true);
        d.put("client_id_metadata_document_supported", true);
        d.putArray("dpop_signing_alg_values_supported").add("ES256");
        return d;
    }

    private static String failedCheck(ObjectNode d, String expected, boolean allowHttp) {
        return assertThrows(OAuthDiscoveryException.class,
                () -> AuthorizationServerMetadata.parseAndCheck(d, expected, allowHttp)).check();
    }

    @Test
    void acceptsTheAtprotoProfile() {
        AuthorizationServerMetadata md = AuthorizationServerMetadata.parseAndCheck(doc("https://bsky.social"),
                "https://bsky.social", false);
        assertEquals(URI.create("https://bsky.social/oauth/par"), md.pushedAuthorizationRequestEndpoint());
    }

    @Test
    void httpEndpointsOnlyWhenAllowed() {
        ObjectNode d = doc("https://bsky.social");
        d.put("authorization_endpoint", "http://bsky.social/oauth/authorize");
        assertEquals("authorization_endpoint", failedCheck(d, "https://bsky.social", false));
        // with http allowed it is still another origin than the https issuer
        assertEquals("authorization_endpoint", failedCheck(d, "https://bsky.social", true));
        AuthorizationServerMetadata.parseAndCheck(doc("http://127.0.0.1:9000"), "http://127.0.0.1:9000", true);
        assertEquals("authorization_endpoint",
                failedCheck(doc("http://127.0.0.1:9000"), "http://127.0.0.1:9000", false));
    }

    @Test
    void javascriptAndOtherSchemesRejected() {
        ObjectNode d = doc("https://bsky.social");
        d.put("authorization_endpoint", "javascript://bsky.social/%0Aalert(1)");
        assertEquals("authorization_endpoint", failedCheck(d, "https://bsky.social", true));
        d.put("authorization_endpoint", "javascript:alert(1)");
        assertEquals("authorization_endpoint", failedCheck(d, "https://bsky.social", true));
    }

    @Test
    void everyEndpointOnTheIssuerOrigin() {
        for (String member : new String[] {"authorization_endpoint", "token_endpoint",
                "pushed_authorization_request_endpoint", "revocation_endpoint"}) {
            ObjectNode d = doc("https://bsky.social");
            d.put(member, "https://evil.example/x");
            assertEquals(member, failedCheck(d, "https://bsky.social", false));
            d.put(member, "https://bsky.social:8443/x");
            assertEquals(member, failedCheck(d, "https://bsky.social", false));
            d.put(member, "https://user@bsky.social/x");
            assertEquals(member, failedCheck(d, "https://bsky.social", false));
        }
    }

    @Test
    void issuerComparedExactly() {
        assertEquals("issuer", failedCheck(doc("https://BSKY.social"), "https://bsky.social", false));
        assertEquals("issuer", failedCheck(doc("https://bsky.social/"), "https://bsky.social", false));
    }
}
