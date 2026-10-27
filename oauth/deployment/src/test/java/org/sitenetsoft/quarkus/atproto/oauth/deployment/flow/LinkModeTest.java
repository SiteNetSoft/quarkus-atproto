package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.AtUri;
import org.sitenetsoft.quarkus.atproto.oauth.events.AtprotoLoggedIn;
import org.sitenetsoft.quarkus.atproto.oauth.events.AtprotoLoggedOut;
import org.sitenetsoft.quarkus.atproto.oauth.link.AtprotoAccountLinks;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * {@code link} mode: the application's own sign-in (basic auth here) owns the identity; the AT Protocol login links a
 * Bluesky account to the signed-in application user.
 */
public class LinkModeTest {

    static final String HANDLE = "alice.example.test";
    static final String BOB_HANDLE = "bob.example.test";
    static final int[] PORTS = FakeAtproto.reservePorts(LinkModeTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);
    static final FakeAccount BOB = FAKE.account(BOB_HANDLE);
    static final String ANN = OAuthTestSupport.ANN;
    static final String BEN = OAuthTestSupport.BEN;

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.linkApp(FAKE, List.of(HANDLE, BOB_HANDLE), LinkApp.class,
            EventLog.class);

    @Inject
    AtprotoSessionStore sessions;

    @Inject
    AtprotoAccountLinks links;

    @AfterEach
    void reset() {
        FAKE.clear();
        EventLog.EVENTS.clear();
        for (FakeAccount a : List.of(ALICE, BOB)) {
            sessions.findByDid(a.did()).forEach(s -> sessions.delete(s.id()));
            for (String user : List.of(ANN, BEN)) {
                links.unlink(user, a.did());
            }
        }
    }

    static RequestSpecification as(String user) {
        return LinkBrowser.as(user);
    }

    static Response startLink(String user, String handle) {
        return LinkBrowser.startLink(user, handle);
    }

    static Response callback(String user, URI callbackUrl) {
        return LinkBrowser.callback(user, callbackUrl);
    }

    static Response link(String user, String handle) {
        return LinkBrowser.link(user, handle);
    }

    @Test
    void loginRequiresAppIdentity() {
        Response r = startLink(null, HANDLE);
        assertEquals(401, r.statusCode(), r.body().asString());
        assertEquals(0, FAKE.as().parCount());
        // a signed-in user's browser sent cross-site (forced link of the attacker's account): refused
        Response forced = as(ANN).header("Sec-Fetch-Site", "cross-site").contentType("application/x-www-form-urlencoded")
                .formParam("handle", BOB_HANDLE).post("/atproto/login");
        assertEquals(403, forced.statusCode(), forced.body().asString());
        assertNull(Browser.setCookie(forced, Browser.BINDING));
        assertEquals(405, as(ANN).queryParam("handle", BOB_HANDLE).get("/atproto/login").statusCode());
        assertEquals(0, FAKE.as().parCount());
        // a wrong password is no identity either
        Response wrong = Browser.sameOrigin(given().redirects().follow(false).auth().preemptive().basic(ANN, "nope"))
                .formParam("handle", HANDLE).post("/atproto/login");
        assertEquals(401, wrong.statusCode());
        assertEquals(0, FAKE.as().parCount());
    }

    @Test
    void linkAttachesDidToAppUser() {
        assertEquals(409, as(ANN).get("/linked").statusCode());
        Response cb = link(ANN, HANDLE);
        assertEquals("/", cb.header("Location"));
        // the application's identity is untouched
        assertEquals(ANN, as(ANN).get("/me").body().asString());
        assertEquals(ALICE.did().value() + " " + HANDLE, as(ANN).get("/linked").body().asString());
        assertEquals(List.of(ALICE.did()), links.findByPrincipal(ANN));
        assertEquals(1, sessions.findByDid(ALICE.did()).size());
        assertEquals(Optional.of(sessions.findByDid(ALICE.did()).get(0).id()), links.sessionFor(ANN, ALICE.did()));
        // writes go to the linked account's repository
        Response published = as(ANN).queryParam("title", "linked").post("/publish");
        assertEquals(200, published.statusCode(), published.body().asString());
        AtUri uri = AtUri.parse(published.body().asString());
        assertEquals(ALICE.did(), uri.authority());
        assertTrue(FAKE.pds().records().containsKey(uri));
        // another application user has nothing linked
        assertEquals(409, as(BEN).get("/linked").statusCode());
        assertEquals("no linked AT Protocol account", as(BEN).get("/linked").body().asString());
        // the login event carries the application principal
        assertEquals(List.of(new AtprotoLoggedIn(ALICE.did(), ALICE.handle(), Optional.of(ANN))),
                EventLog.of(AtprotoLoggedIn.class));
    }

