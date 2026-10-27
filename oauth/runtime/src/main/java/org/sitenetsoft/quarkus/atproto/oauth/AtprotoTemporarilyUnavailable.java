package org.sitenetsoft.quarkus.atproto.oauth;

/**
 * The AT Protocol session could not be used right now because something it depends on failed temporarily (the DID
 * directory, the PDS's or authorization server's metadata). The session and its refresh token are untouched: retry
 * later.
 */
public class AtprotoTemporarilyUnavailable extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AtprotoTemporarilyUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
