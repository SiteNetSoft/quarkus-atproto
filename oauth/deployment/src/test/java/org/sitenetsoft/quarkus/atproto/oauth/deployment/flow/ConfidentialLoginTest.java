package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;
import org.sitenetsoft.quarkus.atproto.test.Recorded;

import io.quarkus.test.QuarkusUnitTest;

/**
 * A confidential client ({@code private_key_jwt}): the fake fetches the app's client metadata and JWKS (its
 * {@code https://app.example.test} fetches are sent to the test server) and verifies every client assertion.
 */
public class ConfidentialLoginTest {

    static final String HANDLE = "alice.example.test";
    static final String BASE = "https://app.example.test";
    static final int[] PORTS = FakeAtproto.reservePorts(ConfidentialLoginTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.app(FAKE, List.of(HANDLE))
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(TestTxtResolver.class, TestApp.class, Browser.class, OAuthTestSupport.class)
                    .addAsResource("test-client-key-1.pem"))
            .overrideConfigKey("quarkus.atproto.oauth.client-base-url", BASE)
            .overrideConfigKey("quarkus.atproto.oauth.signing-key.location", "test-client-key-1.pem");

    @BeforeEach
    void serveClientMetadata() {
        FAKE.as().rewriteClientFetches(BASE, "http://127.0.0.1:" + Browser.port());
    }

    @AfterEach
    void reset() {
        FAKE.clear();
    }

    @Test
    void confidentialClientLogsInPublishesAndRefreshes() {
        Browser.Login login = Browser.login(HANDLE);
        assertTrue(login.callbackUrl().toString().startsWith(BASE + "/atproto/oauth/callback?"), login.callbackUrl()
                .toString());
        assertEquals(ALICE.did().value(), Browser.get("/me", login.cookie()).body().asString());
        assertEquals(200, Browser.publish(login.cookie(), "confidential").statusCode());
        FAKE.pds().expireTokens();
        assertEquals(200, Browser.publish(login.cookie(), "after refresh").statusCode());
        assertEquals(1, FAKE.as().refreshCount());
        assertEquals(204, Browser.post("/atproto/logout", login.cookie()).statusCode());
        assertEquals(1, FAKE.as().revokeCount());
        // PAR, token, refresh and revocation all carried a client assertion
        List<Recorded> calls = FAKE.as().requests().stream().filter(r -> r.method().equals("POST")).toList();
        assertTrue(calls.size() >= 4, calls.toString());
        for (Recorded r : calls) {
            assertTrue(r.body().contains("client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer"),
                    r.path() + " " + r.body());
            assertTrue(r.body().contains("client_id=https%3A%2F%2Fapp.example.test%2Foauth-client-metadata.json"),
                    r.body());
        }
    }
}
