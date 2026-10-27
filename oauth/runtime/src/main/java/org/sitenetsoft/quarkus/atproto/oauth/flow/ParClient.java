package org.sitenetsoft.quarkus.atproto.oauth.flow;

import java.net.URI;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.quarkus.atproto.oauth.client.ClientIdentity;
import org.sitenetsoft.quarkus.atproto.oauth.discovery.AuthorizationServerMetadata;

import io.smallrye.mutiny.Uni;

/** Pushed authorization requests (RFC 9126) with PKCE S256 and DPoP. */
@ApplicationScoped
public class ParClient {

    private final AuthServerClient as;
    private final ClientIdentity client;

    public ParClient(AuthServerClient as, ClientIdentity client) {
        this.as = as;
        this.client = client;
    }

    /**
     * Pushes the authorization request; the {@code request_uri} to send the browser with.
     *
     * @throws OAuthServerError (in the {@link Uni}) for an error answer, e.g. {@code invalid_scope}
     */
    public Uni<String> push(AuthorizationServerMetadata server, KeyPair dpopKey, String state, String codeChallenge,
            String scope, Optional<String> loginHint) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("code_challenge", codeChallenge);
        params.put("code_challenge_method", "S256");
        params.put("state", state);
        params.put("redirect_uri", client.redirectUri().toString());
        params.put("scope", scope);
        loginHint.ifPresent(h -> params.put("login_hint", h));
        URI endpoint = server.pushedAuthorizationRequestEndpoint();
        return as.post("par", endpoint, server.issuer(), dpopKey, params).map(a -> {
            if (!a.success()) {
                throw a.error("par");
            }
            String requestUri = a.body().path("request_uri").asText(null);
            if (requestUri == null || requestUri.isEmpty()) {
                throw new OAuthServerError("par", a.status(), "invalid_response", "no request_uri");
            }
            return requestUri;
        });
    }
}
