package org.sitenetsoft.quarkus.atproto.oauth.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class AuthServerClientTest {

    @Test
    void errorCodeKeptOnlyWhenPlain() {
        assertEquals("invalid_grant", AuthServerClient.errorCode("invalid_grant"));
        assertEquals("use_dpop_nonce", AuthServerClient.errorCode("use_dpop_nonce"));
        assertEquals("http_503", AuthServerClient.errorCode("http_503"));
        assertEquals("invalid_response", AuthServerClient.errorCode("invalid_grant\nINFO forged"));
        assertEquals("invalid_response", AuthServerClient.errorCode("Invalid_Grant"));
        assertEquals("invalid_response", AuthServerClient.errorCode("<b>x</b>"));
        assertEquals("invalid_response", AuthServerClient.errorCode("a".repeat(65)));
        assertEquals("invalid_response", AuthServerClient.errorCode(""));
        assertEquals("invalid_response", AuthServerClient.errorCode(null));
    }

    @Test
    void answerErrorSanitisesTheCode() {
        ObjectNode body = new ObjectMapper().createObjectNode().put("error", "x".repeat(10_000) + "\r\n");
        OAuthServerError e = new AuthServerClient.Answer(400, body).error("token");
        assertEquals("invalid_response", e.error());
        assertEquals("invalid_response", e.getMessage().split(" ")[3].replace(":", ""));
    }
}
