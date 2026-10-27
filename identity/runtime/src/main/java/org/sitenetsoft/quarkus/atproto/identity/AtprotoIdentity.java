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
 * @param handleCheckFailed {@code true} when the claimed handle could not be checked right now (a transient
 *        resolution failure): {@code handle} is then {@link Handle#INVALID} for this answer only (it is not cached),
 *        and a caller holding an earlier verified handle may keep it
 */
public record AtprotoIdentity(Did did, Handle handle, URI pds, Optional<String> signingKeyMultibase,
        boolean handleCheckFailed) {

    public AtprotoIdentity {
        Objects.requireNonNull(did, "did");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(pds, "pds");
        signingKeyMultibase = signingKeyMultibase == null ? Optional.empty() : signingKeyMultibase;
    }

    /** An identity whose handle check completed (verified, or definitively {@link Handle#INVALID}). */
    public AtprotoIdentity(Did did, Handle handle, URI pds, Optional<String> signingKeyMultibase) {
        this(did, handle, pds, signingKeyMultibase, false);
    }

    /** Whether {@link #handle()} passed the bidirectional check (is not {@link Handle#INVALID}). */
    public boolean handleVerified() {
        return !Handle.INVALID.equals(handle);
    }

    /** This identity with another handle (a definitive one: {@link #handleCheckFailed()} is {@code false}). */
    public AtprotoIdentity withHandle(Handle other) {
        return new AtprotoIdentity(did, other, pds, signingKeyMultibase, false);
    }
}
