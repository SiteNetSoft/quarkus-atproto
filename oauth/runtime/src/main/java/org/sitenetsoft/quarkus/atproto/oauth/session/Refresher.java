package org.sitenetsoft.quarkus.atproto.oauth.session;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;

import org.jboss.logging.Logger;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;
import org.sitenetsoft.quarkus.atproto.identity.AtprotoIdentity;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoSessionExpired;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoTemporarilyUnavailable;
import org.sitenetsoft.quarkus.did.DidDeactivated;
import org.sitenetsoft.quarkus.did.DidNotFound;
import org.sitenetsoft.quarkus.atproto.oauth.discovery.Discovery;
import org.sitenetsoft.quarkus.atproto.oauth.events.AtprotoSessionRevoked;
import org.sitenetsoft.quarkus.atproto.oauth.flow.NonceStore;
import org.sitenetsoft.quarkus.atproto.oauth.flow.LoginFailure;
import org.sitenetsoft.quarkus.atproto.oauth.flow.OAuthServerError;
import org.sitenetsoft.quarkus.atproto.oauth.flow.PostTokenChecks;
import org.sitenetsoft.quarkus.atproto.oauth.flow.TokenClient;
import org.sitenetsoft.quarkus.atproto.oauth.flow.TokenResponse;
import org.sitenetsoft.quarkus.safefetch.SafeFetchException;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

/**
 * Refreshes a session's access token. Refresh tokens are single use, so:
 * <ul>
 * <li><b>in this process</b> one refresh per session runs at a time: concurrent callers join the running refresh
 * (a map of in-flight futures keyed by session id, no thread blocks);</li>
 * <li>a refresh first <b>re-reads</b> the stored session: when its access token is no longer the one the caller found
 * stale (someone refreshed meanwhile) and is not about to expire, that session is the answer and no refresh token is
 * spent;</li>
 * <li><b>across nodes</b> only the holder of the store's refresh lease
 * ({@link AtprotoSessionStore#tryAcquireRefreshLease}, keyed by the session and its refresh token's hash, for
 * {@code session.refresh-lease}) spends the refresh token; a node that does not get the lease polls the store for
 * the holder's new tokens and uses them, or fails with {@link AtprotoTemporarilyUnavailable} when none arrive within
 * the lease. After taking the lease the session is read again (another node may have just finished). The new tokens
 * are stored with {@link AtprotoSessionStore#replaceIfRefreshToken} (compare-and-set on the spent refresh token),
 * which decides whose tokens are kept. The default lease always succeeds (one node): a store shared by several nodes
 * must implement it, or two nodes may spend the same refresh token and the authorization server revokes the
 * grant.</li>
 * </ul>
 * {@code invalid_grant} (revoked, replayed or expired grant) deletes the session, fires {@link AtprotoSessionRevoked}
 * and fails with {@link AtprotoSessionExpired}, unless the store shows another node refreshed in the meantime. A token
 * endpoint that answers 5xx, times out or cannot be reached fails the call with {@link AtprotoTemporarilyUnavailable}
 * and keeps the session (other errors end it or surface as documented).
 * <p>
 * Every refresh repeats the login's mandatory checks:
 * <ol>
 * <li><b>before</b> the refresh token is spent, {@link PostTokenChecks#authority}: the session DID → DID document →
 * current PDS → its authorization server = the session's issuer. A transient failure (directory or metadata outage)
 * fails the call with {@link AtprotoTemporarilyUnavailable} and leaves the session and its refresh token untouched; a
 * definitive one (DID not found or deactivated, the PDS now protected by another authorization server) deletes the
 * session and fails with {@link AtprotoSessionExpired} without calling the token endpoint;</li>
 * <li>after the response, {@code sub} must be present and the session's DID and the scope must contain
 * {@code atproto}; otherwise nothing is stored and (the old refresh token being spent) the session ends.</li>
 * </ol>
 * The session's PDS and handle come from the pre-check's DID document, so an account that moved PDS under the same
 * authorization server keeps working. The stored handle is replaced by a verified one, or by {@code handle.invalid}
 * when the handle definitively no longer resolves back; a transient failure of that check keeps the stored handle.
 */
