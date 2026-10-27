package org.sitenetsoft.quarkus.atproto.oauth.discovery;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;
import org.sitenetsoft.atproto.syntax.AtIdentifier;
import org.sitenetsoft.quarkus.atproto.identity.ReactiveIdentityResolver;
import org.sitenetsoft.quarkus.atproto.oauth.Origins;
import org.sitenetsoft.quarkus.safefetch.FetchOptions;
import org.sitenetsoft.quarkus.safefetch.FetchResponse;
import org.sitenetsoft.quarkus.safefetch.ReactiveSafeFetchClient;
import org.sitenetsoft.quarkus.safefetch.SafeFetchConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.mutiny.Uni;

/**
 * Finds and checks an account's authorization server: identity → PDS → {@code /.well-known/oauth-protected-resource}
 * (exactly one {@code authorization_servers} entry) → {@code /.well-known/oauth-authorization-server} (see
 * {@link AuthorizationServerMetadata} for the checks). Metadata fetches go through safe-fetch and refuse redirects to
 * another origin. Identity failures ({@code HandleNotFound}, {@code IdentityResolutionFailed}, ...) pass through
 * unchanged; everything else fails with {@link OAuthDiscoveryException}.
 */
@ApplicationScoped
public class Discovery {

    private static final Logger LOG = Logger.getLogger(Discovery.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_METADATA_BYTES = 64 * 1024;
    static final String PROTECTED_RESOURCE = "/.well-known/oauth-protected-resource";
    static final String AUTHORIZATION_SERVER = "/.well-known/oauth-authorization-server";

    private final ReactiveIdentityResolver identities;
    private final ReactiveSafeFetchClient http;
    private final boolean allowHttp;

    public Discovery(ReactiveIdentityResolver identities, ReactiveSafeFetchClient http, SafeFetchConfig safeFetch) {
        this.identities = identities;
        this.http = http;
        // safe-fetch refuses http fetches anyway; this keeps browser redirects (authorization_endpoint) consistent
        this.allowHttp = !safeFetch.httpsOnly();
    }

    /** Handle or DID → account, PDS and checked authorization server. */
    public Uni<DiscoveredServer> resolve(AtIdentifier identifier) {
        return identities.resolve(identifier)
                .flatMap(identity -> authorizationServerOf(identity.pds())
                        .map(as -> new DiscoveredServer(Optional.of(identity), identity.pds(), as)));
    }

    /**
     * A PDS or entryway host instead of an account: its protected-resource metadata when it serves one, else the
     * host itself as the authorization server (an entryway such as {@code https://bsky.social}).
     */
    public Uni<DiscoveredServer> resolveServer(URI server) {
        URI origin = URI.create(origin(server, "server"));
        return fetch(origin.resolve(PROTECTED_RESOURCE), "protected-resource-metadata", true)
                .flatMap(doc -> doc.isPresent()
                        ? fromProtectedResource(origin, doc.get())
                        : authorizationServer(origin))
                .map(as -> new DiscoveredServer(Optional.empty(), origin, as));
    }

    /** The checked authorization server protecting {@code resourceServer} (a PDS origin). */
    public Uni<AuthorizationServerMetadata> authorizationServerOf(URI resourceServer) {
        URI origin = URI.create(origin(resourceServer, "pds"));
        return fetch(origin.resolve(PROTECTED_RESOURCE), "protected-resource-metadata", false)
                .flatMap(doc -> fromProtectedResource(origin, doc.orElseThrow()));
    }

    /** The checked metadata of the authorization server at {@code issuer} (an origin). */
    public Uni<AuthorizationServerMetadata> authorizationServer(URI issuer) {
        String expected = origin(issuer, "authorization_servers");
        return fetch(URI.create(expected).resolve(AUTHORIZATION_SERVER), "authorization-server-metadata", false)
                .map(doc -> AuthorizationServerMetadata.parseAndCheck(doc.orElseThrow(), expected, allowHttp));
    }

    private Uni<AuthorizationServerMetadata> fromProtectedResource(URI origin, JsonNode doc) {
        ProtectedResourceMetadata pr = ProtectedResourceMetadata.parse(doc);
        if (pr.resource().isPresent() && !sameOrigin(pr.resource().get(), origin)) {
            throw new OAuthDiscoveryException("resource",
                    "'" + pr.resource().get() + "' is not the origin it was fetched from, '" + origin + "'");
        }
        URI as = pr.authorizationServer();
        AuthorizationServerMetadata.requireScheme("authorization_servers", as, allowHttp);
        if (!isOrigin(as)) {
            throw new OAuthDiscoveryException("authorization_servers", "'" + as + "' is not an origin URL");
        }
        LOG.debugf("Resource server %s uses authorization server %s", origin, as);
        return authorizationServer(as);
    }

    /** GET a JSON object; {@code Optional.empty()} only for a 404 when {@code notFoundIsEmpty}. */
    private Uni<Optional<JsonNode>> fetch(URI url, String check, boolean notFoundIsEmpty) {
        FetchOptions options = FetchOptions.defaults().withMaxResponseSize(MAX_METADATA_BYTES)
                .withHeader("Accept", "application/json");
        return http.get(url, options)
                .onFailure(e -> !(e instanceof OAuthDiscoveryException))
                .transform(e -> new OAuthDiscoveryException(check,
                        url + " could not be fetched (" + e.getClass().getSimpleName() + ")", e))
                .map(r -> parse(url, r, check, notFoundIsEmpty));
    }

    private static Optional<JsonNode> parse(URI url, FetchResponse r, String check, boolean notFoundIsEmpty) {
        if (r.uri() != null && !sameOrigin(r.uri().toString(), url)) {
            throw new OAuthDiscoveryException(check, url + " redirected to another origin (" + origin(r.uri(), check)
                    + ")");
        }
        if (r.status() == 404 && notFoundIsEmpty) {
            return Optional.empty();
        }
        if (r.status() != 200) {
            throw new OAuthDiscoveryException(check, url + " answered HTTP " + r.status());
        }
        JsonNode doc;
        try {
            doc = JSON.readTree(r.body());
        } catch (IOException e) {
            throw new OAuthDiscoveryException(check, url + " is not JSON");
        }
        if (doc == null || !doc.isObject()) {
            throw new OAuthDiscoveryException(check, url + " is not a JSON object");
        }
        return Optional.of(doc);
    }

    private static boolean isOrigin(URI uri) {
        String path = uri.getRawPath();
        return uri.getScheme() != null && uri.getHost() != null && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null && uri.getRawFragment() == null
                && (path == null || path.isEmpty() || path.equals("/"));
    }

    private static boolean sameOrigin(String url, URI origin) {
        try {
            return origin(URI.create(url), "resource").equals(origin(origin, "resource"));
        } catch (IllegalArgumentException | OAuthDiscoveryException e) {
            return false;
        }
    }

    /** {@link Origins#of}, failing as {@code check}. */
    static String origin(URI uri, String check) {
        try {
            return Origins.of(uri);
        } catch (IllegalArgumentException e) {
            throw new OAuthDiscoveryException(check, e.getMessage());
        }
    }
}
