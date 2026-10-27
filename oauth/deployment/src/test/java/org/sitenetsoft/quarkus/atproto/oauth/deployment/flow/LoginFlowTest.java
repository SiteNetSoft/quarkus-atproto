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
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;
import org.sitenetsoft.quarkus.atproto.test.Recorded;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.response.Response;

public class LoginFlowTest {

    static final String HANDLE = "alice.example.test";
    static final String NOBODY = "nobody.example.test";
    static final String BOB_HANDLE = "bob.example.test";
    static final int[] PORTS = FakeAtproto.reservePorts(LoginFlowTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);
    static final FakeAccount BOB = FAKE.account(BOB_HANDLE);

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.app(FAKE, List.of(HANDLE, NOBODY, BOB_HANDLE));

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
    void callbackFromAnotherBrowserRejected() {
        // the victim is signed in as alice
        Browser.Login victim = Browser.login(HANDLE);
        // the attacker consents for bob and sends the victim the callback URL
        URI attackerCallback = Browser.authorize(Browser.startLogin(BOB_HANDLE));
        Response r = Browser.callback(attackerCallback, null, victim.cookie());
        assertEquals(401, r.statusCode(), r.body().asString());
        assertNull(r.cookie(Browser.COOKIE));
        assertTrue(sessions.findByDid(BOB.did()).isEmpty());
        assertEquals(1, FAKE.as().tokenCount(), "only the victim's own login exchanged a code");
        // the victim's session is untouched
        assertEquals(ALICE.did().value(), Browser.get("/me", victim.cookie()).body().asString());
        assertEquals(1, sessions.findByDid(ALICE.did()).size());
        // a forged binding cookie fails too, and neither attempt consumed the attacker's pending login
        assertEquals(401, Browser.callback(attackerCallback, "forged-binding", victim.cookie()).statusCode());
        assertEquals(401, Browser.callback(attackerCallback, Browser.binding(Browser.authorize(
                Browser.startLogin(HANDLE))), null).statusCode()); // another login's (valid) binding
        assertEquals(302, Browser.callback(attackerCallback).statusCode());
        assertEquals(1, sessions.findByDid(BOB.did()).size());
        assertEquals(ALICE.did().value(), Browser.get("/me", victim.cookie()).body().asString());
    }

    @Test
    void callbackChecksIssBeforeError() {
        FAKE.as().wrongIss();
        FAKE.as().denyNext();
        URI callbackUrl = Browser.authorize(Browser.startLogin(HANDLE));
        Response r = Browser.callback(callbackUrl);
        assertEquals(401, r.statusCode());
        assertTrue(r.body().asString().contains("iss"), r.body().asString());
        assertTrue(!r.body().asString().contains("access_denied"), r.body().asString());
    }

    @Test
    void handleToCallbackToAuthenticated() {
        assertEquals(401, Browser.get("/me", null).statusCode());
        Browser.Login login = Browser.login("@Alice.Example.Test.");
        assertEquals("/", login.callback().header("Location"));
        Response me = Browser.get("/me", login.cookie());
        assertEquals(200, me.statusCode());
        assertEquals(ALICE.did().value(), me.body().asString());
        Response who = Browser.get("/whoami", login.cookie());
        assertEquals(ALICE.did().value() + " " + HANDLE + " " + FAKE.pdsUrl() + " [user]", who.body().asString());
        assertEquals(1, sessions.findByDid(ALICE.did()).size());
        // the PAR carried the handle as login_hint and the client_id was the localhost development client
        Recorded par = FAKE.as().requests().stream().filter(r -> r.path().equals("/oauth/par") && r.status() == 201)
                .findFirst().orElseThrow();
        assertTrue(par.body().contains("login_hint=alice.example.test"), par.body());
        assertTrue(par.body().contains("client_id=http%3A%2F%2Flocalhost%3Fredirect_uri"), par.body());
    }