    @Test
    void noAtprotoCookieSetInLinkMode() {
        Response cb = link(ANN, HANDLE);
        assertNull(Browser.setCookie(cb, Browser.COOKIE), String.valueOf(cb.headers().getValues("Set-Cookie")));
        // the atproto mechanism adds no identity: without the app's credentials the request is anonymous
        assertEquals(401, as(null).get("/me").statusCode());
        assertEquals(401, as(null).get("/linked").statusCode());
    }

    @Test
    void callbackRequiresSameAppUser() {
        // ann starts the link; ben (another app user, same browser) completes the callback
        URI callbackUrl = Browser.authorize(startLink(ANN, HANDLE));
        Response r = callback(BEN, callbackUrl);
        assertEquals(401, r.statusCode(), r.body().asString());
        assertTrue(links.findByPrincipal(BEN).isEmpty());
        assertTrue(links.findByPrincipal(ANN).isEmpty());
        assertTrue(sessions.findByDid(ALICE.did()).isEmpty());
        assertEquals(0, FAKE.as().tokenCount());
        // signed out of the application at the callback, even after the same-origin bounce: refused as well
        URI again = Browser.authorize(startLink(ANN, HANDLE));
        assertEquals(200, callback(null, again).statusCode(), "the same-origin bounce");
        assertEquals(401, callback(null, URI.create(again + "&bounce=1")).statusCode());
        assertEquals(0, FAKE.as().tokenCount());
    }

    @Test
    void callbackWithoutAppCookieBouncesSameOrigin() {
        // form auth's SameSite=Strict cookie is withheld on the navigation from the authorization server's site
        URI callbackUrl = Browser.authorize(startLink(ANN, HANDLE));
        Response bounce = callback(null, callbackUrl);
        assertEquals(200, bounce.statusCode(), bounce.body().asString());
        assertEquals("no-store", bounce.header("Cache-Control"));
        assertEquals("no-referrer", bounce.header("Referrer-Policy"));
        assertTrue(bounce.header("Content-Type").startsWith("text/html"), bounce.header("Content-Type"));
        String target = callbackUrl.getRawPath() + "?" + callbackUrl.getRawQuery() + "&bounce=1";
        String escaped = target.replace("&", "&amp;");
        String page = bounce.body().asString();
        assertTrue(page.contains("<meta http-equiv=\"refresh\" content=\"0;url=" + escaped + "\">"), page);
        assertTrue(page.contains("<a href=\"" + escaped + "\">"), page);
        assertTrue(!page.contains("<script"), page);
        // nothing consumed: the binding cookie stays, no code exchanged
        assertNull(Browser.setCookie(bounce, Browser.BINDING));
        assertEquals(0, FAKE.as().tokenCount());
        // the bounced request starts on the application's origin: the app's cookie is sent and the link completes
        Response done = callback(ANN, URI.create("http://127.0.0.1:" + Browser.port() + target));
        assertEquals(302, done.statusCode(), done.body().asString());
        assertEquals(List.of(ALICE.did()), links.findByPrincipal(ANN));
        assertEquals(1, FAKE.as().tokenCount());
    }

