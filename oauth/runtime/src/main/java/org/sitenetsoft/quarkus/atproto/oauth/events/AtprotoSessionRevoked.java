package org.sitenetsoft.quarkus.atproto.oauth.events;

import org.sitenetsoft.atproto.syntax.Did;

/**
 * CDI event: a session ended without the user asking here, typically because the authorization server refused its
 * refresh token ({@code invalid_grant}: revoked elsewhere, replayed, expired).
 *
 * @param reason a short reason, never a token
 */
public record AtprotoSessionRevoked(Did did, String reason) {
}
