package org.sitenetsoft.quarkus.atproto.oauth.jose;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.sitenetsoft.quarkus.atproto.oauth.client.ClientKey;

/** {@code private_key_jwt} client assertions (RFC 7523 §2.2, RFC 7521). */
public final class ClientAssertions {

    /** The {@code client_assertion_type} value. */
    public static final String TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    /** Lifetime of an assertion ({@code exp} - {@code iat}). */
    static final long LIFETIME_SECONDS = 60;

    private ClientAssertions() {
    }

    /**
     * A one-time assertion: header {@code kid}; claims {@code iss} = {@code sub} = {@code clientId},
     * {@code aud} = the authorization server's issuer, {@code jti}, {@code iat} and {@code exp} (RFC 7523 requires it;
     * one minute later). Never log the result.
     */
    public static String assertion(ClientKey key, String clientId, String audienceIssuer, Instant now) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("kid", key.kid());
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", clientId);
        claims.put("sub", clientId);
        claims.put("aud", audienceIssuer);
        claims.put("jti", DpopProofs.jti());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.getEpochSecond() + LIFETIME_SECONDS);
        return Jws.sign(header, claims, key.privateKey());
    }
}
