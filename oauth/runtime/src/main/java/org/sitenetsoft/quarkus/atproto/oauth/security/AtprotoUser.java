package org.sitenetsoft.quarkus.atproto.oauth.security;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.time.Instant;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoSessionExpired;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;
import org.sitenetsoft.quarkus.atproto.oauth.auth.DpopXrpcAuth;
import org.sitenetsoft.quarkus.atproto.oauth.auth.ScopeWriteGuard;
import org.sitenetsoft.quarkus.atproto.oauth.auth.SessionXrpc;
import org.sitenetsoft.quarkus.atproto.oauth.flow.NonceStore;
import org.sitenetsoft.quarkus.atproto.oauth.link.AccountLinks;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.Refresher;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionStores;
import org.sitenetsoft.quarkus.atproto.xrpc.AtprotoRecords;
import org.sitenetsoft.quarkus.atproto.xrpc.ReactiveAtprotoRecords;
import org.sitenetsoft.quarkus.atproto.xrpc.RecordsImpl;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcAuth;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcClient;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;

/**
 * The AT Protocol account of the current request. {@link #records()} writes to the account's repository on its PDS
 * with DPoP, writes outside the granted scope refused with {@code AtprotoScopeException} before anything is sent.
 * <ul>
 * <li>{@code login} mode: the request's identity (principal = the DID). {@link IllegalStateException} when the
 * request has no AT Protocol identity.</li>
 * <li>{@code link} mode: the account linked to the request's application user. {@link #did()} is the single linked
 * account: {@code IllegalStateException("no linked AT Protocol account")} when there is none,
 * {@code IllegalStateException("several linked accounts; use forDid")} when there are several; {@link #forDid}
 * picks one of them. When the link's session has ended (revoked, refresh refused) the account methods other than
 * {@link #did()} throw {@link AtprotoSessionExpired}: link the account again. Resolving the link reads the link and
 * session stores once per request: with an application store, on the event loop call {@link #resolve()} (or
 * {@link #didAsync()}, {@link #reactiveRecordsAsync()}) first; the synchronous accessors there fail fast with an
 * {@link IllegalStateException} instead of blocking. A session past {@code session.max-age} counts as ended.</li>
 * </ul>
 */
@RequestScoped
public class AtprotoUser {

    @Inject
    SecurityIdentity identity;
    @Inject
    RecordsImpl records;
    @Inject
    XrpcClient xrpcClient;
    @Inject
    SessionStores stores;
    @Inject
    Refresher refresher;
    @Inject
    NonceStore nonces;
    @Inject
    OAuthConfig config;
    @Inject
    AccountLinks links;

    /** {@code link} mode views made by {@link #forDid}: the chosen account. */
    private Did chosen;
    /** Per request, loaded once: the linked accounts, the account's session view or why there is none. */
    private volatile List<Did> linked;
    private volatile View view;
    private volatile AtprotoSessionExpired viewFailure;
    private volatile DpopXrpcAuth auth;

    /** The account's session as this request sees it. */
    private record View(String sessionId, Did did, Handle handle, URI pds, String scope) {
    }

    /**
     * Loads what this request needs from the link and session stores without blocking (through their reactive
     * views), and caches it for the request; afterwards every accessor answers from memory, so they are safe on the
     * event loop. Needed only in {@code link} mode with an application store, before using the synchronous accessors
     * on the event loop. Never fails for a missing or ended session (the accessors that need it throw
     * {@link AtprotoSessionExpired}).
     */
    public Uni<AtprotoUser> resolve() {
        return Uni.createFrom().deferred(() -> {
            if (!linkMode()) {
                view();
                return Uni.createFrom().item(this);
            }
            if (view != null || viewFailure != null) {
                return Uni.createFrom().item(this);
            }
            String principal = appPrincipal();
            Uni<List<Did>> accounts = linked != null ? Uni.createFrom().item(linked)
                    : links.links().findByPrincipal(principal).invoke(l -> linked = List.copyOf(l));
            return accounts.chain(l -> {
                Did target = chosen != null ? chosen : l.size() == 1 ? l.get(0) : null;
                if (target == null) {
                    return Uni.createFrom().item(this); // none or several: did() says which
                }
                return links.links().sessionFor(principal, target)
                        .chain(id -> id.isEmpty() ? Uni.createFrom().item(Optional.<AtprotoSession> empty())
                                : stores.sessions().find(id.get()))
                        .chain(found -> {
                            if (found.isPresent() && !live(found.get())) {
                                return stores.sessions().delete(found.get().id()).map(v -> Optional.<AtprotoSession> empty());
                            }
                            return Uni.createFrom().item(found);
                        })
                        .map(found -> {
                            setView(target, found);
                            return this;
                        });
            });
        });
    }

    /** {@link #did()} without blocking ({@link #resolve()} first). */
    public Uni<Did> didAsync() {
        return resolve().map(AtprotoUser::did);
    }

    /** {@link #reactiveRecords()} without blocking ({@link #resolve()} first). */
    public Uni<ReactiveAtprotoRecords> reactiveRecordsAsync() {
        return resolve().map(AtprotoUser::reactiveRecords);
    }

