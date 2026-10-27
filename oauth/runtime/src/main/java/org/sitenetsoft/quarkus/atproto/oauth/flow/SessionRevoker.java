package org.sitenetsoft.quarkus.atproto.oauth.flow;

import java.net.URI;

import jakarta.inject.Singleton;

import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.atproto.oauth.discovery.Discovery;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionStores;

import io.smallrye.mutiny.Uni;

/**
 * Ends a session: revokes its refresh token at the authorization server's {@code revocation_endpoint} when it has one
 * (best effort: a failure is logged at DEBUG, without the token) and deletes it from the store.
 */
@Singleton
public class SessionRevoker {

    private static final Logger LOG = Logger.getLogger(SessionRevoker.class);

    private final SessionStores stores;
    private final Discovery discovery;
    private final TokenClient tokens;

    public SessionRevoker(SessionStores stores, Discovery discovery, TokenClient tokens) {
        this.stores = stores;
        this.discovery = discovery;
        this.tokens = tokens;
    }

    /** Revoke (best effort), then delete. */
    public Uni<Void> end(AtprotoSession session) {
        return revoke(session).chain(v -> stores.sessions().delete(session.id()));
    }

    private Uni<Void> revoke(AtprotoSession session) {
        return Uni.createFrom().deferred(() -> discovery.authorizationServer(URI.create(session.issuer())))
                .chain(as -> {
                    if (as.revocationEndpoint().isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }
                    return tokens.revoke(as.issuer(), as.revocationEndpoint().get(), session.dpopKey(),
                            session.refreshToken(), "refresh_token");
                }).onFailure().recoverWithItem(e -> {
                    LOG.debugf("Revoking the session of %s at %s failed (%s); deleting it anyway", session.did(),
                            session.issuer(), e.getClass().getSimpleName());
                    return null;
                });
    }
}
