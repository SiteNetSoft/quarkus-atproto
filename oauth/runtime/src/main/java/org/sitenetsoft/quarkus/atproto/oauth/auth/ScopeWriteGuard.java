package org.sitenetsoft.quarkus.atproto.oauth.auth;

import java.util.Optional;

import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.quarkus.atproto.xrpc.RecordsImpl;
import org.sitenetsoft.quarkus.atproto.xrpc.WriteGuard;

/**
 * The records API's {@link WriteGuard} for one session: refuses, with {@link AtprotoScopeException}, a write its
 * granted scope does not cover. A {@code put} ({@code update}) may create the record, so it needs both {@code create}
 * and {@code update} (as the PDS asks for {@code putRecord}). Not a CDI bean: one per session.
 */
public final class ScopeWriteGuard implements WriteGuard {

    private final String grantedScope;

    public ScopeWriteGuard(String grantedScope) {
        this.grantedScope = grantedScope;
    }

    @Override
    public void check(Nsid collection, String action, Optional<String> blobMime) {
        boolean allowed;
        if (RecordsImpl.UPLOAD_BLOB.equals(collection)) {
            allowed = ScopeCheck.allows(grantedScope, "blob", blobMime.orElse(""), Optional.empty());
        } else if ("update".equals(action)) {
            allowed = ScopeCheck.allows(grantedScope, "repo", collection.value(), Optional.of("create"))
                    && ScopeCheck.allows(grantedScope, "repo", collection.value(), Optional.of("update"));
        } else {
            allowed = ScopeCheck.allows(grantedScope, "repo", collection.value(), Optional.of(action));
        }
        if (!allowed) {
            throw new AtprotoScopeException(collection, action,
                    RecordsImpl.UPLOAD_BLOB.equals(collection) ? blobMime : Optional.empty(), grantedScope);
        }
    }
}
