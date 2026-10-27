package org.sitenetsoft.quarkus.atproto.oauth;

/**
 * The AT Protocol session is gone: deleted (logout, max age), or its refresh token was refused ({@code invalid_grant},
 * e.g. revoked by the user). The user has to sign in again.
 */
public class AtprotoSessionExpired extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AtprotoSessionExpired(String message) {
        super(message);
    }
}
