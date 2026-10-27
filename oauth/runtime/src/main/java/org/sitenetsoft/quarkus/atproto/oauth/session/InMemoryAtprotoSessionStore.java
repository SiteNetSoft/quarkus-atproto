package org.sitenetsoft.quarkus.atproto.oauth.session;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;

import io.quarkus.arc.DefaultBean;

/**
 * Default {@link AtprotoSessionStore}: process memory, lost on restart, one node only. WARNs once in production.
 * A session is dropped when it reaches {@code session.max-age} (counted from its creation), whether or not it is used
 * again, so abandoned sessions (tokens, DPoP keys) do not pile up.
 */
@DefaultBean
@ApplicationScoped
public class InMemoryAtprotoSessionStore implements AtprotoSessionStore {

    /** {@code quarkus.atproto.oauth.session.max-age}'s default. */
    private static final Duration DEFAULT_MAX_AGE = Duration.ofDays(14);

    private final Cache<String, AtprotoSession> sessions;

    /** A store whose sessions last the default max-age (14 days). */
    public InMemoryAtprotoSessionStore() {
        this(DEFAULT_MAX_AGE, Ticker.systemTicker(), Scheduler.systemScheduler());
    }

    @Inject
    public InMemoryAtprotoSessionStore(OAuthConfig config) {
        this(config.session().maxAge(), Ticker.systemTicker(), Scheduler.systemScheduler());
    }

    /** A store expiring sessions {@code maxAge} after their creation, on {@code ticker}'s clock (tests). */
    InMemoryAtprotoSessionStore(Duration maxAge, Ticker ticker, Scheduler scheduler) {
        this.sessions = Caffeine.newBuilder().ticker(ticker).scheduler(scheduler)
                .expireAfter(new Expiry<String, AtprotoSession>() {
                    @Override
                    public long expireAfterCreate(String id, AtprotoSession s, long now) {
                        return remaining(s, maxAge);
                    }

                    @Override
                    public long expireAfterUpdate(String id, AtprotoSession s, long now, long current) {
                        return remaining(s, maxAge);
                    }

                    @Override
                    public long expireAfterRead(String id, AtprotoSession s, long now, long current) {
                        return current;
                    }
                }).build();
    }

    /** Nanoseconds until {@code s} is {@code maxAge} old (at least 0). */
    private static long remaining(AtprotoSession s, Duration maxAge) {
        Duration left = Duration.between(Instant.now(), s.created().plus(maxAge));
        return left.isNegative() ? 0 : saturatedNanos(left);
    }

    private static long saturatedNanos(Duration d) {
        try {
            return d.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    @PostConstruct
    void warnIfProduction() {
        ProductionWarning.warnIfProduction("AtprotoSessionStore");
    }

    @Override
    public void save(AtprotoSession session) {
        sessions.put(session.id(), session);
    }

    @Override
    public Optional<AtprotoSession> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(sessions.getIfPresent(id));
    }

    @Override
    public List<AtprotoSession> findByDid(Did did) {
        return sessions.asMap().values().stream().filter(s -> s.did().equals(did)).toList();
    }

    @Override
    public boolean replaceIfRefreshToken(AtprotoSession updated, String expectedRefreshToken) {
        boolean[] replaced = {false};
        sessions.asMap().computeIfPresent(updated.id(), (id, current) -> {
            if (current.refreshToken().equals(expectedRefreshToken)) {
                replaced[0] = true;
                return updated;
            }
            return current;
        });
        return replaced[0];
    }

    /** Every stored session (for the Dev UI; not part of the store SPI). */
    public List<AtprotoSession> all() {
        return List.copyOf(sessions.asMap().values());
    }

    /** Drops expired entries now (tests; Caffeine also does it on its own). */
    void cleanUp() {
        sessions.cleanUp();
    }

    /** Entries held, expired ones not yet dropped included (tests). */
    long size() {
        return sessions.estimatedSize();
    }

    @Override
    public void delete(String id) {
        sessions.invalidate(id);
    }
}
