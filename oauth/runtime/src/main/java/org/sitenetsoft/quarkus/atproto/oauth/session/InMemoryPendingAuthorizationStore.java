package org.sitenetsoft.quarkus.atproto.oauth.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.DefaultBean;

/** Default {@link PendingAuthorizationStore}: process memory, expired entries purged on every put. */
@DefaultBean
@ApplicationScoped
public class InMemoryPendingAuthorizationStore implements PendingAuthorizationStore {

    /** Upper bound on started-but-unfinished logins kept at once (abandoned logins expire anyway). */
    static final int MAX_ENTRIES = 100_000;

    private record Entry(PendingAuthorization value, Instant expires) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @PostConstruct
    void warnIfProduction() {
        ProductionWarning.warnIfProduction("PendingAuthorizationStore");
    }

    @Override
    public void put(String state, PendingAuthorization entry, Duration ttl) {
        Instant now = Instant.now();
        entries.values().removeIf(e -> !e.expires().isAfter(now));
        if (entries.size() >= MAX_ENTRIES) {
            throw new IllegalStateException("Too many logins in progress");
        }
        entries.put(state, new Entry(entry, now.plus(ttl)));
    }

    @Override
    public Optional<PendingAuthorization> take(String state) {
        if (state == null) {
            return Optional.empty();
        }
        Entry e = entries.remove(state);
        return e == null || !e.expires().isAfter(Instant.now()) ? Optional.empty() : Optional.of(e.value());
    }
}
