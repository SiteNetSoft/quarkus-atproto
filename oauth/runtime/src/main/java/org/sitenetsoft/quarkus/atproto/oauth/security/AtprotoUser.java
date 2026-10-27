package org.sitenetsoft.quarkus.atproto.oauth.security;

import java.net.URI;
import java.util.List;
import java.util.Optional;

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
 * session stores (blocking for an application store: call from a worker thread).</li>
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
    private View view;
    private DpopXrpcAuth auth;

    /** The account's session as this request sees it. */
    private record View(String sessionId, Did did, Handle handle, URI pds, String scope) {
    }

    public Did did() {
        if (!linkMode()) {
            return view().did();
        }
        if (chosen != null) {
            return chosen;
        }
        List<Did> linked = linked();
        if (linked.isEmpty()) {
            throw new IllegalStateException("no linked AT Protocol account");
        }
        if (linked.size() > 1) {
            throw new IllegalStateException("several linked accounts; use forDid");
        }
        return linked.get(0);
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

    private synchronized DpopXrpcAuth auth() {
        if (auth == null) {
            View v = view();
            auth = new DpopXrpcAuth(v.sessionId(), v.pds(), stores, refresher, nonces);
        }
        return auth;
    }

    private boolean linkMode() {
        return config.mode() == OAuthConfig.Mode.LINK;
    }

    private synchronized View view() {
        if (view == null) {
            view = linkMode() ? linkedView() : new View(attribute(AtprotoIdentityProvider.SESSION_ID),
                    Did.parse(principal()), Handle.parse(attribute(AtprotoIdentityProvider.HANDLE)),
                    URI.create(attribute(AtprotoIdentityProvider.PDS)), attribute(AtprotoIdentityProvider.SCOPE));
        }
        return view;
    }

    private View linkedView() {
        Did did = did();
        Optional<AtprotoSession> session = links.blockingLinks().sessionFor(appPrincipal(), did)
                .flatMap(id -> stores.blockingSessions().find(id));
        AtprotoSession s = session.orElseThrow(() -> new AtprotoSessionExpired("The AT Protocol session of " + did
                + " has ended; link the account again"));
        return new View(s.id(), s.did(), s.handle(), s.pds(), s.grantedScope());
    }

    private List<Did> linked() {
        return links.blockingLinks().findByPrincipal(appPrincipal());
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
