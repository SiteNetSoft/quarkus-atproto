package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoSessionExpired;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoSessions;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoTemporarilyUnavailable;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.oauth.session.Refresher;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;
import org.sitenetsoft.quarkus.atproto.xrpc.AtprotoRecords;

import io.quarkus.test.QuarkusUnitTest;

/**
 * A refresh re-runs the post-token checks: the issuer must still be the authority for the account's current PDS
 * (checked before the refresh token is spent; a transient failure keeps the session), and the response's sub must be
 * present and the session's DID.
 */
public class RefreshChecksTest {

    static final String HANDLE = "alice.example.test";
    static final int[] PORTS = FakeAtproto.reservePorts(RefreshChecksTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);
    /** Another network: its PDS is where the account moves, its AS is another authority. */
    static final int[] OTHER_PORTS = FakeAtproto.reservePorts(RefreshChecksTest.class.getName() + ".other");
    static final FakeAtproto OTHER = FakeAtproto.start(OTHER_PORTS[0], OTHER_PORTS[1], OTHER_PORTS[2]);

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.app(FAKE, List.of(HANDLE))
            // DID documents change during these tests
            .overrideConfigKey("quarkus.atproto.identity.cache-ttl", "0s")
            .overrideConfigKey("quarkus.did.cache-ttl", "0s");

    @Inject
    AtprotoSessionStore sessions;

    @Inject
    Refresher refresher;

    @Inject
    AtprotoSessions atproto;

    @AfterEach
    void reset() {
        FAKE.clear();
        OTHER.clear();
        sessions.findByDid(ALICE.did()).forEach(s -> sessions.delete(s.id()));
    }

    private AtprotoSession loggedIn() {
        Browser.login(HANDLE);
        return sessions.findByDid(ALICE.did()).get(0);
    }

    private AtprotoSession refresh(AtprotoSession s) {
        return refresher.refresh(s.id(), s.accessToken()).await().atMost(Duration.ofSeconds(20));
    }

    @Test
    void transientDidErrorKeepsTheSession() {
        AtprotoSession s = loggedIn();
        FAKE.plc().unavailable(true);
        assertThrows(AtprotoTemporarilyUnavailable.class, () -> refresh(s));
        assertEquals(0, FAKE.as().refreshCount(), "the refresh token must not be spent");
        AtprotoSession kept = sessions.find(s.id()).orElseThrow();
        assertEquals(s.refreshToken(), kept.refreshToken());
        assertEquals(s.accessToken(), kept.accessToken());
        // after the outage the same session refreshes
        FAKE.plc().unavailable(false);
        AtprotoSession refreshed = refresh(s);
        assertEquals(1, FAKE.as().refreshCount());
        assertTrue(!refreshed.refreshToken().equals(s.refreshToken()));
        assertEquals(refreshed.refreshToken(), sessions.find(s.id()).orElseThrow().refreshToken());
    }

    @Test
    void transientMetadataErrorKeepsTheSession() {
        AtprotoSession s = loggedIn();
        FAKE.pds().protectedResourceStatus(503);
        assertThrows(AtprotoTemporarilyUnavailable.class, () -> refresh(s));
        assertEquals(0, FAKE.as().refreshCount());
        assertEquals(s.refreshToken(), sessions.find(s.id()).orElseThrow().refreshToken());
    }

    @Test
    void tokenEndpointOutageKeepsTheSession() {
        AtprotoSession s = loggedIn();
        FAKE.as().tokenEndpointStatus(503);
        assertThrows(AtprotoTemporarilyUnavailable.class, () -> refresh(s));
        assertEquals(0, FAKE.as().refreshCount());
        AtprotoSession kept = sessions.find(s.id()).orElseThrow();
        assertEquals(s.refreshToken(), kept.refreshToken());
        // the authorization server is back: the same session refreshes
        FAKE.as().tokenEndpointStatus(200);
        refresh(s);
        assertEquals(1, FAKE.as().refreshCount());
    }

