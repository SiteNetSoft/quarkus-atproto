package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.AtUri;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoSessionExpired;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoSessions;
import org.sitenetsoft.quarkus.atproto.oauth.events.AtprotoSessionRevoked;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;
import org.sitenetsoft.quarkus.atproto.xrpc.RecordResult;

import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusUnitTest;

/** {@link AtprotoSessions}: a background job publishes for a DID, outside any request. */
public class BackgroundSessionsTest {

    static final String HANDLE = "alice.example.test";
    static final int[] PORTS = FakeAtproto.reservePorts(BackgroundSessionsTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);
    static final FakeAccount CAROL = FAKE.account("carol.example.test");

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.app(FAKE, List.of(HANDLE), EventLog.class);

    @Inject
    AtprotoSessions atproto;

    @Inject
    AtprotoSessionStore sessions;

    @AfterEach
    void reset() {
        FAKE.clear();
        EventLog.EVENTS.clear();
        sessions.findByDid(ALICE.did()).forEach(s -> sessions.delete(s.id()));
    }

    /**
     * A job on a plain thread of the application's own: no CDI request context, nothing propagated. (A
     * {@code ManagedExecutor} that clears contexts still activates a fresh request context on its thread, so it would
     * not prove the point.)
     */
    static <T> T inBackground(java.util.concurrent.Callable<T> job) throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            return executor.submit(() -> {
                assertFalse(Arc.container().requestContext().isActive(), "the job must run without a request");
                return job.call();
            }).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void recordsByDidOutsideRequest() throws Exception {
        Browser.login(HANDLE);
        RecordResult r = inBackground(() -> atproto.records(ALICE.did()).create(TestApp.DOCUMENT,
                Map.of("site", "https://example.com", "title", "Scheduled", "publishedAt",
                        "2026-10-27T10:00:00.000Z")));
        assertEquals(ALICE.did(), r.uri().authority());
        assertTrue(FAKE.pds().records().containsKey(r.uri()));
        assertEquals("Scheduled", FAKE.pds().records().get(r.uri()).get("title").asText());
        // the reactive form too, with the access token expired first: refreshed in the background
        FAKE.pds().expireTokens();
        RecordResult again = inBackground(() -> atproto.reactiveRecords(ALICE.did()).create(TestApp.DOCUMENT,
                Map.of("site", "https://example.com", "title", "Later", "publishedAt", "2026-10-27T11:00:00.000Z"))
                .await().indefinitely());
        assertTrue(FAKE.pds().records().containsKey(again.uri()));
        assertEquals(1, FAKE.as().refreshCount());
    }

    @Test
    void findUsesTheNewestLiveSession() throws Exception {
        assertTrue(atproto.find(ALICE.did()).isEmpty());
        Browser.login(HANDLE);
        Thread.sleep(5);
        Browser.login(HANDLE);
        List<AtprotoSession> all = sessions.findByDid(ALICE.did());
        assertEquals(2, all.size());
        AtprotoSession newest = all.stream().max(java.util.Comparator.comparing(AtprotoSession::created)).orElseThrow();
        assertEquals(newest.id(), inBackground(() -> atproto.find(ALICE.did())).orElseThrow().id());
        // a session past its maximum age is not live
        AtprotoSession old = new AtprotoSession(newest.id(), newest.did(), newest.handle(), newest.pds(),
                newest.issuer(), newest.grantedScope(), newest.accessToken(), newest.refreshToken(),
                newest.accessExpiry(), newest.dpopPrivateJwk(), newest.nonces(), Instant.now().minusSeconds(
                        30L * 24 * 3600));
        sessions.save(old);
        AtprotoSession other = all.stream().filter(s -> !s.id().equals(newest.id())).findFirst().orElseThrow();
        assertEquals(other.id(), atproto.find(ALICE.did()).orElseThrow().id());
    }

    @Test
    void expiredSessionThrows() throws Exception {
        // never signed in
        assertThrows(AtprotoSessionExpired.class, () -> inBackground(() -> atproto.records(CAROL.did())));
        Browser.login(HANDLE);
        // the refresh token was revoked elsewhere and the access token expired: invalid_grant at the refresh
        AtprotoSession s = sessions.findByDid(ALICE.did()).get(0);
        sessions.save(new AtprotoSession(s.id(), s.did(), s.handle(), s.pds(), s.issuer(), s.grantedScope(),
                s.accessToken(), "revoked-elsewhere", Instant.now().minusSeconds(60), s.dpopPrivateJwk(), s.nonces(),
                s.created()));
        AtprotoSessionExpired e = assertThrows(AtprotoSessionExpired.class, () -> inBackground(
                () -> atproto.records(ALICE.did()).create(TestApp.DOCUMENT, Map.of("site", "https://example.com",
                        "title", "Too late", "publishedAt", "2026-10-27T10:00:00.000Z"))));
        assertTrue(e.getMessage().contains("invalid_grant"), e.getMessage());
        assertTrue(sessions.findByDid(ALICE.did()).isEmpty());
        assertTrue(atproto.find(ALICE.did()).isEmpty());
        assertEquals(1, EventLog.of(AtprotoSessionRevoked.class).size());
        assertTrue(FAKE.pds().records().isEmpty());
    }

    @Test
    void revokeEndsEverySessionOfTheDid() throws Exception {
        Browser.Login login = Browser.login(HANDLE);
        Browser.login(HANDLE);
        inBackground(() -> {
            atproto.revoke(ALICE.did());
            return null;
        });
        assertTrue(sessions.findByDid(ALICE.did()).isEmpty());
        assertEquals(2, FAKE.as().revokeCount());
        assertEquals(401, Browser.get("/me", login.cookie()).statusCode());
        assertEquals(List.of(new AtprotoSessionRevoked(ALICE.did(), "revoked by the application")),
                EventLog.of(AtprotoSessionRevoked.class).subList(0, 1));
        // nothing to revoke: harmless
        atproto.revoke(ALICE.did());
    }

    @Test
    void xrpcByDidAuthorizesWithTheSession() throws Exception {
        Browser.login(HANDLE);
        RecordResult created = atproto.records(ALICE.did()).create(TestApp.DOCUMENT, Map.of("site",
                "https://example.com", "title", "Via records", "publishedAt", "2026-10-27T10:00:00.000Z"));
        AtUri uri = created.uri();
        com.fasterxml.jackson.databind.JsonNode got = inBackground(() -> atproto.xrpc(ALICE.did()).query(FAKE.pdsUrl(),
                org.sitenetsoft.atproto.syntax.Nsid.parse("com.atproto.repo.getRecord"),
                Map.of("repo", ALICE.did().value(), "collection", "site.standard.document", "rkey",
                        uri.rkey().orElseThrow().value()),
                com.fasterxml.jackson.databind.JsonNode.class, java.util.Optional.empty()));
        assertEquals("Via records", got.path("value").path("title").asText());
        // the token was sent: the PDS saw a DPoP-authorized getRecord
        assertTrue(FAKE.pds().requests().stream().anyMatch(r -> r.path().equals("/xrpc/com.atproto.repo.getRecord")
                && r.headers().getOrDefault("authorization", "").startsWith("DPoP ")),
                FAKE.pds().requests().toString());
    }
}
