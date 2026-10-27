package org.sitenetsoft.quarkus.atproto.oauth.flow;

/**
 * An authorization server answered an error ({@code {"error": ..., "error_description": ...}}) or an unusable
 * response. The description comes from the server; it never contains our tokens.
 */
public class OAuthServerError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String endpoint;
    private final int status;
    private final String error;
    private final String description;

    public OAuthServerError(String endpoint, int status, String error, String description) {
        super(endpoint + " answered " + status + " " + error + (description == null ? "" : ": " + description));
        this.endpoint = endpoint;
        this.status = status;
        this.error = error;
        this.description = description;
    }

    /** Which call: {@code par}, {@code token}, {@code refresh}, {@code revoke}. */
    public String endpoint() {
        return endpoint;
    }

    public int status() {
        return status;
    }

    /** The OAuth error code ({@code invalid_grant}, {@code invalid_scope}, ...). */
    public String error() {
        return error;
    }

    /** The server's {@code error_description}, or {@code null}. */
    public String description() {
        return description;
    }
}
