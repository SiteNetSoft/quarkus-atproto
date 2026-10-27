package org.sitenetsoft.quarkus.atproto.oauth.session;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;

import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoSessionExpired;
import org.sitenetsoft.quarkus.atproto.oauth.discovery.Discovery;
import org.sitenetsoft.quarkus.atproto.oauth.events.AtprotoSessionRevoked;
import org.sitenetsoft.quarkus.atproto.oauth.flow.NonceStore;
import org.sitenetsoft.quarkus.atproto.oauth.flow.OAuthServerError;
import org.sitenetsoft.quarkus.atproto.oauth.flow.TokenClient;
import org.sitenetsoft.quarkus.atproto.oauth.flow.TokenResponse;

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
 * <li><b>across nodes</b> the new tokens are stored with {@link AtprotoSessionStore#replaceIfRefreshToken}
 * (compare-and-set on the refresh token that was spent); the loser re-reads and uses the winner's session.</li>
 * </ul>
 * {@code invalid_grant} (revoked, replayed or expired grant) deletes the session, fires {@link AtprotoSessionRevoked}
 * and fails with {@link AtprotoSessionExpired}, unless the store shows another node refreshed in the meantime.
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
    private final Map<String, CompletableFuture<AtprotoSession>> inFlight = new ConcurrentHashMap<>();

    public Refresher(SessionStores stores, TokenClient tokens, Discovery discovery, NonceStore nonces,
            Event<AtprotoSessionRevoked> revoked) {
        this.stores = stores;
        this.tokens = tokens;
        this.discovery = discovery;
        this.nonces = nonces;
        this.revoked = revoked;
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
            if (!s.accessToken().equals(staleAccessToken)
                    && !s.accessExpiresWithin(Instant.now(), EXPIRY_SKEW_SECONDS)) {
                return Uni.createFrom().item(s);
            }
            return discovery.authorizationServer(URI.create(s.issuer()))
                    .chain(as -> tokens.refresh(s.issuer(), as.tokenEndpoint(), s.dpopKey(), s.refreshToken()))
                    .onFailure(e -> e instanceof OAuthServerError o && "invalid_grant".equals(o.error()))
                    .recoverWithUni(e -> refused(s, (OAuthServerError) e))
                    .chain(t -> t == null ? find(sessionId) : store(s, t));
        });
    }

    private Uni<AtprotoSession> store(AtprotoSession s, TokenResponse t) {
        if (t.sub().isPresent() && !t.sub().get().equals(s.did().value())) {
            return Uni.createFrom().failure(new AtprotoSessionExpired(
                    "The refreshed token is for another account than the session's; sign in again"));
        }
        String scope = t.scope().orElse(s.grantedScope());
        if (Arrays.stream(scope.trim().split("\\s+")).noneMatch("atproto"::equals)) {
            return Uni.createFrom().failure(new AtprotoSessionExpired(
                    "The refreshed token's scope lacks atproto; sign in again"));
        }
        Map<String, String> n = new java.util.LinkedHashMap<>(s.nonces());
        n.putAll(nonces.snapshot(URI.create(s.issuer()), s.pds()));
        AtprotoSession updated = s.withTokens(t.accessToken(), t.refreshToken(),
                Instant.now().plusSeconds(t.expiresIn()), scope, n);
        return stores.sessions().replaceIfRefreshToken(updated, s.refreshToken()).chain(replaced -> {
            if (replaced) {
                LOG.debugf("Refreshed the access token of %s", s.did());
                return Uni.createFrom().item(updated);
            }
            LOG.debugf("Another refresh of %s's session won the compare-and-set; using the stored session", s.did());
            return find(s.id());
        });
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
            return stores.sessions().delete(s.id()).chain(v -> Uni.createFrom().item(() -> {
                try {
                    revoked.fire(new AtprotoSessionRevoked(s.did(), "refresh refused: " + e.error()));
                } catch (RuntimeException ex) {
                    LOG.warnf("An AtprotoSessionRevoked observer failed for %s: %s", s.did(), ex.toString());
                }
                return (Void) null;
            }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())).chain(v -> Uni.createFrom().failure(
                    new AtprotoSessionExpired("The AT Protocol session of " + s.did() + " ended (" + e.error()
                            + "); sign in again")));
        });
    }

    private Uni<AtprotoSession> find(String sessionId) {
        return stores.sessions().find(sessionId).map(s -> s.orElseThrow(
                () -> new AtprotoSessionExpired("The AT Protocol session is gone; sign in again")));
    }
}
