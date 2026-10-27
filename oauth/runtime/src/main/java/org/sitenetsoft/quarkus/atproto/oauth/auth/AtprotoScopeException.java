package org.sitenetsoft.quarkus.atproto.oauth.auth;

import java.util.Optional;

import org.sitenetsoft.atproto.syntax.Nsid;

/**
 * A write the session's granted scope does not cover, refused before anything is sent to the PDS (which would answer
 * 403). Ask for the scope in {@code quarkus.atproto.oauth.scope} and have the user sign in again.
 */
public class AtprotoScopeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Nsid collection;
    private final String action;
    private final Optional<String> blobMime;

    public AtprotoScopeException(Nsid collection, String action, Optional<String> blobMime, String grantedScope) {
        super(blobMime.isPresent()
                ? "The granted AT Protocol scope does not allow uploading a " + blobMime.get() + " blob (granted: "
                        + grantedScope + ")"
                : "The granted AT Protocol scope does not allow " + action + " on " + collection + " (granted: "
                        + grantedScope + ")");
        this.collection = collection;
        this.action = action;
        this.blobMime = blobMime;
    }

    /** The collection ({@code com.atproto.repo.uploadBlob} for a blob). */
    public Nsid collection() {
        return collection;
    }

    /** {@code create}, {@code update} or {@code delete}. */
    public String action() {
        return action;
    }

    public Optional<String> blobMime() {
        return blobMime;
    }
}
