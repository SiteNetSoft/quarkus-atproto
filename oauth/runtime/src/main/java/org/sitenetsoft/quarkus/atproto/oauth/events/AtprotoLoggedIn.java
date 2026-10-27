package org.sitenetsoft.quarkus.atproto.oauth.events;

import java.util.Optional;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;

/**
 * CDI event: an account signed in (a new session exists).
 *
 * @param appPrincipal the application principal the account was linked to ({@code link} mode); empty in {@code login}
 *        mode
 */
public record AtprotoLoggedIn(Did did, Handle handle, Optional<String> appPrincipal) {
}
