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
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoSessionExpired;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.oauth.session.Refresher;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;

import io.quarkus.test.QuarkusUnitTest;

/** A refresh re-runs the post-token checks: sub present and the same, the issuer still the PDS's authority. */
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

    private void refresh(AtprotoSession s) {
        refresher.refresh(s.id(), s.accessToken()).await().atMost(Duration.ofSeconds(20));
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