@ApplicationScoped
public class Refresher {

    private static final Logger LOG = Logger.getLogger(Refresher.class);
    /** An access token this close to expiry is refreshed before use. */
    public static final long EXPIRY_SKEW_SECONDS = 10;

    private final SessionStores stores;
    private final TokenClient tokens;
    private final Discovery discovery;
    private final NonceStore nonces;
    private final Event<AtprotoSessionRevoked> revoked;
    private final PostTokenChecks checks;
    private final OAuthConfig config;
    private final Map<String, CompletableFuture<AtprotoSession>> inFlight = new ConcurrentHashMap<>();

    public Refresher(SessionStores stores, TokenClient tokens, Discovery discovery, NonceStore nonces,
            Event<AtprotoSessionRevoked> revoked, PostTokenChecks checks, OAuthConfig config) {
        this.config = config;
        this.stores = stores;
        this.tokens = tokens;
        this.discovery = discovery;
        this.nonces = nonces;
        this.revoked = revoked;
        this.checks = checks;
    }

    /**
     * A session whose access token is not {@code staleAccessToken} (unless that one is still fresh): the stored one if
     * someone refreshed already, else a refreshed one.
     *
     * @throws AtprotoSessionExpired (in the {@link Uni}) when the session is gone or its grant was refused
     */
    public Uni<AtprotoSession> refresh(String sessionId, String staleAccessToken) {
        return Uni.createFrom().deferred(() -> {
            CompletableFuture<AtprotoSession> mine = new CompletableFuture<>();
            CompletableFuture<AtprotoSession> running = inFlight.putIfAbsent(sessionId, mine);
            if (running == null) {
                run(sessionId, staleAccessToken).subscribe().with(s -> {
                    inFlight.remove(sessionId, mine);
                    mine.complete(s);
                }, e -> {
                    inFlight.remove(sessionId, mine);
                    mine.completeExceptionally(e);
                });
                running = mine;
            }
            return Uni.createFrom().completionStage(running);
        }).onFailure(CompletionException.class).transform(e -> e.getCause() == null ? e : e.getCause());
    }

    private Uni<AtprotoSession> run(String sessionId, String staleAccessToken) {
        return find(sessionId).chain(s -> {
            if (alreadyRefreshed(s, staleAccessToken)) {
                return Uni.createFrom().item(s);
            }
            String lease = hash(s.refreshToken());
            Duration ttl = config.session().refreshLease();
            return stores.sessions().tryAcquireRefreshLease(s.id(), lease, ttl)
                    .onFailure().transform(e -> unavailable(s, "the session store's refresh lease could not be taken",
                            e))
                    .chain(acquired -> {
                        if (!acquired) {
                            LOG.debugf("Another node is refreshing %s's session; waiting for its tokens", s.did());
                            return awaitOtherNode(s, Instant.now().plus(ttl), pollInterval(ttl));
                        }
                        // another node may have finished between our read and our lease: never spend a used token
                        return find(sessionId).chain(current -> current.refreshToken().equals(s.refreshToken())
                                ? spend(current) : Uni.createFrom().item(current))
                                .eventually(() -> stores.sessions().releaseRefreshLease(s.id(), lease)
                                        .onFailure().recoverWithNull());
                    });
        });
    }

    /** Someone refreshed since the caller found {@code staleAccessToken}, and the stored token is not about to expire. */
    private static boolean alreadyRefreshed(AtprotoSession s, String staleAccessToken) {
        return !s.accessToken().equals(staleAccessToken) && !s.accessExpiresWithin(Instant.now(), EXPIRY_SKEW_SECONDS);
    }

