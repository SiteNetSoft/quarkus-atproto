package org.sitenetsoft.quarkus.atproto.oauth.discovery;

/**
 * The account's servers cannot be used for AT Protocol OAuth: a metadata document could not be fetched, or failed one
 * of the required checks. {@link #check()} names it (a metadata member such as {@code issuer} or
 * {@code require_pushed_authorization_requests}, or {@code protected-resource-metadata} /
 * {@code authorization-server-metadata} for fetch failures).
 */
public class OAuthDiscoveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String check;

    public OAuthDiscoveryException(String check, String message) {
        super(check + ": " + message);
        this.check = check;
    }

    public OAuthDiscoveryException(String check, String message, Throwable cause) {
        super(check + ": " + message, cause);
        this.check = check;
    }

    /** The failed check. */
    public String check() {
        return check;
    }
}
