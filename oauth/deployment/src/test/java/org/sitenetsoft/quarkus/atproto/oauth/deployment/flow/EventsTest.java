package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.atproto.oauth.events.AtprotoLoggedIn;
import org.sitenetsoft.quarkus.atproto.oauth.events.AtprotoLoggedOut;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;

import io.quarkus.test.QuarkusUnitTest;

/**
 * Login events in {@code login} mode (no application principal). One application runs in one mode, so the
 * {@code link} mode half ({@code AtprotoLoggedIn} / {@code AtprotoLoggedOut} carrying the application principal) is
 * asserted by {@link LinkModeTest#linkAttachesDidToAppUser} and {@link LinkModeTest#unlinkRemovesLinkAndSession}.
 */
public class EventsTest {

    static final String HANDLE = "alice.example.test";
    static final int[] PORTS = FakeAtproto.reservePorts(EventsTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.app(FAKE, List.of(HANDLE), EventLog.class);

    @Inject
    AtprotoSessionStore sessions;

    @AfterEach
    void reset() {
        FAKE.clear();
        EventLog.EVENTS.clear();
        sessions.findByDid(ALICE.did()).forEach(s -> sessions.delete(s.id()));
    }

    @Test
    void loggedInFiredBothModes() {
        Browser.login(HANDLE);
        assertEquals(List.of(new AtprotoLoggedIn(ALICE.did(), ALICE.handle(), Optional.empty())),
                EventLog.of(AtprotoLoggedIn.class));
    }

    @Test
    void loggedOutFired() {
        Browser.Login login = Browser.login(HANDLE);
        assertEquals(204, Browser.post("/atproto/logout", login.cookie()).statusCode());
        assertEquals(List.of(new AtprotoLoggedOut(ALICE.did(), Optional.empty())), EventLog.of(AtprotoLoggedOut.class));
        // a logout without a session fires nothing
        Browser.post("/atproto/logout", null);
        assertEquals(1, EventLog.of(AtprotoLoggedOut.class).size());
    }
}
