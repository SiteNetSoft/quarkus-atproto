package org.sitenetsoft.quarkus.atproto.identity;

/**
 * Base of every identity resolution failure: {@link HandleNotFound}, {@link IdentityResolutionFailed} and
 * {@link NoPds}.
 */
public class IdentityException extends RuntimeException {

    private final String identifier;

    public IdentityException(String identifier, String message) {
        super(message);
        this.identifier = identifier;
    }

    public IdentityException(String identifier, String message, Throwable cause) {
        super(message, cause);
        this.identifier = identifier;
    }

    /** The handle or DID that was being resolved. */
    public String identifier() {
        return identifier;
    }
}
