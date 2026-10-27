package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** The session cookie: AES-GCM over the session id, HttpOnly, SameSite=Lax. */
public class CookieTest {

    static final String HANDLE = "alice.example.test";
    static final int[] PORTS = FakeAtproto.reservePorts(CookieTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.app(FAKE, List.of(HANDLE));

    @Inject
    AtprotoSessionStore sessions;

    @AfterEach
    void reset() {
        FAKE.clear();
        sessions.findByDid(ALICE.did()).forEach(s -> sessions.delete(s.id()));
    }

    @Test
    void tamperedCookieIs401() {
        Browser.Login login = Browser.login(HANDLE);
        String cookie = login.cookie();
        assertEquals(200, Browser.get("/me", cookie).statusCode());
        char[] c = cookie.toCharArray();
        int i = c.length / 2;
        c[i] = c[i] == 'A' ? 'B' : 'A';
        assertEquals(401, Browser.get("/me", new String(c)).statusCode());
        assertEquals(401, Browser.get("/me", cookie.substring(0, cookie.length() - 2)).statusCode());
        assertEquals(401, Browser.get("/me", "garbage").statusCode());
        // the cookie does not carry the session id in the clear
        AtprotoSession s = sessions.findByDid(ALICE.did()).get(0);
        assertFalse(cookie.contains(s.id()));
        // a public route still answers with a bad cookie (an unusable cookie is no identity, not an error)
        assertEquals(302, Browser.get("/atproto/login?handle=" + HANDLE, "garbage").statusCode());
    }

    @Test
    void cookieFlags() {
        Browser.Login login = Browser.login(HANDLE);
        String setCookie = login.callback().header("Set-Cookie");
        String attributes = setCookie.toLowerCase(java.util.Locale.ROOT); // attribute names are case-insensitive
        assertTrue(setCookie.startsWith(Browser.COOKIE + "="), setCookie);
        assertTrue(attributes.contains("; httponly"), setCookie);
        assertTrue(attributes.contains("; samesite=lax"), setCookie);
        assertTrue(attributes.contains("; path=/"), setCookie);
        assertTrue(attributes.contains("; max-age=1209600"), setCookie); // 14 days
        assertFalse(attributes.contains("; secure"), "no Secure flag in test mode (plain http): " + setCookie);
    }
}
