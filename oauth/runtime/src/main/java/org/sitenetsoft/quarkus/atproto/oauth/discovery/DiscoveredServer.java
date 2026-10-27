package org.sitenetsoft.quarkus.atproto.oauth.discovery;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

import org.sitenetsoft.quarkus.atproto.identity.AtprotoIdentity;

/**
 * Where to sign in.
 *
 * @param identity the account, when discovery started from a handle or DID
 * @param pds the resource server: the account's PDS, or the host given when discovery started from a server
 * @param as the checked authorization server metadata
 */
public record DiscoveredServer(Optional<AtprotoIdentity> identity, URI pds, AuthorizationServerMetadata as) {

    public DiscoveredServer {
        identity = identity == null ? Optional.empty() : identity;
        Objects.requireNonNull(pds, "pds");
        Objects.requireNonNull(as, "as");
    }
}
