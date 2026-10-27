package org.sitenetsoft.quarkus.atproto.identity;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;

/**
 * A resolved AT Protocol account.
 *
 * @param did the account DID ({@code did:plc} or {@code did:web})
 * @param handle the handle, verified in both directions; {@link Handle#INVALID} when the DID document claims no handle,
 *        or the claimed handle does not resolve back to {@code did}
 * @param pds the Personal Data Server: scheme, host and optional port only
 * @param signingKeyMultibase the {@code #atproto} verification method's public key (Multikey), when present
 */
public record AtprotoIdentity(Did did, Handle handle, URI pds, Optional<String> signingKeyMultibase) {

    public AtprotoIdentity {
        Objects.requireNonNull(did, "did");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(pds, "pds");
        signingKeyMultibase = signingKeyMultibase == null ? Optional.empty() : signingKeyMultibase;
    }

    /** Whether {@link #handle()} passed the bidirectional check (is not {@link Handle#INVALID}). */
    public boolean handleVerified() {
        return !Handle.INVALID.equals(handle);
    }

    /** This identity with another handle. */
    public AtprotoIdentity withHandle(Handle other) {
        return new AtprotoIdentity(did, other, pds, signingKeyMultibase);
    }
}
