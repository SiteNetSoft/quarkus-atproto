package org.sitenetsoft.quarkus.atproto.oauth.flow;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.quarkus.atproto.oauth.Origins;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * The last DPoP nonce each server (authorization server or PDS, by origin) sent in {@code DPoP-Nonce}. Nonces are
 * per server, never shared between servers: a PDS proof never carries the authorization server's nonce. A stale
 * nonce costs one retry ({@code use_dpop_nonce}).
 */
@ApplicationScoped
public class NonceStore {

    /** Longest nonce accepted from a server. */
    static final int MAX_NONCE_LENGTH = 512;

    private final Cache<String, String> nonces = Caffeine.newBuilder().maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10)).build();

    /** The nonce last seen from {@code url}'s origin. */
    public Optional<String> get(URI url) {
        return Optional.ofNullable(nonces.getIfPresent(Origins.of(url)));
    }

    /** Remembers {@code nonce} for {@code url}'s origin (ignored when absent or implausible). */
    public void put(URI url, String nonce) {
        if (nonce == null || nonce.isEmpty() || nonce.length() > MAX_NONCE_LENGTH
                || !nonce.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
            return;
        }
        nonces.put(Origins.of(url), nonce);
    }

    /** The current nonces of {@code servers}, by origin (to keep with a session). */
    public Map<String, String> snapshot(URI... servers) {
        Map<String, String> out = new LinkedHashMap<>();
        for (URI s : servers) {
            get(s).ifPresent(n -> out.put(Origins.of(s), n));
        }
        return out;
    }
}
