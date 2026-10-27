package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientIdentity;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;
import org.sitenetsoft.quarkus.atproto.test.Recorded;

import io.quarkus.test.QuarkusUnitTest;

/** {@code fallback-scope}: one PAR retry when the authorization server answers {@code invalid_scope}. */
public class FallbackScopeTest {

    static final String HANDLE = "alice.example.test";
    static final String SCOPE = "atproto repo:site.standard.document?action=create";
    static final String FALLBACK = "atproto transition:generic";
    static final int[] PORTS = FakeAtproto.reservePorts(FallbackScopeTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.app(FAKE, List.of(HANDLE))
            .overrideConfigKey("quarkus.atproto.oauth.scope", SCOPE)
            .overrideConfigKey("quarkus.atproto.oauth.fallback-scope", FALLBACK);

    @Inject
    AtprotoSessionStore sessions;

    @Inject
    ClientIdentity identity;

    @AfterEach
    void reset() {
        FAKE.clear();
        sessions.findByDid(ALICE.did()).forEach(s -> sessions.delete(s.id()));
    }

    @Test
    void invalidScopeRetriesWithFallback() {
        FAKE.as().rejectScopesStartingWith("repo:");
        Browser.Login login = Browser.login(HANDLE);
        assertEquals(FALLBACK, Browser.get("/scope", login.cookie()).body().asString());
        List<Recorded> pars = FAKE.as().requests().stream().filter(r -> r.path().equals("/oauth/par")).toList();
        Recorded last = pars.get(pars.size() - 1);
        assertEquals(201, last.status());
        assertTrue(last.body().contains("scope=atproto+transition%3Ageneric")
                || last.body().contains("scope=atproto%20transition%3Ageneric"), last.body());
        assertEquals(1, FAKE.as().parCount());
        // transition:generic covers the write
        assertEquals(200, Browser.publish(login.cookie(), "generic").statusCode());
    }

    @Test
    void registeredScopeIncludesTheFallback() {
        // the client may only request scopes it registered: the client_id names both
        assertTrue(identity.scope().contains("repo:site.standard.document?action=create"), identity.scope());
        assertTrue(identity.scope().contains("transition:generic"), identity.scope());
        Browser.Login login = Browser.login(HANDLE);
        assertEquals(SCOPE, Browser.get("/scope", login.cookie()).body().asString());
    }
}
