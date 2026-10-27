package org.sitenetsoft.quarkus.atproto.oauth.security;

import java.util.Set;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;

/**
 * Roles of a signed-in AT Protocol account. Every {@code AtprotoRoleMapper} bean is asked and the roles are united;
 * the extension's own mapper always gives {@code user}, so an application mapper adds roles (for example
 * {@code admin} for some DIDs). Called on a worker thread: may block.
 */
public interface AtprotoRoleMapper {

    /** The role every signed-in account has. */
    String USER = "user";

    Set<String> roles(Did did, Handle handle);
}
