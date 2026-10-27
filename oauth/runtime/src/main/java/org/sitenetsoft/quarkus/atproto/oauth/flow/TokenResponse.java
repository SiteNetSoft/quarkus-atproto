package org.sitenetsoft.quarkus.atproto.oauth.flow;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A token endpoint answer. {@link #toString()} leaves the tokens out.
 *
 * @param scope the granted scope, when the answer has one
 * @param sub the account DID as the authorization server says it, when present
 */
public record TokenResponse(String accessToken, String refreshToken, long expiresIn, Optional<String> scope,
        Optional<String> sub) {

    /** Lifetime assumed when the server does not say ({@code expires_in} missing). */
    static final long DEFAULT_EXPIRES_IN = 300;

    /**
     * Parses a successful answer: {@code access_token}, {@code token_type} {@code DPoP} and {@code refresh_token} are
     * required.
     */
    static TokenResponse parse(String endpoint, int status, JsonNode body) {
        String access = text(body, "access_token");
        if (access == null || access.isEmpty()) {
            throw new OAuthServerError(endpoint, status, "invalid_response", "no access_token");
        }
        String type = text(body, "token_type");
        if (!"DPoP".equalsIgnoreCase(type)) {
            throw new OAuthServerError(endpoint, status, "invalid_response",
                    "token_type is " + (type == null ? "missing" : AuthServerClient.abbreviate(type)) + ", not DPoP");
        }
        String refresh = text(body, "refresh_token");
        if (refresh == null || refresh.isEmpty()) {
            throw new OAuthServerError(endpoint, status, "invalid_response", "no refresh_token");
        }
        JsonNode exp = body.get("expires_in");
        long expiresIn = exp != null && exp.isNumber() ? Math.max(0, exp.asLong()) : DEFAULT_EXPIRES_IN;
        return new TokenResponse(access, refresh, expiresIn, Optional.ofNullable(text(body, "scope")),
                Optional.ofNullable(text(body, "sub")));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    @Override
    public String toString() {
        return "TokenResponse[expiresIn=" + expiresIn + ", scope=" + scope.orElse("-") + ", sub=" + sub.orElse("-")
                + "]";
    }
}
