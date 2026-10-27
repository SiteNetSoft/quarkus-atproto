package org.sitenetsoft.quarkus.atproto.oauth.security;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.quarkus.atproto.oauth.auth.DpopXrpcAuth;
import org.sitenetsoft.quarkus.atproto.oauth.auth.ScopeWriteGuard;
import org.sitenetsoft.quarkus.atproto.oauth.flow.NonceStore;
import org.sitenetsoft.quarkus.atproto.oauth.session.Refresher;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionStores;
import org.sitenetsoft.quarkus.atproto.xrpc.AtprotoRecords;
import org.sitenetsoft.quarkus.atproto.xrpc.ReactiveAtprotoRecords;
import org.sitenetsoft.quarkus.atproto.xrpc.RecordsImpl;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcAuth;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcClient;

import io.quarkus.security.identity.SecurityIdentity;

/**
 * The signed-in AT Protocol account of the current request ({@code login} mode: the request's identity).
 * {@link #records()} writes to the account's repository on its PDS with DPoP, writes outside the granted scope
 * refused with {@code AtprotoScopeException} before anything is sent. Throws {@link IllegalStateException} when the
 * request has no AT Protocol identity.
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

    private DpopXrpcAuth auth;

    public Did did() {
        sessionId();
        return Did.parse(identity.getPrincipal().getName());
    }

    public Handle handle() {
        return Handle.parse(attribute(AtprotoIdentityProvider.HANDLE));
    }

    public URI pds() {
        return URI.create(attribute(AtprotoIdentityProvider.PDS));
    }

    /** The scope the authorization server granted (what the user consented to). */
    public String scope() {
        return attribute(AtprotoIdentityProvider.SCOPE);
    }

    /** The account's records, blocking (never on the event loop). */
    public AtprotoRecords records() {
        return records.blockingForRepo(did(), pds(), auth(), new ScopeWriteGuard(scope()));
    }

    public ReactiveAtprotoRecords reactiveRecords() {
        return records.forRepo(did(), pds(), auth(), new ScopeWriteGuard(scope()));
    }

    /**
     * An XRPC client whose calls without an explicit {@link XrpcAuth} are authorized with the session (DPoP); the
     * session's token is only sent to its PDS.
     */
    public XrpcClient xrpc() {
        XrpcAuth a = auth();
        XrpcClient d = xrpcClient;
        return new XrpcClient() {
            @Override
            public <T> T query(URI service, Nsid nsid, Map<String, ?> params, Class<T> out, Optional<XrpcAuth> auth) {
                return d.query(service, nsid, params, out, or(auth, a));
            }

            @Override
            public <T> T procedure(URI service, Nsid nsid, Object input, Class<T> out, Optional<XrpcAuth> auth) {
                return d.procedure(service, nsid, input, out, or(auth, a));
            }

            @Override
            public <T> T procedureBytes(URI service, Nsid nsid, byte[] body, String contentType, Class<T> out,
                    Optional<XrpcAuth> auth) {
                return d.procedureBytes(service, nsid, body, contentType, out, or(auth, a));
            }
        };
    }

    private static Optional<XrpcAuth> or(Optional<XrpcAuth> given, XrpcAuth session) {
        return given != null && given.isPresent() ? given : Optional.of(session);
    }

    private synchronized DpopXrpcAuth auth() {
        if (auth == null) {
            auth = new DpopXrpcAuth(sessionId(), pds(), stores, refresher, nonces);
        }
        return auth;
    }

    private String sessionId() {
        return attribute(AtprotoIdentityProvider.SESSION_ID);
    }

    private String attribute(String name) {
        Object v = identity.isAnonymous() ? null : identity.getAttribute(name);
        if (v == null) {
            throw new IllegalStateException("No AT Protocol account is signed in on this request");
        }
        return v.toString();
    }
}