    @Test
    void transientHandleCheckFailureKeepsTheHandle() {
        AtprotoSession s = loggedIn();
        assertEquals(HANDLE, s.handle().value());
        // the handle's reverse check cannot be done right now (DNS empty, well-known 503)
        FAKE.pds().wellKnownStatus(503);
        AtprotoSession refreshed = refresh(s);
        assertEquals(HANDLE, refreshed.handle().value());
        assertEquals(HANDLE, sessions.find(s.id()).orElseThrow().handle().value());
        // a handle that definitively no longer resolves back is replaced by handle.invalid
        FAKE.pds().wellKnownStatus(404);
        AtprotoSession again = refresh(refreshed);
        assertEquals(Handle.INVALID, again.handle());
    }

    @Test
    void heldRecordsFollowAPdsMove() {
        loggedIn();
        // a long-lived job keeps its records view
        AtprotoRecords held = atproto.records(ALICE.did());
        held.create(TestApp.DOCUMENT, java.util.Map.of("site", "https://example.com", "title", "before",
                "publishedAt", "2026-10-27T10:00:00.000Z"));
        assertEquals(1, FAKE.pds().records().size());
        OTHER.account(HANDLE);
        OTHER.pds().authorizationServers(List.of(FAKE.asUrl().toString()));
        OTHER.pds().trustAuthorizationServer(FAKE.as());
        FAKE.plc().movePds(ALICE.did(), OTHER.pdsUrl());
        refresh(sessions.findByDid(ALICE.did()).get(0));
        held.create(TestApp.DOCUMENT, java.util.Map.of("site", "https://example.com", "title", "after",
                "publishedAt", "2026-10-27T10:00:00.000Z"));
        assertEquals(1, OTHER.pds().records().size(), "the held view writes to the new PDS");
        assertEquals(1, FAKE.pds().records().size());
    }

    @Test
    void deactivatedDidEndsTheSessionWithoutSpending() {
        AtprotoSession s = loggedIn();
        FAKE.plc().tombstone(ALICE.did());
        assertThrows(AtprotoSessionExpired.class, () -> refresh(s));
        assertEquals(0, FAKE.as().refreshCount());
        assertTrue(sessions.find(s.id()).isEmpty());
    }

    @Test
    void refreshWithoutSubExpires() {
        AtprotoSession s = loggedIn();
        FAKE.as().dropSubOnRefresh();
        AtprotoSessionExpired e = assertThrows(AtprotoSessionExpired.class, () -> refresh(s));
        assertTrue(e.getMessage().contains("sub"), e.getMessage());
        assertEquals(1, FAKE.as().refreshCount());
        assertTrue(sessions.find(s.id()).isEmpty(), "the session must end");
    }

    @Test
    void refreshAfterPdsRepointedToAnotherAsExpires() {
        AtprotoSession s = loggedIn();
        FAKE.pds().authorizationServers(List.of(OTHER.asUrl().toString()));
        AtprotoSessionExpired e = assertThrows(AtprotoSessionExpired.class, () -> refresh(s));
        assertTrue(e.getMessage().contains("issuer"), e.getMessage());
        assertEquals(0, FAKE.as().refreshCount(), "detected before the refresh token is spent");
        assertTrue(sessions.find(s.id()).isEmpty(), "nothing stored, the session ends");
    }

    @Test
    void refreshAfterPdsMoveUpdatesThePds() {
        Browser.Login login = Browser.login(HANDLE);
        AtprotoSession s = sessions.findByDid(ALICE.did()).get(0);
        assertEquals(FAKE.pdsUrl(), s.pds());
        // the account moves to another PDS under the same authorization server
        OTHER.account(HANDLE);
        OTHER.pds().authorizationServers(List.of(FAKE.asUrl().toString()));
        OTHER.pds().trustAuthorizationServer(FAKE.as());
        FAKE.plc().movePds(ALICE.did(), OTHER.pdsUrl());
        refresh(s);
        AtprotoSession moved = sessions.find(s.id()).orElseThrow();
        assertEquals(OTHER.pdsUrl(), moved.pds());
        // later requests write to the new PDS
        assertEquals(200, Browser.publish(login.cookie(), "moved").statusCode());
        assertEquals(1, OTHER.pds().records().size());
        assertTrue(FAKE.pds().records().isEmpty());
    }
}