    @Test
    void formPostStartsTheLogin() {
        Response r = Browser.sameOrigin(given().redirects().follow(false))
                .formParam("handle", HANDLE).formParam("redirect", "/posted").post("/atproto/login");
        Response cb = Browser.callback(Browser.authorize(r));
        assertEquals(302, cb.statusCode(), cb.body().asString());
        assertEquals("/posted", cb.header("Location"));
        assertEquals(ALICE.did().value(), Browser.get("/me", cb.cookie(Browser.COOKIE)).body().asString());
    }

    @Test
    void parUsesNonceRetry() {
        // whatever nonce an earlier test left in the app is stale now
        FAKE.as().rotateNonceNow();
        Browser.login(HANDLE);
        List<Recorded> pars = FAKE.as().requests().stream().filter(r -> r.path().equals("/oauth/par")).toList();
        // the first PAR has no current authorization server nonce: 400 use_dpop_nonce, then one retry
        assertEquals(List.of(400, 201), pars.stream().map(Recorded::status).toList());
        assertEquals(1, FAKE.as().parCount());
        // the nonce is remembered: the next login needs one PAR request
        Browser.login(HANDLE);
        long parRequests = FAKE.as().requests().stream().filter(r -> r.path().equals("/oauth/par")).count();
        assertEquals(3, parRequests);
        assertEquals(2, FAKE.as().parCount());
    }

    @Test
    void callbackReplayRejected() {
        URI callbackUrl = Browser.authorize(Browser.startLogin(HANDLE));
        assertEquals(302, Browser.callback(callbackUrl).statusCode());
        Response replay = Browser.callback(callbackUrl);
        assertEquals(401, replay.statusCode());
        assertNull(replay.cookie(Browser.COOKIE));
        assertEquals(1, FAKE.as().tokenCount());
    }

    @Test
    void consentDeniedIs401AndNoSession() {
        FAKE.as().denyNext();
        URI callbackUrl = Browser.authorize(Browser.startLogin(HANDLE));
        assertEquals("access_denied", Browser.param(callbackUrl, "error").orElseThrow());
        Response denied = Browser.callback(callbackUrl);
        assertEquals(401, denied.statusCode());
        assertTrue(denied.body().asString().contains("access_denied"), denied.body().asString());
        assertNull(denied.cookie(Browser.COOKIE));
        assertTrue(sessions.findByDid(ALICE.did()).isEmpty());
        assertEquals(0, FAKE.as().tokenCount());
        // the pending entry is gone: the same state again is unknown
        assertEquals(401, Browser.callback(callbackUrl).statusCode());
    }

    @Test
    void wrongIssRejected() {
        FAKE.as().wrongIss();
        URI callbackUrl = Browser.authorize(Browser.startLogin(HANDLE));
        Response r = Browser.callback(callbackUrl);
        assertEquals(401, r.statusCode());
        assertTrue(r.body().asString().contains("iss"), r.body().asString());
        assertEquals(0, FAKE.as().tokenCount());
        assertTrue(sessions.findByDid(ALICE.did()).isEmpty());
    }

    @Test
    void unknownHandleIsUserError() {
        Response r = Browser.startLogin(NOBODY);
        assertEquals(400, r.statusCode());
        assertTrue(r.body().asString().contains("No AT Protocol account found for this handle"), r.body().asString());
        assertEquals(400, Browser.startLogin("not a handle").statusCode());
        assertEquals(400, Browser.sameOrigin(given().redirects().follow(false)).post("/atproto/login").statusCode());
    }

    @Test
    void handleOnNonexistentDomainIsUserError() {
        // DNS (the test resolver) has no record and the domain does not exist: "no such account", not "try again"
        Response r = Browser.startLogin("nobody.no-such-domain-for-atproto-tests");
        assertEquals(400, r.statusCode(), r.body().asString());
        assertTrue(r.body().asString().contains("No AT Protocol account found for this handle"), r.body().asString());
    }

