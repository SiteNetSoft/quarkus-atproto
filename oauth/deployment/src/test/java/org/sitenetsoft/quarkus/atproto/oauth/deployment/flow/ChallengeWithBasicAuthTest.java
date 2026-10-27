package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.response.Response;

/**
 * {@code login} mode in an application that also enables basic auth (priority 2000): a signed-out request gets the
 * AT Protocol mechanism's plain 401, not a {@code WWW-Authenticate: basic} prompt that could never succeed; a signed-in
 * request works.
 */
public class ChallengeWithBasicAuthTest {

    static final String HANDLE = "alice.example.test";
    static final int[] PORTS = FakeAtproto.reservePorts(ChallengeWithBasicAuthTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final org.sitenetsoft.quarkus.atproto.test.FakeAccount ALICE = FAKE.account(HANDLE);

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.app(FAKE, List.of(HANDLE))
            .overrideConfigKey("quarkus.http.auth.basic", "true")
            .overrideConfigKey("quarkus.security.users.embedded.enabled", "true")
            .overrideConfigKey("quarkus.security.users.embedded.plain-text", "true")
            .overrideConfigKey("quarkus.security.users.embedded.users.ann", "ann-password");

    @AfterEach
    void reset() {
        FAKE.clear();
    }

    @Test
    void signedOutGetsNoBasicChallenge() {
        Response r = given().redirects().follow(false).get("/me");
        assertEquals(401, r.statusCode());
        String challenge = r.header("WWW-Authenticate");
        assertTrue(challenge == null || !challenge.toLowerCase(java.util.Locale.ROOT).contains("basic"),
                "unexpected challenge: " + challenge);
        Browser.Login login = Browser.login(HANDLE);
        assertEquals(200, Browser.get("/me", login.cookie()).statusCode());
    }
}
