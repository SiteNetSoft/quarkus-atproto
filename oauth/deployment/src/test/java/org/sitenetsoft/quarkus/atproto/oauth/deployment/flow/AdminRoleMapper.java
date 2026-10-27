package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.quarkus.atproto.oauth.security.AtprotoRoleMapper;

/** An application's role mapper: {@code admin} for one DID. */
@ApplicationScoped
public class AdminRoleMapper implements AtprotoRoleMapper {

    static volatile Did admin;

    @Override
    public Set<String> roles(Did did, Handle handle) {
        return did.equals(admin) ? Set.of("admin") : Set.of();
    }
}
