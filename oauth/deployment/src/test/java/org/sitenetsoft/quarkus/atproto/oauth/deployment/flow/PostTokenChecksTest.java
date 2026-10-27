package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.response.Response;

/** The mandatory checks after the token response, on every login path. */
public class PostTokenChecksTest {

    static final String ALICE_HANDLE = "alice.example.test";
    static final String BOB_HANDLE = "bob.example.test";
    static final int[] PORTS = FakeAtproto.reservePorts(PostTokenChecksTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(ALICE_HANDLE);
    static final FakeAccount BOB = FAKE.account(BOB_HANDLE);
    /** Another network: its authorization server is valid, but it did not issue our tokens. */
    static final int[] OTHER_PORTS = FakeAtproto.reservePorts(PostTokenChecksTest.class.getName() + ".other");
    static final FakeAtproto OTHER = FakeAtproto.start(OTHER_PORTS[0], OTHER_PORTS[1], OTHER_PORTS[2]);

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.app(FAKE, List.of(ALICE_HANDLE, BOB_HANDLE));

    @Inject
    AtprotoSessionStore sessions;

    @AfterEach
    void reset() {
        FAKE.clear();
        for (FakeAccount a : List.of(ALICE, BOB)) {
            sessions.findByDid(a.did()).forEach(s -> sessions.delete(s.id()));
        }
    }

    @Test
    void subMismatchRejected() {
        FAKE.as().wrongSub(BOB.did());
        Response r = Browser.callback(Browser.authorize(Browser.startLogin(ALICE_HANDLE)));
        assertRefused(r, "sub");
        assertEquals(1, FAKE.as().tokenCount());
    }

    @Test
    void missingAtprotoScopeRejected() {
        FAKE.as().dropAtprotoScope();
        Response r = Browser.callback(Browser.authorize(Browser.startLogin(ALICE_HANDLE)));
        assertRefused(r, "atproto");
    }

    @Test
    void loginStartedFromDidChecksSub() {
        Browser.Login ok = Browser.login(ALICE.did().value());
        assertEquals(ALICE.did().value(), Browser.get("/me", ok.cookie()).body().asString());
        FAKE.as().wrongSub(BOB.did());
        Response r = Browser.callback(Browser.authorize(Browser.startLogin(ALICE.did().value())));
        assertEquals(401, r.statusCode());
        assertTrue(sessions.findByDid(BOB.did()).isEmpty());
    }

    @Test
    void issuerRecheckWhenStartedFromHost() {
        // an entryway login (the authorization server's own host, no account given): sub's PDS is found from its DID
        // document, and the session's PDS is that one, never the entryway
        Browser.Login login = Browser.login(FAKE.asUrl().toString());
        assertEquals(ALICE.did().value(), Browser.get("/me", login.cookie()).body().asString());
        AtprotoSession s = sessions.findByDid(ALICE.did()).get(0);
        assertEquals(FAKE.pdsUrl(), s.pds());
        assertEquals(FAKE.as().issuer(), s.issuer());
        assertEquals(ALICE.handle(), s.handle());
        sessions.delete(s.id());

        // sub's PDS names another (valid) authorization server: the issuer of the token is not the account's
        FAKE.pds().authorizationServers(List.of(OTHER.asUrl().toString()));
        URI callbackUrl = Browser.authorize(Browser.startLogin(FAKE.asUrl().toString()));
        Response refused = Browser.callback(callbackUrl);
        assertRefused(refused, "issuer");
        assertEquals(2, FAKE.as().tokenCount());
    }

    private void assertRefused(Response r, String reason) {
        assertEquals(401, r.statusCode(), r.body().asString());
        assertTrue(r.body().asString().contains(reason), r.body().asString());
        assertNull(r.cookie(Browser.COOKIE));
        assertTrue(sessions.findByDid(ALICE.did()).isEmpty());
        assertTrue(sessions.findByDid(BOB.did()).isEmpty());
    }
}
