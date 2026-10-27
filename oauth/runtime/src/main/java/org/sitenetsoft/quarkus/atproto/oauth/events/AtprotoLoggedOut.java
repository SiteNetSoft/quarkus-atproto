package org.sitenetsoft.quarkus.atproto.oauth.events;

import java.util.Optional;

import org.sitenetsoft.atproto.syntax.Did;

/** CDI event: an account signed out (its session was deleted on request). */
public record AtprotoLoggedOut(Did did, Optional<String> appPrincipal) {
}
