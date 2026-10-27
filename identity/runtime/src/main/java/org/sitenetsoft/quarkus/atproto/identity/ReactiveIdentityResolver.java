package org.sitenetsoft.quarkus.atproto.identity;

import org.sitenetsoft.atproto.syntax.AtIdentifier;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;

import io.smallrye.mutiny.Uni;

/**
 * Mutiny form of {@link IdentityResolver}; safe on the event loop. Failures are {@link IdentityException} subclasses.
 */
public interface ReactiveIdentityResolver {

    /** Handle → DID through DNS and HTTPS (no DID document fetch, no bidirectional check). */
    Uni<Did> resolveHandle(Handle handle);

    /** Handle or DID → verified identity. */
    Uni<AtprotoIdentity> resolve(AtIdentifier identifier);

    /** DID → identity, with the handle the document claims checked in the other direction. */
    Uni<AtprotoIdentity> resolveDid(Did did);
}