    /** This node holds the lease: the pre-check, then the refresh token is spent. */
    private Uni<AtprotoSession> spend(AtprotoSession s) {
        // the issuer's authority over the account's current PDS, before the refresh token is spent
        return checks.authority(s.did(), s.issuer())
                .onFailure(e -> !definitive(e)).transform(e -> unavailable(s, "the account's PDS and its "
                        + "authorization server could not be checked", e))
                .onFailure(Refresher::definitive).recoverWithUni(e -> end(s, reason(e)))
                .chain(identity -> discovery.authorizationServer(URI.create(s.issuer()))
                        .onFailure().transform(e -> unavailable(s, "the authorization server's metadata could "
                                + "not be fetched", e))
                        .chain(as -> tokens.refresh(s.issuer(), as.tokenEndpoint(), s.dpopKey(), s.refreshToken())
                                .onFailure(Refresher::transientTokenFailure).transform(e -> unavailable(s,
                                        "the authorization server's token endpoint failed or could not be reached",
                                        e)))
                        .onFailure(e -> e instanceof OAuthServerError o && "invalid_grant".equals(o.error()))
                        .recoverWithUni(e -> refused(s, (OAuthServerError) e))
                        .chain(t -> t == null ? find(s.id()) : store(s, t, identity)));
    }

    /**
     * Another node holds the refresh lease: poll the store until its new tokens are there (a changed refresh token),
     * the session is gone, or {@code deadline} passes ({@link AtprotoTemporarilyUnavailable}; nothing spent here).
     */
    private Uni<AtprotoSession> awaitOtherNode(AtprotoSession s, Instant deadline, Duration poll) {
        return Uni.createFrom().voidItem().onItem().delayIt().by(poll)
                .chain(v -> stores.sessions().find(s.id()))
                .onFailure().transform(e -> unavailable(s, "the session store could not be read", e))
                .chain(current -> {
                    if (current.isEmpty()) {
                        return Uni.createFrom().failure(new AtprotoSessionExpired(
                                "The AT Protocol session is gone; sign in again"));
                    }
                    if (!current.get().refreshToken().equals(s.refreshToken())) {
                        LOG.debugf("Using the tokens another node refreshed for %s", s.did());
                        return Uni.createFrom().item(current.get());
                    }
                    if (!Instant.now().isBefore(deadline)) {
                        return Uni.createFrom().failure(unavailable(s, "another node is refreshing this session and "
                                + "has not stored new tokens in time", null));
                    }
                    return awaitOtherNode(s, deadline, poll);
                });
    }

    private static Duration pollInterval(Duration lease) {
        return Duration.ofMillis(Math.max(50, Math.min(500, lease.toMillis() / 20)));
    }

