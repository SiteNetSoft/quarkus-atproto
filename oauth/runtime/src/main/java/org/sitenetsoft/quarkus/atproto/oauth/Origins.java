package org.sitenetsoft.quarkus.atproto.oauth;

import java.net.URI;
import java.util.Locale;

/** Web origins: {@code scheme://host[:port]}, scheme and host lower case, default ports omitted, no user info. */
public final class Origins {

    private Origins() {
    }

    /**
     * The origin of an absolute http(s) URL.
     *
     * @throws IllegalArgumentException when {@code uri} has no scheme or host, or is not http(s)
     */
    public static String of(URI uri) {
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("'" + uri + "' is not an absolute URL with a host");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalArgumentException("'" + uri + "' is not an http(s) URL");
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("https") && port == 443)
                || (scheme.equals("http") && port == 80);
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }
}
