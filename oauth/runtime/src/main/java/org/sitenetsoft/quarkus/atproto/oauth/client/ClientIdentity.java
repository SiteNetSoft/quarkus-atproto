package org.sitenetsoft.quarkus.atproto.oauth.client;

import static org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig.CLIENT_BASE_URL;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;

import io.quarkus.runtime.LaunchMode;

/**
 * Who this application is to authorization servers.
 * <ul>
 * <li><b>Confidential client</b> (whenever {@code quarkus.atproto.oauth.client-base-url} is a non-loopback URL):
 * {@code client_id} = {@code <base><metadata-path>}, authenticated with {@code private_key_jwt}.</li>
 * <li><b>Localhost development client</b> (dev and test mode when the base URL is unset or loopback): the
 * {@code client_id} is {@code http://localhost?redirect_uri=http://127.0.0.1:<port><callback-path>&scope=<scope>}, a
 * public client without keys whose metadata the authorization server derives from the {@code client_id}.</li>
 * </ul>
 * Production without a usable base URL fails startup, naming the property.
 */
public final class ClientIdentity {

    private final String clientId;
    private final boolean confidential;
    private final Optional<ClientKey> signingKey;
    private final URI redirectUri;
    private final String scope;
    private final Optional<URI> baseUrl;
    private final Optional<URI> jwksUri;

    private ClientIdentity(String clientId, boolean confidential, Optional<ClientKey> signingKey, URI redirectUri,
            String scope, Optional<URI> baseUrl, Optional<URI> jwksUri) {
        this.clientId = clientId;
        this.confidential = confidential;
        this.signingKey = signingKey;
        this.redirectUri = redirectUri;
        this.scope = scope;
        this.baseUrl = baseUrl;
        this.jwksUri = jwksUri;
    }

    /**
     * Decides the client type.
     *
     * @param clientBaseUrl {@code quarkus.atproto.oauth.client-base-url}
     * @param httpPort the local HTTP port, asked only for the development client
     * @param signingKey the first configured signing key; required for a confidential client
     * @throws IllegalStateException naming the property when the configuration cannot work in this launch mode
     */
    public static ClientIdentity resolve(Optional<String> clientBaseUrl, String scope, String metadataPath,
            String callbackPath, String jwksPath, LaunchMode launchMode, IntSupplier httpPort,
            Optional<ClientKey> signingKey) {
        Objects.requireNonNull(scope, "scope");
        Optional<URI> base = clientBaseUrl.map(String::strip).filter(s -> !s.isEmpty()).map(ClientIdentity::parseBase);
        boolean loopback = base.isPresent() && isLoopback(base.get().getHost());
        if (base.isPresent() && !loopback) {
            URI b = base.get();
            if (!"https".equals(b.getScheme())) {
                throw new IllegalStateException(CLIENT_BASE_URL + " must be an https URL for a confidential client, not "
                        + b);
            }
            if (signingKey.isEmpty()) {
                throw new IllegalStateException("A confidential client (" + CLIENT_BASE_URL + "=" + b
                        + ") needs an ES256 signing key: set "
                        + org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig.SIGNING_KEY_LOCATION);
            }
            String root = b.toString();
            return new ClientIdentity(root + path(metadataPath), true, signingKey, URI.create(root + path(callbackPath)),
                    scope, base, Optional.of(URI.create(root + path(jwksPath))));
        }
        if (launchMode != LaunchMode.DEVELOPMENT && launchMode != LaunchMode.TEST) {
            throw new IllegalStateException(base.isEmpty()
                    ? "Set " + CLIENT_BASE_URL + " to this application's public https URL: AT Protocol OAuth needs a "
                            + "confidential client in production (the localhost client is for dev and test mode only)"
                    : CLIENT_BASE_URL + " is a loopback URL (" + base.get() + "); production needs this application's "
                            + "public https URL");
        }
        URI redirect = URI.create("http://127.0.0.1:" + httpPort.getAsInt() + path(callbackPath));
        String id = "http://localhost?redirect_uri=" + encode(redirect.toString()) + "&scope=" + encode(scope);
        return new ClientIdentity(id, false, Optional.empty(), redirect, scope, Optional.empty(), Optional.empty());
    }

    /** The {@code client_id}. */
    public String clientId() {
        return clientId;
    }

    /** Whether this is a confidential client ({@code private_key_jwt}); {@code false} for the development client. */
    public boolean confidential() {
        return confidential;
    }

    /** The key that signs client assertions; present exactly when {@link #confidential()}. */
    public Optional<ClientKey> signingKey() {
        return signingKey;
    }

    /** The one registered redirect URI (the callback). */
    public URI redirectUri() {
        return redirectUri;
    }

    /** The scope registered (the configured scope plus the fallback scope), space separated. */
    public String scope() {
        return scope;
    }

    /** The confidential client's base URL, without a trailing slash. */
    public Optional<URI> baseUrl() {
        return baseUrl;
    }

    /** The confidential client's {@code jwks_uri}. */
    public Optional<URI> jwksUri() {
        return jwksUri;
    }

    @Override
    public String toString() {
        return (confidential ? "confidential" : "localhost development") + " client " + clientId;
    }

    private static URI parseBase(String value) {
        URI uri;
        try {
            uri = URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(CLIENT_BASE_URL + " is not a URL: " + value);
        }
        if (uri.getScheme() == null || uri.getHost() == null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalStateException(CLIENT_BASE_URL
                    + " must be an absolute URL with a host and no user info, query or fragment: " + value);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalStateException(CLIENT_BASE_URL + " must be an http(s) URL: " + value);
        }
        return uri;
    }

    static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost")) {
            return true;
        }
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        // literal addresses only: never a DNS lookup here
        if (!h.matches("[0-9.]+") && !h.contains(":")) {
            return false;
        }
        try {
            return InetAddress.getByName(h).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static String path(String p) {
        return p.startsWith("/") ? p : "/" + p;
    }

    /** Form-encodes with {@code %20} for spaces, which every query parser decodes the same way. */
    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
