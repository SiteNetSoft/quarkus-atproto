package org.sitenetsoft.quarkus.atproto.oauth.flow;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.event.Event;
import jakarta.inject.Singleton;

import org.jboss.logging.Logger;
import org.sitenetsoft.multikey.Jwk;
import org.sitenetsoft.quarkus.atproto.identity.AtprotoIdentity;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;
import org.sitenetsoft.quarkus.atproto.oauth.events.AtprotoLoggedIn;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.PendingAuthorization;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionCookie;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionStores;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code GET {callback-path}?code&state&iss} (or {@code ?error&state&iss}):
 * <ol>
 * <li>the request must carry the login's browser-binding cookie; the pending entry is found by {@code state}
 * <b>and</b> that cookie's hash, so a callback URL opened in another browser (login CSRF) finds nothing, consumes
 * nothing and touches no session; the hash is then compared again in constant time and the cookie cleared;</li>
 * <li>{@code iss} must be the authorization server the login was sent to (checked before {@code error}, RFC 9207);
 * </li>
 * <li>an {@code error} ends the login;</li>
 * <li>the code is exchanged with the PKCE verifier and DPoP; the {@link PostTokenChecks} run;</li>
 * <li>the session is stored, a previous session of this browser deleted, the cookie set and the browser sent to the
 * login's target.</li>
 * </ol>
 * Every failure answers 401 (or the error page) with the reason and leaves no session.
 */
@Singleton
public class CallbackHandler implements Handler<RoutingContext> {

    private static final Logger LOG = Logger.getLogger(CallbackHandler.class);

    private final OAuthConfig config;
    private final SessionStores stores;
    private final TokenClient tokens;
    private final PostTokenChecks checks;
    private final NonceStore nonces;
    private final SessionCookie cookie;
    private final Event<AtprotoLoggedIn> loggedIn;

    public CallbackHandler(OAuthConfig config, SessionStores stores, TokenClient tokens, PostTokenChecks checks,
            NonceStore nonces, SessionCookie cookie, Event<AtprotoLoggedIn> loggedIn) {
        this.config = config;
        this.stores = stores;
        this.tokens = tokens;
        this.checks = checks;
        this.nonces = nonces;
        this.cookie = cookie;
        this.loggedIn = loggedIn;
    }

    private record Done(AtprotoSession session, String target) {
    }

    @Override
    public void handle(RoutingContext ctx) {
        Context context = ctx.vertx().getOrCreateContext();
        Optional<String> previous = cookie.sessionId(ctx.request());
        AtomicBoolean bound = new AtomicBoolean();
        // the previous session is deleted only after a fully successful login
        complete(ctx, bound).chain(done -> previous.map(id -> stores.sessions().delete(id))
                .orElse(Uni.createFrom().voidItem()).map(v -> done))
                .subscribe().with(done -> Responses.on(context, () -> {
                    cookie.clearBinding(ctx);
                    cookie.set(ctx, done.session().id());
                    Responses.redirect(ctx, done.target());
                }), error -> Responses.on(context, () -> {
                    if (bound.get()) {
                        cookie.clearBinding(ctx);
                    }
                    Responses.fail(ctx, config, "login callback", error);
                }));
    }

    private Uni<Done> complete(RoutingContext ctx, AtomicBoolean bound) {
        // read on the event loop, before any hop to another thread
        String state = ctx.queryParams().get("state");
        String error = ctx.queryParams().get("error");
        String description = ctx.queryParams().get("error_description");
        String iss = ctx.queryParams().get("iss");
        String code = ctx.queryParams().get("code");
        Optional<String> binding = cookie.bindingHash(ctx.request());
        if (state == null || state.isEmpty()) {
            return Uni.createFrom().failure(new LoginFailure(400, "invalid_request", "The callback has no state"));
        }
        if (binding.isEmpty()) {
            return Uni.createFrom().failure(new LoginFailure(401, "invalid_state",
                    "This sign-in was not started in this browser: please sign in again"));
        }
        return stores.takePending(state, binding.get()).chain(found -> {
            PendingAuthorization pending = found.orElseThrow(() -> new LoginFailure(401, "invalid_state",
                    "Unknown, used or expired login state, or a sign-in started in another browser: please sign in "
                            + "again"));
            if (!MessageDigest.isEqual(pending.browserBinding().getBytes(StandardCharsets.US_ASCII),
                    binding.get().getBytes(StandardCharsets.US_ASCII))) {
                throw new LoginFailure(401, "invalid_state", "This sign-in was not started in this browser");
            }
            bound.set(true);
            if (iss == null || !iss.equals(pending.issuer())) {
                throw new LoginFailure(401, "issuer_mismatch", "The callback's iss "
                        + (iss == null ? "is missing" : "'" + AuthServerClient.abbreviate(iss) + "'")
                        + " is not the authorization server the login was sent to (" + pending.issuer() + ")");
            }
            if (error != null) {
                throw new LoginFailure(401, AuthServerClient.abbreviate(error), "The sign-in was not completed: "
                        + AuthServerClient.abbreviate(error)
                        + (description == null ? "" : " (" + AuthServerClient.abbreviate(description) + ")"));
            }
            if (code == null || code.isEmpty()) {
                throw new LoginFailure(401, "invalid_request", "The callback has no code");
            }
            var dpopKey = new java.security.KeyPair(Jwk.publicKey(pending.dpopPrivateJwk()),
                    Jwk.privateKey(pending.dpopPrivateJwk()));
            return tokens.exchangeCode(pending.issuer(), pending.tokenEndpoint(), dpopKey, code, pending.codeVerifier())
                    .chain(t -> checks.check(t, pending.issuer(), pending.expectedDid())
                            .chain(identity -> store(pending, t, identity)))
                    .map(session -> new Done(session, pending.target()));
        });
    }

    private Uni<AtprotoSession> store(PendingAuthorization pending, TokenResponse t, AtprotoIdentity identity) {
        Instant now = Instant.now();
        // the DID document's handle, verified both ways (handle.invalid otherwise, even if the user typed a handle)
        AtprotoSession session = new AtprotoSession(LoginHandler.random(32), identity.did(), identity.handle(),
                identity.pds(),
                pending.issuer(), t.scope().orElseThrow(), t.accessToken(), t.refreshToken(),
                now.plusSeconds(t.expiresIn()), pending.dpopPrivateJwk(),
                nonces.snapshot(URI.create(pending.issuer()), identity.pds()), now);
        return stores.sessions().save(session).chain(v -> fireLoggedIn(session)).map(v -> {
            LOG.debugf("AT Protocol login: %s", session);
            return session;
        });
    }

    /** Observers may block: fired on a worker thread; an observer's failure does not undo the login. */
    private Uni<Void> fireLoggedIn(AtprotoSession session) {
        return Uni.createFrom().item(() -> {
            try {
                loggedIn.fire(new AtprotoLoggedIn(session.did(), session.handle(), Optional.empty()));
            } catch (RuntimeException e) {
                LOG.warnf("An AtprotoLoggedIn observer failed for %s: %s", session.did(), e.toString());
            }
            return (Void) null;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
