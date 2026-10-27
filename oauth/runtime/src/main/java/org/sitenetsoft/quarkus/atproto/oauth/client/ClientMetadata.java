package org.sitenetsoft.quarkus.atproto.oauth.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;

/** The client metadata document (AT Protocol OAuth, "Client ID Metadata Document"). */
public final class ClientMetadata {

    private ClientMetadata() {
    }

    /**
     * The document served at {@code client_id} for a confidential client. For the development client it is the
     * metadata the authorization server derives from the {@code http://localhost} client id (not served).
     */
    public static Map<String, Object> document(ClientIdentity identity, OAuthConfig config) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("client_id", identity.clientId());
        if (identity.confidential()) {
            config.clientName().ifPresent(v -> doc.put("client_name", v));
            doc.put("client_uri", config.clientUri().orElse(identity.baseUrl().orElseThrow().toString()));
            put(doc, "logo_uri", config.logoUri());
            put(doc, "tos_uri", config.tosUri());
            put(doc, "policy_uri", config.policyUri());
        }
        doc.put("redirect_uris", List.of(identity.redirectUri().toString()));
        doc.put("grant_types", List.of("authorization_code", "refresh_token"));
        doc.put("response_types", List.of("code"));
        doc.put("scope", identity.scope());
        doc.put("dpop_bound_access_tokens", true);
        if (identity.confidential()) {
            doc.put("application_type", "web");
            doc.put("token_endpoint_auth_method", "private_key_jwt");
            doc.put("token_endpoint_auth_signing_alg", "ES256");
            doc.put("jwks_uri", identity.jwksUri().orElseThrow().toString());
        } else {
            doc.put("application_type", "native");
            doc.put("token_endpoint_auth_method", "none");
        }
        return doc;
    }

    private static void put(Map<String, Object> doc, String name, Optional<String> value) {
        value.ifPresent(v -> doc.put(name, v));
    }
}
