package org.sitenetsoft.quarkus.atproto.oauth.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import io.quarkus.arc.DefaultBean;

/**
 * Default {@link PendingAuthorizationStore}: a bounded Caffeine cache in process memory. Entries expire after their
 * TTL; when the bound is reached the least recently used started logins are evicted (that login then fails at its
 * callback) instead of refusing new logins.
 */
@DefaultBean
@ApplicationScoped
public class InMemoryPendingAuthorizationStore implements PendingAuthorizationStore {

    /** Upper bound on started-but-unfinished logins kept at once. */
    static final long MAX_ENTRIES = 100_000;

    private record Entry(PendingAuthorization value, Instant expires, long ttlNanos) {
    }

    private final Cache<String, Entry> entries;

    public InMemoryPendingAuthorizationStore() {
        this(MAX_ENTRIES);
    }

    InMemoryPendingAuthorizationStore(long maxEntries) {
        this.entries = Caffeine.newBuilder().maximumSize(maxEntries).executor(Runnable::run)
                .expireAfter(new Expiry<String, Entry>() {
                    @Override
                    public long expireAfterCreate(String key, Entry value, long currentTime) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, Entry value, long currentTime, long currentDuration) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, Entry value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                }).build();
    }

    @PostConstruct
    void warnIfProduction() {
        ProductionWarning.warnIfProduction("PendingAuthorizationStore");
    }

    @Override
    public void put(String state, PendingAuthorization entry, Duration ttl) {
        long nanos = Math.max(0, ttl.toNanos());
        entries.put(state, new Entry(entry, Instant.now().plus(ttl), nanos));
    }

    @Override
    public Optional<PendingAuthorization> take(String state) {
        if (state == null) {
            return Optional.empty();
        }
        Entry e = entries.asMap().remove(state);
        return e == null || !e.expires().isAfter(Instant.now()) ? Optional.empty() : Optional.of(e.value());
    }

    /** Entries currently held (after pending maintenance). */
    long size() {
        entries.cleanUp();
        return entries.estimatedSize();
    }
}