    /** base64url SHA-256 of a refresh token: the lease key (the token itself never leaves for the lease). */
    static String hash(String token) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A token-endpoint failure worth retrying later, the refresh token presumably unspent: a 5xx answer, a timeout or
     * a transport failure. Other answers (4xx) are not.
     */
    static boolean transientTokenFailure(Throwable e) {
        if (e instanceof OAuthServerError o) {
            return o.status() >= 500;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SafeFetchException || t instanceof java.util.concurrent.TimeoutException
                    || t instanceof io.smallrye.mutiny.TimeoutException || t instanceof java.io.IOException) {
                return true;
            }
        }
        return false;
    }

    /**
     * A pre-check failure that will not go away by retrying: the DID is gone or deactivated, or its PDS is now
     * protected by another authorization server. Anything else (directory or metadata outages, 5xx, timeouts) is
     * transient.
     */
    static boolean definitive(Throwable e) {
        if (e instanceof PostTokenChecks.IssuerMismatch) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DidNotFound || t instanceof DidDeactivated) {
                return true;
            }
        }
        return false;
    }

    private static AtprotoTemporarilyUnavailable unavailable(AtprotoSession s, String what, Throwable e) {
        LOG.infof("Not refreshing %s's session now: %s%s; the session is kept", s.did(), what,
                e == null ? "" : " (" + e.getClass().getSimpleName() + ")");
        return new AtprotoTemporarilyUnavailable("The AT Protocol session of " + s.did() + " cannot be refreshed right "
                + "now: " + what + "; try again later", e);
    }

    private Uni<AtprotoSession> store(AtprotoSession s, TokenResponse t, AtprotoIdentity identity) {
        if (t.sub().isEmpty()) {
            return end(s, "the refresh response has no sub");
        }
        if (!t.sub().get().equals(s.did().value())) {
            return end(s, "sub mismatch: the refreshed tokens are for another account");
        }
        String scope = t.scope().orElse(s.grantedScope());
        if (java.util.Arrays.stream(scope.trim().split("\\s+")).noneMatch("atproto"::equals)) {
            return end(s, "the refreshed scope does not contain atproto");
        }
        Map<String, String> n = new java.util.LinkedHashMap<>(s.nonces());
        n.putAll(nonces.snapshot(URI.create(s.issuer()), identity.pds()));
        // a transient failure of the handle's reverse check keeps the handle we had
        Handle handle = identity.handleVerified() || !identity.handleCheckFailed() ? identity.handle() : s.handle();
        AtprotoSession updated = new AtprotoSession(s.id(), s.did(), handle, identity.pds(), s.issuer(),
                scope, t.accessToken(), t.refreshToken(), Instant.now().plusSeconds(t.expiresIn()),
                s.dpopPrivateJwk(), n, s.created());
        if (!identity.pds().equals(s.pds())) {
            LOG.infof("%s moved from PDS %s to %s; the session follows", s.did(), s.pds(), identity.pds());
        }
        return replace(s, updated);
    }

    private Uni<AtprotoSession> replace(AtprotoSession s, AtprotoSession updated) {
        return stores.sessions().replaceIfRefreshToken(updated, s.refreshToken()).chain(replaced -> {
            if (replaced) {
                LOG.debugf("Refreshed the access token of %s", s.did());
                return Uni.createFrom().item(updated);
            }
            LOG.debugf("Another refresh of %s's session won the compare-and-set; using the stored session", s.did());
            return find(s.id());
        });
    }

    private static String reason(Throwable e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return e instanceof LoginFailure && m.startsWith("Login refused: ") ? m.substring("Login refused: ".length())
                : m;
    }

    /** A refresh response that failed the checks: nothing stored, the session (its refresh token spent) ends. */
    private <T> Uni<T> end(AtprotoSession s, String reason) {
        LOG.infof("The refreshed tokens of %s's session failed a check (%s); the session ends", s.did(), reason);
        return stores.sessions().delete(s.id()).chain(v -> fireRevoked(s, "refresh check failed: " + reason))
                .chain(v -> Uni.createFrom().failure(new AtprotoSessionExpired("The AT Protocol session of " + s.did()
                        + " ended: " + reason + "; sign in again")));
    }

    private Uni<Void> fireRevoked(AtprotoSession s, String reason) {
        return Uni.createFrom().item(() -> {
            try {
                revoked.fire(new AtprotoSessionRevoked(s.did(), reason));
            } catch (RuntimeException ex) {
                LOG.warnf("An AtprotoSessionRevoked observer failed for %s: %s", s.did(), ex.toString());
            }
            return (Void) null;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /**
     * {@code invalid_grant}: if the stored refresh token changed meanwhile, another node refreshed (the item is
     * {@code null}: re-read); otherwise the session is over.
     */
    private Uni<TokenResponse> refused(AtprotoSession s, OAuthServerError e) {
        return stores.sessions().find(s.id()).chain(current -> {
            if (current.isPresent() && !current.get().refreshToken().equals(s.refreshToken())) {
                return Uni.createFrom().nullItem();
            }
            LOG.infof("The authorization server refused the refresh of %s's session (%s); the session ends", s.did(),
                    e.error());
            return stores.sessions().delete(s.id()).chain(v -> fireRevoked(s, "refresh refused: " + e.error()))
                    .chain(v -> Uni.createFrom().failure(
                    new AtprotoSessionExpired("The AT Protocol session of " + s.did() + " ended (" + e.error()
                            + "); sign in again")));
        });
    }

    private Uni<AtprotoSession> find(String sessionId) {
        return stores.sessions().find(sessionId).map(s -> s.orElseThrow(
                () -> new AtprotoSessionExpired("The AT Protocol session is gone; sign in again")));
    }
}