    @Test
    void bounceOnlyOnce() {
        URI callbackUrl = Browser.authorize(startLink(ANN, HANDLE));
        Response r = callback(null, URI.create(callbackUrl + "&bounce=1"));
        assertEquals(401, r.statusCode(), r.body().asString());
        assertTrue(links.findByPrincipal(ANN).isEmpty());
        assertEquals(0, FAKE.as().tokenCount());
    }

    @Test
    void unlinkRemovesLinkAndSession() {
        link(ANN, HANDLE);
        assertEquals(401, as(null).post("/atproto/unlink").statusCode());
        Response r = as(ANN).post("/atproto/unlink");
        assertEquals(204, r.statusCode(), r.body().asString());
        assertTrue(links.findByPrincipal(ANN).isEmpty());
        assertTrue(sessions.findByDid(ALICE.did()).isEmpty());
        assertEquals(1, FAKE.as().revokeCount());
        assertEquals("no linked AT Protocol account", as(ANN).get("/linked").body().asString());
        assertEquals(List.of(new AtprotoLoggedOut(ALICE.did(), Optional.of(ANN))), EventLog.of(AtprotoLoggedOut.class));
        // nothing linked: nothing to unlink
        assertEquals(404, as(ANN).post("/atproto/unlink").statusCode());
    }

    @Test
    void forDidPicksAmongSeveral() {
        link(ANN, HANDLE);
        link(ANN, BOB_HANDLE);
        assertEquals(List.of(ALICE.did(), BOB.did()), links.findByPrincipal(ANN));
        Response several = as(ANN).get("/linked");
        assertEquals(409, several.statusCode());
        assertEquals("several linked accounts; use forDid", several.body().asString());
        Response published = as(ANN).queryParam("did", BOB.did().value()).post("/publish");
        assertEquals(200, published.statusCode(), published.body().asString());
        assertEquals(BOB.did(), AtUri.parse(published.body().asString()).authority());
        // an account linked to someone else is not reachable
        link(BEN, HANDLE);
        assertEquals(409, as(BEN).queryParam("did", BOB.did().value()).post("/publish").statusCode());
        // unlink needs the did when several are linked
        assertEquals(400, as(ANN).post("/atproto/unlink").statusCode());
        assertEquals(204, as(ANN).queryParam("did", BOB.did().value()).post("/atproto/unlink").statusCode());
        assertEquals(List.of(ALICE.did()), links.findByPrincipal(ANN));
        // ben's link to alice is a session of its own: ann's link still works
        assertEquals(ALICE.did().value() + " " + HANDLE, as(ANN).get("/linked").body().asString());
        assertEquals(2, sessions.findByDid(ALICE.did()).size());
    }

    @Test
    void relinkReplacesTheSession() {
        link(ANN, HANDLE);
        String first = links.sessionFor(ANN, ALICE.did()).orElseThrow();
        link(ANN, HANDLE);
        String second = links.sessionFor(ANN, ALICE.did()).orElseThrow();
        assertTrue(!first.equals(second));
        assertTrue(sessions.find(first).isEmpty(), "the replaced session is deleted");
        assertEquals(1, sessions.findByDid(ALICE.did()).size());
    }

    @Test
    void maxAgeAppliesToLinkedSession() {
        link(ANN, HANDLE);
        AtprotoSession s = sessions.findByDid(ALICE.did()).get(0);
        sessions.save(new AtprotoSession(s.id(), s.did(), s.handle(), s.pds(), s.issuer(), s.grantedScope(),
                s.accessToken(), s.refreshToken(), s.accessExpiry(), s.dpopPrivateJwk(), s.nonces(),
                java.time.Instant.now().minusSeconds(30L * 24 * 3600)));
        Response r = as(ANN).get("/linked");
        assertEquals(410, r.statusCode(), r.body().asString());
        assertTrue(r.body().asString().contains("link the account again"), r.body().asString());
        assertTrue(sessions.findByDid(ALICE.did()).isEmpty(), "the session past max-age is deleted");
        assertEquals(410, as(ANN).post("/publish").statusCode());
        assertTrue(FAKE.pds().records().isEmpty());
    }
}
