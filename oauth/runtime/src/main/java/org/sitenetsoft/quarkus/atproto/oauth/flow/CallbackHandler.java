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
import org.sitenetsoft.quarkus.atproto.oauth.link.AccountLinks;
import org.sitenetsoft.quarkus.atproto.oauth.link.AtprotoAccountLinks;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.PendingAuthorization;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionCookie;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionStores;

import io.quarkus.security.identity.IdentityProviderManager;
import io.smallrye.mutiny.Uni;
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
 * <p>
 * {@code link} mode: the request must also carry the application's signed-in identity, and it must be the user who
 * started the login (else 401 before the code is exchanged). The session is then stored and <b>linked</b> to that
 * user ({@link AtprotoAccountLinks}); an earlier session of the same link is deleted. No cookie of this extension is
 * set (only the binding cookie is cleared).
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
    private final AccountLinks links;
    private final IdentityProviderManager identities;

    public CallbackHandler(OAuthConfig config, SessionStores stores, TokenClient tokens, PostTokenChecks checks,
            NonceStore nonces, SessionCookie cookie, Event<AtprotoLoggedIn> loggedIn, AccountLinks links,
            IdentityProviderManager identities) {
        this.config = config;
        this.stores = stores;
        this.tokens = tokens;
        this.checks = checks;
        this.nonces = nonces;
        this.cookie = cookie;
        this.loggedIn = loggedIn;
        this.links = links;
        this.identities = identities;
    }

    private record Done(AtprotoSession session, String target) {
    }

    @Override
    public void handle(RoutingContext ctx) {
        Context context = ctx.vertx().getOrCreateContext();
        boolean link = config.mode() == OAuthConfig.Mode.LINK;
        Optional<String> previous = link ? Optional.empty() : cookie.sessionId(ctx.request());
        AtomicBoolean bound = new AtomicBoolean();
        Uni<Optional<String>> appPrincipal = link ? AppIdentity.principal(ctx, identities)
                : Uni.createFrom().item(Optional.empty());
        // the previous session is deleted only after a fully successful login
        complete(ctx, bound, appPrincipal)
                .chain(done -> previous.map(id -> stores.sessions().delete(id))
                        .orElse(Uni.createFrom().voidItem()).map(v -> done))
                .subscribe().with(done -> Responses.on(context, () -> {
                    cookie.clearBinding(ctx);
                    if (!link) {
                        cookie.set(ctx, done.session().id());
                    }
                    Responses.redirect(ctx, done.target());
                }), error -> Responses.on(context, () -> {
                    if (bound.get()) {
                        cookie.clearBinding(ctx);
                    }
                    Responses.fail(ctx, config, "login callback", error);
                }));
    }

    private Uni<Done> complete(RoutingContext ctx, AtomicBoolean bound, Uni<Optional<String>> appPrincipalUni) {
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
        return appPrincipalUni.chain(appPrincipal -> stores.takePending(state, binding.get()).chain(found -> {
            PendingAuthorization pending = found.orElseThrow(() -> new LoginFailure(401, "invalid_state",
                    "Unknown, used or expired login state, or a sign-in started in another browser: please sign in "
                            + "again"));
            if (!MessageDigest.isEqual(pending.browserBinding().getBytes(StandardCharsets.US_ASCII),
                    binding.get().getBytes(StandardCharsets.US_ASCII))) {
                throw new LoginFailure(401, "invalid_state", "This sign-in was not started in this browser");
            }
            bound.set(true);
            // link mode: the user who started the login completes it (before anything is exchanged)
            if (pending.appPrincipal().isPresent() && !pending.appPrincipal().equals(appPrincipal)) {
                throw new LoginFailure(401, "unauthenticated", appPrincipal.isEmpty()
                        ? "Sign in to the application to finish linking the account"
                        : "This account link was started by another application user");
            }
            if (pending.appPrincipal().isEmpty() && config.mode() == OAuthConfig.Mode.LINK) {
                throw new LoginFailure(401, "invalid_state", "This sign-in was not started as an account link");
            }
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
        }));
    }

    private Uni<AtprotoSession> store(PendingAuthorization pending, TokenResponse t, AtprotoIdentity identity) {
        Instant now = Instant.now();
        // the DID document's handle, verified both ways (handle.invalid otherwise, even if the user typed a handle)
        AtprotoSession session = new AtprotoSession(LoginHandler.random(32), identity.did(), identity.handle(),
                identity.pds(),
                pending.issuer(), t.scope().orElseThrow(), t.accessToken(), t.refreshToken(),
                now.plusSeconds(t.expiresIn()), pending.dpopPrivateJwk(),
                nonces.snapshot(URI.create(pending.issuer()), identity.pds()), now);
        Optional<String> principal = pending.appPrincipal();
        return stores.sessions().save(session)
                .chain(v -> principal.map(p -> link(p, session)).orElse(Uni.createFrom().voidItem()))
                // observers may block (worker thread); an observer's failure does not undo the login
                .chain(v -> EventFiring.fire(loggedIn, new AtprotoLoggedIn(session.did(), session.handle(), principal)))
                .map(v -> {
                    LOG.debugf("AT Protocol %s: %s%s", principal.isPresent() ? "account link" : "login", session,
                            principal.map(p -> " for application user " + p).orElse(""));
                    return session;
                });
    }

    /** Links the new session to the application user; the link's earlier session (if any) is deleted. */
    private Uni<Void> link(String appPrincipal, AtprotoSession session) {
        return links.links().sessionFor(appPrincipal, session.did())
                .chain(earlier -> links.links().link(appPrincipal, session.did(), session.id())
                        .chain(v -> earlier.filter(id -> !id.equals(session.id()))
                                .map(id -> stores.sessions().delete(id)).orElse(Uni.createFrom().voidItem())));
    }
}
