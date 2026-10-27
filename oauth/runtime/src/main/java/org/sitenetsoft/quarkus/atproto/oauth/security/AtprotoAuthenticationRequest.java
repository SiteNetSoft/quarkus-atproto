package org.sitenetsoft.quarkus.atproto.oauth.security;

import io.quarkus.security.identity.request.BaseAuthenticationRequest;

/** The session id read from the session cookie, to be turned into a {@code SecurityIdentity}. */
public final class AtprotoAuthenticationRequest extends BaseAuthenticationRequest {

    private final String sessionId;

    public AtprotoAuthenticationRequest(String sessionId) {
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }

    @Override
    public String toString() {
        return "AtprotoAuthenticationRequest";
    }
}
