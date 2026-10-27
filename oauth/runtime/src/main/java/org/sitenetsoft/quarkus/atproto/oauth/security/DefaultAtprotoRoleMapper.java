package org.sitenetsoft.quarkus.atproto.oauth.security;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;

/** Gives every signed-in account the role {@code user}. */
@ApplicationScoped
public class DefaultAtprotoRoleMapper implements AtprotoRoleMapper {

    @Override
    public Set<String> roles(Did did, Handle handle) {
        return Set.of(USER);
    }
}
