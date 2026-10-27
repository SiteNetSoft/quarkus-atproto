package org.sitenetsoft.quarkus.atproto.oauth.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.multikey.Jwk;
import org.sitenetsoft.multikey.KeyPairs;
import org.sitenetsoft.multikey.KeyType;

class SessionsTest {

    static final Did DID = Did.parse("did:plc:abcdefghijklmnopqrstuvwx");

    static AtprotoSession session(String id, String refresh) {
        var key = KeyPairs.generate(KeyType.P256);
        return new AtprotoSession(id, DID, Handle.parse("alice.example.com"), URI.create("https://pds.example.com"),
                "https://as.example.com", "atproto", "secret-access-token", refresh, Instant.now().plusSeconds(60),
                Jwk.ofPrivate(key.getPrivate(), key.getPublic()), Map.of("https://pds.example.com", "n1"),
                Instant.now());
    }

    @Test
    void toStringOmitsTokensAndKeys() {
        AtprotoSession s = session("session-id-1234567890", "secret-refresh-token");
        String text = s.toString();
        assertFalse(text.contains("secret-access-token"), text);
        assertFalse(text.contains("secret-refresh-token"), text);
        assertFalse(text.contains(String.valueOf(s.dpopPrivateJwk().get("d"))), text);
        assertFalse(text.contains("session-id-1234567890"), text);
        assertTrue(text.contains(DID.value()), text);
    }

    @Test
    void storeCompareAndSet() {
        InMemoryAtprotoSessionStore store = new InMemoryAtprotoSessionStore();
        AtprotoSession s = session("a", "r1");
        store.save(s);
        store.save(session("b", "x"));
        assertEquals(2, store.findByDid(DID).size());
        assertTrue(store.replaceIfRefreshToken(session("a", "r2"), "r1"));
        assertFalse(store.replaceIfRefreshToken(session("a", "r3"), "r1"));
        assertEquals("r2", store.find("a").orElseThrow().refreshToken());
        assertFalse(store.replaceIfRefreshToken(session("gone", "r2"), "r1"));
        assertTrue(store.find("gone").isEmpty());
        store.delete("a");
        assertTrue(store.find("a").isEmpty());
        assertEquals(1, store.findByDid(DID).size());
    }

    @Test
    void sessionsExpireAtMaxAgeWithoutBeingTouched() {
        java.util.concurrent.atomic.AtomicLong nanos = new java.util.concurrent.atomic.AtomicLong();
        InMemoryAtprotoSessionStore store = new InMemoryAtprotoSessionStore(Duration.ofHours(1), nanos::get,
                com.github.benmanes.caffeine.cache.Scheduler.disabledScheduler());
        store.save(session("a", "r1"));
        nanos.addAndGet(Duration.ofMinutes(30).toNanos());
        store.save(session("b", "r1")); // created now: 30 minutes younger than a
        store.cleanUp();
        assertEquals(2, store.size());
        nanos.addAndGet(Duration.ofMinutes(31).toNanos());
        store.cleanUp();
        assertEquals(1, store.size(), "a, past max-age and never touched, is dropped");
        assertTrue(store.find("a").isEmpty());
        assertEquals(1, store.findByDid(DID).size());
        nanos.addAndGet(Duration.ofMinutes(60).toNanos());
        store.cleanUp();
        assertEquals(0, store.size());
        // a session already past max-age is not kept at all
        AtprotoSession old = session("c", "r1");
        store.save(new AtprotoSession(old.id(), old.did(), old.handle(), old.pds(), old.issuer(), old.grantedScope(),
                old.accessToken(), old.refreshToken(), old.accessExpiry(), old.dpopPrivateJwk(), old.nonces(),
                Instant.now().minus(Duration.ofHours(2))));
        assertTrue(store.find("c").isEmpty());
    }

    @Test
    void pendingTakeRemovesAndExpires() {
        InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore();
        PendingAuthorization p = new PendingAuthorization("https://as.example.com", URI.create("https://as.example.com/t"),
                Optional.of(DID), Optional.empty(), "verifier-secret", Map.of("kty", "EC"), "atproto", "/", "binding-hash",
                Instant.now());
        store.put("s1", p, Duration.ofMinutes(10));
        assertEquals(Optional.of(p), store.take("s1"));
        assertEquals(Optional.empty(), store.take("s1"));
        store.put("s2", p, Duration.ofMillis(-1));
        assertEquals(Optional.empty(), store.take("s2"));
        assertFalse(p.toString().contains("verifier-secret"), p.toString());
        assertFalse(p.toString().contains("binding-hash"), p.toString());
    }

    @Test
    void fullPendingStoreEvictsInsteadOfFailing() {
        InMemoryPendingAuthorizationStore store = new InMemoryPendingAuthorizationStore(3);
        PendingAuthorization p = new PendingAuthorization("https://as.example.com", URI.create("https://as.example.com/t"),
                Optional.empty(), Optional.empty(), "v", Map.of("kty", "EC"), "atproto", "/", "b", Instant.now());
        for (int i = 0; i < 50; i++) {
            store.put("s" + i, p, Duration.ofMinutes(10)); // never throws
        }
        assertTrue(store.size() <= 3, "size " + store.size());
        // what is still held is still usable
        int kept = 0;
        for (int i = 0; i < 50; i++) {
            kept += store.take("s" + i).isPresent() ? 1 : 0;
        }
        assertTrue(kept >= 1 && kept <= 3, "kept " + kept);
    }

    @Test
    void cookieSealsTheSessionId() {
        byte[] key = SessionCookie.deriveKey("0123456789abcdef0123456789abcdef");
        String sealed = SessionCookie.seal(key, "atproto-session", "the-session-id");
        assertFalse(sealed.contains("the-session-id"));
        assertEquals(Optional.of("the-session-id"), SessionCookie.open(key, "atproto-session", sealed));
        // another cookie name (associated data), another key, a flipped bit, garbage: nothing
        assertEquals(Optional.empty(), SessionCookie.open(key, "other", sealed));
        assertEquals(Optional.empty(), SessionCookie.open(SessionCookie.deriveKey("another key that is long enough!!"),
                "atproto-session", sealed));
        char[] c = sealed.toCharArray();
        c[c.length / 2] = c[c.length / 2] == 'A' ? 'B' : 'A';
        assertEquals(Optional.empty(), SessionCookie.open(key, "atproto-session", new String(c)));
        assertEquals(Optional.empty(), SessionCookie.open(key, "atproto-session", "%%%"));
        assertEquals(Optional.empty(), SessionCookie.open(key, "atproto-session", ""));
        // two seals of the same id differ (random IV)
        assertFalse(sealed.equals(SessionCookie.seal(key, "atproto-session", "the-session-id")));
    }
}
