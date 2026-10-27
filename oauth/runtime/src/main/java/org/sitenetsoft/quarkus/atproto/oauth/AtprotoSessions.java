package org.sitenetsoft.quarkus.atproto.oauth;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;

import org.jboss.logging.Logger;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.quarkus.atproto.oauth.auth.DpopXrpcAuth;
import org.sitenetsoft.quarkus.atproto.oauth.auth.ScopeWriteGuard;
import org.sitenetsoft.quarkus.atproto.oauth.auth.SessionXrpc;
import org.sitenetsoft.quarkus.atproto.oauth.events.AtprotoSessionRevoked;
import org.sitenetsoft.quarkus.atproto.oauth.flow.NonceStore;
import org.sitenetsoft.quarkus.atproto.oauth.flow.SessionRevoker;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.Refresher;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionStores;
import org.sitenetsoft.quarkus.atproto.xrpc.AtprotoRecords;
import org.sitenetsoft.quarkus.atproto.xrpc.ReactiveAtprotoRecords;
import org.sitenetsoft.quarkus.atproto.xrpc.RecordsImpl;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcClient;

/**
 * AT Protocol sessions by DID, for work outside a request: a scheduled job publishing a post later, a message
 * consumer. Needs no request context and no cookie. When the account is signed in several times (several browsers, or
 * linked to several application users) the most recently created live session is used.
 * <p>
 * The clients refresh the access token as requests do (one refresh per session in the process; across nodes only the
 * holder of the session store's refresh lease spends the refresh token, see
 * {@link org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore#tryAcquireRefreshLease}: a store shared by
 * several nodes must implement it). A call fails with {@link AtprotoSessionExpired} when the account has no live
 * session or its refresh token was refused ({@code invalid_grant}: revoked, expired): the job should ask the author to
 * sign in again; and with {@link AtprotoTemporarilyUnavailable} when the session could not be refreshed now (directory,
 * metadata or token endpoint outage, or another node's refresh still running; the session is kept): retry later. The
 * returned handles can be kept: every call reads the session, and goes to its current PDS. Lookups read the session store, so call these methods from worker or background
 * threads, not the event loop (the in-memory default store never blocks).
 */
@ApplicationScoped
public class AtprotoSessions {

    private static final Logger LOG = Logger.getLogger(AtprotoSessions.class);
    private static final Duration REVOKE_WAIT = Duration.ofSeconds(30);

    private final SessionStores stores;
    private final OAuthConfig config;
    private final RecordsImpl records;
    private final XrpcClient xrpcClient;
    private final Refresher refresher;
    private final NonceStore nonces;
    private final SessionRevoker revoker;
    private final Event<AtprotoSessionRevoked> revoked;

    public AtprotoSessions(SessionStores stores, OAuthConfig config, RecordsImpl records, XrpcClient xrpcClient,
            Refresher refresher, NonceStore nonces, SessionRevoker revoker, Event<AtprotoSessionRevoked> revoked) {
        this.stores = stores;
        this.config = config;
        this.records = records;
        this.xrpcClient = xrpcClient;
        this.refresher = refresher;
        this.nonces = nonces;
        this.revoker = revoker;
        this.revoked = revoked;
    }

    /** The account's most recently created session that is within {@code session.max-age}. */
    public Optional<AtprotoSession> find(Did did) {
        Instant now = Instant.now();
        return stores.blockingSessions().findByDid(did).stream()
                .filter(s -> s.created().plus(config.session().maxAge()).isAfter(now))
                .max(Comparator.comparing(AtprotoSession::created));
    }

    /**
     * The account's records, blocking, writes checked against the session's granted scope. The session is chosen now;
     * each call goes to that session's current PDS (an account that moved PDS is followed after a refresh), so the
     * view can be kept.
     */
    public AtprotoRecords records(Did did) {
        AtprotoSession s = require(did);
        DpopXrpcAuth auth = auth(s);
        return records.blockingForRepo(s.did(), auth::pds, auth, new ScopeWriteGuard(s.grantedScope()));
    }

    /** The account's records, reactive (the session is looked up now, the calls run when subscribed). */
    public ReactiveAtprotoRecords reactiveRecords(Did did) {
        AtprotoSession s = require(did);
        DpopXrpcAuth auth = auth(s);
        return records.forRepo(s.did(), auth::pds, auth, new ScopeWriteGuard(s.grantedScope()));
    }

    /**
     * An XRPC client whose calls without an explicit auth are authorized with the account's session (DPoP); the token
     * is only sent to the account's current PDS (as the session knows it at each call).
     */
    public XrpcClient xrpc(Did did) {
        return SessionXrpc.client(xrpcClient, auth(require(did)));
    }

    /**
     * Ends every session of the account: refresh tokens revoked at the authorization server (best effort), sessions
     * deleted, {@link AtprotoSessionRevoked} fired. Nothing when there is none. Links ({@code link} mode) stay and
     * fail with {@link AtprotoSessionExpired} until the account is linked again.
     */
    public void revoke(Did did) {
        List<AtprotoSession> all = stores.blockingSessions().findByDid(did);
        for (AtprotoSession s : all) {
            revoker.end(s).await().atMost(REVOKE_WAIT);
        }
        if (!all.isEmpty()) {
            LOG.debugf("Revoked %d AT Protocol session(s) of %s", all.size(), did);
            try {
                revoked.fire(new AtprotoSessionRevoked(did, "revoked by the application"));
            } catch (RuntimeException e) {
                LOG.warnf("An AtprotoSessionRevoked observer failed for %s: %s", did, e.toString());
            }
        }
    }

    private AtprotoSession require(Did did) {
        return find(did).orElseThrow(() -> new AtprotoSessionExpired("No AT Protocol session for " + did
                + "; the account has to sign in again"));
    }

    private DpopXrpcAuth auth(AtprotoSession s) {
        return new DpopXrpcAuth(s.id(), stores, refresher, nonces);
    }
}
