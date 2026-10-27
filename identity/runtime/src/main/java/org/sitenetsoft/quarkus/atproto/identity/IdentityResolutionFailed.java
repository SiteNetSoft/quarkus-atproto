package org.sitenetsoft.quarkus.atproto.identity;

/**
 * Resolution could not complete: conflicting DNS records, a DID that is not {@code did:plc}/{@code did:web}, a DID
 * resolution failure (the {@code DidException} is the cause) or a timeout.
 */
public class IdentityResolutionFailed extends IdentityException {

    public IdentityResolutionFailed(String identifier, String message) {
        super(identifier, message);
    }

    public IdentityResolutionFailed(String identifier, String message, Throwable cause) {
        super(identifier, message, cause);
    }
}
