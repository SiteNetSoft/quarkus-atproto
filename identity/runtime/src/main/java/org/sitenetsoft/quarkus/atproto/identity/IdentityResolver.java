package org.sitenetsoft.quarkus.atproto.identity;

import org.sitenetsoft.atproto.syntax.AtIdentifier;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;

/**
 * Blocking AT Protocol identity resolution. Do not call it on the Vert.x event loop; use
 * {@link ReactiveIdentityResolver} there. For user input ({@code @Alice.bsky.social.}) build the identifier with
 * {@link Handle#normalise(String)} first.
 */
public interface IdentityResolver {

    /**
     * Resolves a handle to its DID: DNS TXT {@code _atproto.<handle>} ({@code did=…}) and HTTPS
     * {@code https://<handle>/.well-known/atproto-did} in parallel. DNS wins when both answer differently.
     *
     * @throws HandleNotFound neither method gave a DID
     * @throws IdentityResolutionFailed the DNS records name several different DIDs
     */
    Did resolveHandle(Handle handle);

    /**
     * Resolves a handle or DID to a verified identity. For a handle, the result keeps it only when the DID document
     * claims that same handle; otherwise the handle is {@link Handle#INVALID}.
     *
     * @throws HandleNotFound a handle that does not resolve
     * @throws NoPds the DID document names no usable PDS
     * @throws IdentityResolutionFailed anything else (DID not found or invalid, timeout)
     */
    AtprotoIdentity resolve(AtIdentifier identifier);

    /**
     * Resolves a DID: fetches its document, takes the PDS and signing key, and keeps the first valid {@code at://}
     * handle of {@code alsoKnownAs} only if that handle resolves back to this DID ({@link Handle#INVALID} otherwise).
     *
     * @throws NoPds the DID document names no usable PDS
     * @throws IdentityResolutionFailed not did:plc/did:web, DID not found or invalid, timeout
     */
    AtprotoIdentity resolveDid(Did did);
}