    public Did did() {
        if (!linkMode()) {
            return view().did();
        }
        if (chosen != null) {
            return chosen;
        }
        List<Did> accounts = linked();
        if (accounts.isEmpty()) {
            throw new IllegalStateException("no linked AT Protocol account");
        }
        if (accounts.size() > 1) {
            throw new IllegalStateException("several linked accounts; use forDid");
        }
        return accounts.get(0);
    }

    /**
     * This user's account {@code did}: in {@code link} mode one of the accounts linked to the application user
     * ({@link IllegalStateException} when it is not linked to them); in {@code login} mode the request's account
     * itself (anything else is refused).
     */
    public AtprotoUser forDid(Did did) {
        if (!linkMode()) {
            if (!did().equals(did)) {
                throw new IllegalStateException("This request is signed in as another AT Protocol account");
            }
            return this;
        }
        if (!linked().contains(did)) {
            throw new IllegalStateException("This AT Protocol account is not linked to the signed-in user");
        }
        AtprotoUser v = new AtprotoUser();
        v.identity = identity;
        v.records = records;
        v.xrpcClient = xrpcClient;
        v.stores = stores;
        v.refresher = refresher;
        v.nonces = nonces;
        v.config = config;
        v.links = links;
        v.chosen = did;
        v.linked = linked;
        return v;
    }

    public Handle handle() {
        return view().handle();
    }

    public URI pds() {
        return view().pds();
    }

    /** The scope the authorization server granted (what the user consented to). */
    public String scope() {
        return view().scope();
    }

    /** The account's records, blocking (never on the event loop). */
    public AtprotoRecords records() {
        View v = view();
        return records.blockingForRepo(v.did(), v.pds(), auth(), new ScopeWriteGuard(v.scope()));
    }

    public ReactiveAtprotoRecords reactiveRecords() {
        View v = view();
        return records.forRepo(v.did(), v.pds(), auth(), new ScopeWriteGuard(v.scope()));
    }

    /**
     * An XRPC client whose calls without an explicit {@link XrpcAuth} are authorized with the session (DPoP); the
     * session's token is only sent to its PDS.
     */
    public XrpcClient xrpc() {
        return SessionXrpc.client(xrpcClient, auth());
    }

    private DpopXrpcAuth auth() {
        DpopXrpcAuth a = auth;
        if (a == null) {
            View v = view();
            a = new DpopXrpcAuth(v.sessionId(), v.pds(), stores, refresher, nonces);
            auth = a;
        }
        return a;
    }

    private boolean linkMode() {
        return config.mode() == OAuthConfig.Mode.LINK;
    }

    private View view() {
        View v = view;
        if (v != null) {
            return v;
        }
        if (viewFailure != null) {
            throw viewFailure;
        }
        if (!linkMode()) {
            v = new View(attribute(AtprotoIdentityProvider.SESSION_ID), Did.parse(principal()),
                    Handle.parse(attribute(AtprotoIdentityProvider.HANDLE)),
                    URI.create(attribute(AtprotoIdentityProvider.PDS)), attribute(AtprotoIdentityProvider.SCOPE));
            view = v;
            return v;
        }
        Did did = did();
        guardEventLoop(links.inline() && stores.sessionsInline());
        Optional<AtprotoSession> found = links.blockingLinks().sessionFor(appPrincipal(), did)
                .flatMap(id -> stores.blockingSessions().find(id));
        if (found.isPresent() && !live(found.get())) {
            stores.blockingSessions().delete(found.get().id());
            found = Optional.empty();
        }
        setView(did, found);
        return view();
    }

    private void setView(Did did, Optional<AtprotoSession> session) {
        if (session.isPresent()) {
            AtprotoSession s = session.get();
            view = new View(s.id(), s.did(), s.handle(), s.pds(), s.grantedScope());
        } else {
            viewFailure = new AtprotoSessionExpired("The AT Protocol session of " + did
                    + " has ended; link the account again");
        }
    }

    /** Within {@code session.max-age} (as the identity provider and {@code AtprotoSessions} judge it). */
    private boolean live(AtprotoSession s) {
        return s.created().plus(config.session().maxAge()).isAfter(Instant.now());
    }

    private List<Did> linked() {
        List<Did> l = linked;
        if (l == null) {
            guardEventLoop(links.inline());
            l = List.copyOf(links.blockingLinks().findByPrincipal(appPrincipal()));
            linked = l;
        }
        return l;
    }

    /** An application store must not be read synchronously on the event loop: fail fast with the way out. */
    private static void guardEventLoop(boolean inlineStores) {
        if (!inlineStores && Context.isOnEventLoopThread()) {
            throw new IllegalStateException("AtprotoUser in link mode would read the application's link or session "
                    + "store on the event loop: call resolve(), didAsync() or reactiveRecordsAsync() first, or run "
                    + "the endpoint on a worker thread (@Blocking)");
        }
    }

    private String appPrincipal() {
        if (identity.isAnonymous() || identity.getPrincipal() == null) {
            throw new IllegalStateException("No application user is signed in on this request");
        }
        return identity.getPrincipal().getName();
    }

    private String principal() {
        attribute(AtprotoIdentityProvider.SESSION_ID); // an AT Protocol identity, not another mechanism's
        return identity.getPrincipal().getName();
    }

    private String attribute(String name) {
        Object v = identity.isAnonymous() ? null : identity.getAttribute(name);
        if (v == null) {
            throw new IllegalStateException("No AT Protocol account is signed in on this request");
        }
        return v.toString();
    }
}
