package org.sitenetsoft.quarkus.atproto.oauth.discovery;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728) as a PDS serves it at
 * {@code /.well-known/oauth-protected-resource}: the members this client uses.
 */
public record ProtectedResourceMetadata(Optional<String> resource, List<String> authorizationServers) {

    public ProtectedResourceMetadata {
        resource = resource == null ? Optional.empty() : resource;
        authorizationServers = List.copyOf(authorizationServers);
    }

    static ProtectedResourceMetadata parse(JsonNode doc) {
        JsonNode servers = doc.get("authorization_servers");
        List<String> list = new ArrayList<>();
        if (servers != null && servers.isArray()) {
            for (JsonNode s : servers) {
                if (!s.isTextual()) {
                    throw new OAuthDiscoveryException("authorization_servers", "entries must be strings");
                }
                list.add(s.asText());
            }
        } else if (servers != null) {
            throw new OAuthDiscoveryException("authorization_servers", "must be an array");
        }
        JsonNode resource = doc.get("resource");
        return new ProtectedResourceMetadata(
                resource != null && resource.isTextual() ? Optional.of(resource.asText()) : Optional.empty(), list);
    }

    /** The single authorization server, as AT Protocol requires. */
    URI authorizationServer() {
        if (authorizationServers.size() != 1) {
            throw new OAuthDiscoveryException("authorization_servers",
                    "exactly one entry required, found " + authorizationServers.size());
        }
        try {
            return URI.create(authorizationServers.get(0));
        } catch (IllegalArgumentException e) {
            throw new OAuthDiscoveryException("authorization_servers", "not a URL");
        }
    }
}
