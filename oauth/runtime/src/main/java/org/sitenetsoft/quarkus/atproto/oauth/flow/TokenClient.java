package org.sitenetsoft.quarkus.atproto.oauth.flow;

import java.net.URI;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.quarkus.atproto.oauth.client.ClientIdentity;

import io.smallrye.mutiny.Uni;

/** The token endpoint (code exchange, refresh) and the revocation endpoint, with DPoP and client authentication. */
@ApplicationScoped
public class TokenClient {

    private final AuthServerClient as;
    private final ClientIdentity client;

    public TokenClient(AuthServerClient as, ClientIdentity client) {
        this.as = as;
        this.client = client;
    }

    /** {@code authorization_code} grant with the PKCE verifier. */
    public Uni<TokenResponse> exchangeCode(String issuer, URI tokenEndpoint, KeyPair dpopKey, String code,
            String codeVerifier) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("redirect_uri", client.redirectUri().toString());
        params.put("code_verifier", codeVerifier);
        return token("token", issuer, tokenEndpoint, dpopKey, params);
    }

    /** {@code refresh_token} grant; the refresh token is spent whatever the outcome. */
    public Uni<TokenResponse> refresh(String issuer, URI tokenEndpoint, KeyPair dpopKey, String refreshToken) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", refreshToken);
        return token("refresh", issuer, tokenEndpoint, dpopKey, params);
    }

    /** RFC 7009 revocation of {@code token}; fails on an error answer. */
    public Uni<Void> revoke(String issuer, URI revocationEndpoint, KeyPair dpopKey, String token, String hint) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("token", token);
        params.put("token_type_hint", hint);
        return as.post("revoke", revocationEndpoint, issuer, dpopKey, params).map(a -> {
            if (!a.success()) {
                throw a.error("revoke");
            }
            return null;
        });
    }

    private Uni<TokenResponse> token(String name, String issuer, URI endpoint, KeyPair dpopKey,
            Map<String, String> params) {
        return as.post(name, endpoint, issuer, dpopKey, params).map(a -> {
            if (!a.success()) {
                throw a.error(name);
            }
            return TokenResponse.parse(name, a.status(), a.body());
        });
    }
}
