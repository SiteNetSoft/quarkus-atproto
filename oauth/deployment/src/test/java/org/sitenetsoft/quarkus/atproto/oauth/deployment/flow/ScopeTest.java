package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.response.Response;

/** The granted scope: reported, enforced before the PDS is called; no fallback scope configured here. */
public class ScopeTest {

    static final String HANDLE = "alice.example.test";
    static final String SCOPE = "atproto repo:site.standard.document?action=create&action=update";
    static final int[] PORTS = FakeAtproto.reservePorts(ScopeTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.app(FAKE, List.of(HANDLE))
            .overrideConfigKey("quarkus.atproto.oauth.scope", SCOPE);

    @Inject
    AtprotoSessionStore sessions;

    @AfterEach
    void reset() {
        FAKE.clear();
        sessions.findByDid(ALICE.did()).forEach(s -> sessions.delete(s.id()));
    }

    @Test
    void writeOutsideScopeThrowsBeforePds() {
        Browser.Login login = Browser.login(HANDLE);
        Response r = Browser.post("/publication", login.cookie());
        assertEquals(403, r.statusCode(), r.body().asString());
        assertTrue(r.body().asString().contains("site.standard.publication"), r.body().asString());
        assertTrue(FAKE.pds().requests().stream().noneMatch(q -> q.path().startsWith("/xrpc/")),
                "the PDS received a request");
        // inside the scope it works
        assertEquals(200, Browser.publish(login.cookie(), "in scope").statusCode());
    }

    @Test
    void grantedScopeIsReported() {
        Browser.Login login = Browser.login(HANDLE);
        assertEquals(SCOPE, Browser.get("/scope", login.cookie()).body().asString());
        assertEquals(SCOPE, sessions.findByDid(ALICE.did()).get(0).grantedScope());
    }

    @Test
    void noFallbackFailsWithReason() {
        FAKE.as().rejectScopesStartingWith("repo:");
        Response r = Browser.startLogin(HANDLE);
        assertEquals(502, r.statusCode(), r.body().asString());
        assertTrue(r.body().asString().contains("invalid_scope"), r.body().asString());
        assertEquals(0, FAKE.as().parCount());
    }
}
