package org.sitenetsoft.quarkus.atproto.oauth.discovery;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * OAuth 2.0 Authorization Server Metadata (RFC 8414) after the AT Protocol checks: {@code issuer} equals the origin it
 * was fetched from; PAR required; {@code iss} in authorization responses; client ID metadata documents; ES256 DPoP.
 */
public record AuthorizationServerMetadata(String issuer, URI authorizationEndpoint, URI tokenEndpoint,
        URI pushedAuthorizationRequestEndpoint, Optional<URI> revocationEndpoint, List<String> scopesSupported,
        List<String> dpopSigningAlgValuesSupported, List<String> tokenEndpointAuthMethodsSupported) {

    public AuthorizationServerMetadata {
        revocationEndpoint = revocationEndpoint == null ? Optional.empty() : revocationEndpoint;
        scopesSupported = List.copyOf(scopesSupported);
        dpopSigningAlgValuesSupported = List.copyOf(dpopSigningAlgValuesSupported);
        tokenEndpointAuthMethodsSupported = List.copyOf(tokenEndpointAuthMethodsSupported);
    }

    /**
     * Parses and checks {@code doc}, fetched from {@code expectedIssuer}{@code /.well-known/oauth-authorization-server}.
     *
     * @throws OAuthDiscoveryException naming the first failed check
     */
    static AuthorizationServerMetadata parseAndCheck(JsonNode doc, String expectedIssuer) {
        String issuer = text(doc, "issuer").orElseThrow(() -> new OAuthDiscoveryException("issuer", "missing"));
        if (!issuer.equals(expectedIssuer)) {
            throw new OAuthDiscoveryException("issuer",
                    "'" + issuer + "' does not equal the origin it was fetched from, '" + expectedIssuer + "'");
        }
        requireTrue(doc, "require_pushed_authorization_requests");
        requireTrue(doc, "authorization_response_iss_parameter_supported");
        requireTrue(doc, "client_id_metadata_document_supported");
        List<String> dpopAlgs = strings(doc, "dpop_signing_alg_values_supported");
        if (!dpopAlgs.contains("ES256")) {
            throw new OAuthDiscoveryException("dpop_signing_alg_values_supported", "ES256 is not listed");
        }
        return new AuthorizationServerMetadata(issuer, endpoint(doc, "authorization_endpoint"),
                endpoint(doc, "token_endpoint"), endpoint(doc, "pushed_authorization_request_endpoint"),
                text(doc, "revocation_endpoint").map(v -> url("revocation_endpoint", v)),
                strings(doc, "scopes_supported"), dpopAlgs, strings(doc, "token_endpoint_auth_methods_supported"));
    }

    private static void requireTrue(JsonNode doc, String member) {
        JsonNode v = doc.get(member);
        if (v == null || !v.isBoolean() || !v.booleanValue()) {
            throw new OAuthDiscoveryException(member, "must be true");
        }
    }

    private static URI endpoint(JsonNode doc, String member) {
        return url(member, text(doc, member).orElseThrow(() -> new OAuthDiscoveryException(member, "missing")));
    }

    private static URI url(String member, String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new OAuthDiscoveryException(member, "not an absolute URL");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new OAuthDiscoveryException(member, "not a URL");
        }
    }

    private static Optional<String> text(JsonNode doc, String member) {
        JsonNode v = doc.get(member);
        return v != null && v.isTextual() ? Optional.of(v.asText()) : Optional.empty();
    }

    private static List<String> strings(JsonNode doc, String member) {
        List<String> out = new ArrayList<>();
        JsonNode v = doc.get(member);
        if (v != null && v.isArray()) {
            v.forEach(e -> {
                if (e.isTextual()) {
                    out.add(e.asText());
                }
            });
        }
        return out;
    }
}