    @Test
    void redirectTargetMustBeLocal() {
        Response cb = Browser.callback(Browser.authorize(Browser.startLogin(HANDLE, "/after?x=1")));
        assertEquals("/after?x=1", cb.header("Location"));
        for (String evil : List.of("https://evil.example/", "//evil.example/", "/\\evil.example", "javascript:alert(1)")) {
            Response r = Browser.callback(Browser.authorize(Browser.startLogin(HANDLE, evil)));
            assertEquals(302, r.statusCode());
            assertEquals("/", r.header("Location"), evil);
        }
    }

    @Test
    void logoutRevokesDeletesAndClearsCookie() {
        Browser.Login login = Browser.login(HANDLE);
        Response out = Browser.post("/atproto/logout", login.cookie());
        assertEquals(204, out.statusCode());
        String setCookie = out.header("Set-Cookie");
        assertTrue(setCookie.startsWith(Browser.COOKIE + "=") && setCookie.contains("Max-Age=0"), setCookie);
        assertEquals(1, FAKE.as().revokeCount());
        assertTrue(sessions.findByDid(ALICE.did()).isEmpty());
        assertEquals(401, Browser.get("/me", login.cookie()).statusCode());
        // logging out without a session is harmless
        assertEquals(204, Browser.post("/atproto/logout", null).statusCode());
    }

    private static io.restassured.specification.RequestSpecification given() {
        return io.restassured.RestAssured.given();
    }

    @Test
    void getIsRefused() {
        Response r = given().redirects().follow(false).queryParam("handle", HANDLE).get("/atproto/login");
        assertEquals(405, r.statusCode());
        assertEquals("POST", r.header("Allow"));
        assertNull(Browser.setCookie(r, Browser.BINDING));
        assertEquals(0, FAKE.as().parCount());
    }

    @Test
    void crossSitePostRefused() {
        Response r = given().redirects().follow(false).header("Sec-Fetch-Site", "cross-site")
                .contentType("application/x-www-form-urlencoded").formParam("handle", HANDLE).post("/atproto/login");
        assertEquals(403, r.statusCode(), r.body().asString());
        assertNull(Browser.setCookie(r, Browser.BINDING));
        // same-site (another origin of the same site) is not same-origin either
        assertEquals(403, given().redirects().follow(false).header("Sec-Fetch-Site", "same-site")
                .contentType("application/x-www-form-urlencoded").formParam("handle", HANDLE).post("/atproto/login")
                .statusCode());
        assertEquals(0, FAKE.as().parCount());
    }

    @Test
    void foreignOriginRefused() {
        Response r = given().redirects().follow(false).header("Origin", "https://evil.example")
                .contentType("application/x-www-form-urlencoded").formParam("handle", HANDLE).post("/atproto/login");
        assertEquals(403, r.statusCode(), r.body().asString());
        assertNull(Browser.setCookie(r, Browser.BINDING));
        assertEquals(403, given().redirects().follow(false).header("Origin", "null")
                .contentType("application/x-www-form-urlencoded").formParam("handle", HANDLE).post("/atproto/login")
                .statusCode());
        assertEquals(0, FAKE.as().parCount());
    }

    @Test
    void noOriginHeadersRefused() {
        Response r = given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
                .formParam("handle", HANDLE).post("/atproto/login");
        assertEquals(403, r.statusCode(), r.body().asString());
        assertNull(Browser.setCookie(r, Browser.BINDING));
        assertEquals(0, FAKE.as().parCount());
    }

    @Test
    void ownOriginAccepted() {
        // an older browser without Fetch Metadata: the Origin header of this application (the development client:
        // the request's own host)
        Response r = given().redirects().follow(false).header("Origin", "http://localhost:" + Browser.port())
                .contentType("application/x-www-form-urlencoded").formParam("handle", HANDLE).post("/atproto/login");
        assertEquals(302, r.statusCode(), r.body().asString());
        assertEquals(302, Browser.callback(Browser.authorize(r)).statusCode());
    }
}
