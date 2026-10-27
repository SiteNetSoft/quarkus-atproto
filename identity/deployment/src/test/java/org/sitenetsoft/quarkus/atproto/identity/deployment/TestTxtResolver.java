package org.sitenetsoft.quarkus.atproto.identity.deployment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.quarkus.atproto.identity.TxtResolver;

import io.smallrye.mutiny.Uni;

/**
 * Map-backed DNS: replaces the default {@code VertxTxtResolver} so tests never touch real DNS. Names not listed have no
 * TXT records.
 */
@ApplicationScoped
public class TestTxtResolver implements TxtResolver {

    static final Map<String, List<String>> RECORDS = Map.of(
            "_atproto.alice.example.test", List.of("v=spf1 -all", "did=" + FakeIdentityHost.ALICE),
            "_atproto.carol.example.test", List.of("did=" + FakeIdentityHost.CAROL),
            "_atproto.conflict.example.test", List.of("did=" + FakeIdentityHost.CONFLICT_1,
                    "did=" + FakeIdentityHost.CONFLICT_2),
            "_atproto.erin.example.test", List.of("did=" + FakeIdentityHost.ERIN),
            "_atproto.cached.example.test", List.of("did=" + FakeIdentityHost.CACHED));

    static final Set<String> SLOW = Set.of("_atproto.carol.example.test");

    private final Map<String, AtomicInteger> lookups = new ConcurrentHashMap<>();

    @Override
    public Uni<List<String>> txt(String name) {
        lookups.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
        Uni<List<String>> answer = Uni.createFrom().item(RECORDS.getOrDefault(name, List.of()));
        // carol's DNS answers after her well-known, so DNS winning there proves the HTTPS answer waited for it
        return SLOW.contains(name) ? answer.onItem().delayIt().by(Duration.ofMillis(300)) : answer;
    }

    /** How many times {@code name} was looked up. */
    public int lookups(String name) {
        AtomicInteger n = lookups.get(name);
        return n == null ? 0 : n.get();
    }
}
