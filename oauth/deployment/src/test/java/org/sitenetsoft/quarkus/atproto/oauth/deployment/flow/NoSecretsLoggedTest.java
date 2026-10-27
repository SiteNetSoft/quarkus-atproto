package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;

import io.quarkus.test.QuarkusUnitTest;

/** Tokens, codes and DPoP keys never reach the logs, at DEBUG, through a full login, publish, refresh and logout. */
public class NoSecretsLoggedTest {

    static final String HANDLE = "alice.example.test";
    static final String CATEGORY = "org.sitenetsoft.quarkus.atproto";
    static final int[] PORTS = FakeAtproto.reservePorts(NoSecretsLoggedTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.app(FAKE, List.of(HANDLE))
            .overrideConfigKey("quarkus.log.category.\"" + CATEGORY + "\".level", "DEBUG");

    @Inject
    AtprotoSessionStore sessions;

    @Test
    void noTokenOrKeyInLogs() {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                StringBuilder sb = new StringBuilder(new SimpleFormatter().formatMessage(record));
                if (record.getThrown() != null) {
                    StringWriter w = new StringWriter();
                    record.getThrown().printStackTrace(new PrintWriter(w));
                    sb.append('\n').append(w);
                }
                lines.add(record.getLoggerName() + ": " + sb);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        Logger logger = Logger.getLogger(CATEGORY);
        logger.addHandler(capture);
        Set<String> secrets = new LinkedHashSet<>();
        try {
            Browser.Login login = Browser.login(HANDLE);
            secrets.add(Browser.param(login.callbackUrl(), "code").orElseThrow());
            AtprotoSession first = sessions.findByDid(ALICE.did()).get(0);
            collect(secrets, first);
            assertEquals(200, Browser.publish(login.cookie(), "one").statusCode());
            FAKE.pds().expireTokens();
            assertEquals(200, Browser.publish(login.cookie(), "two").statusCode()); // refreshed
            assertEquals(1, FAKE.as().refreshCount());
            collect(secrets, sessions.findByDid(ALICE.did()).get(0));
            // a refused login (wrong sub) logs its reason
            FAKE.as().wrongSub(org.sitenetsoft.atproto.syntax.Did.parse("did:plc:aaaaaaaaaaaaaaaaaaaaaaaa"));
            var refused = Browser.authorize(Browser.startLogin(HANDLE));
            secrets.add(Browser.param(refused, "code").orElseThrow());
            assertEquals(401, Browser.callback(refused).statusCode());
            assertEquals(204, Browser.post("/atproto/logout", login.cookie()).statusCode());
        } finally {
            logger.removeHandler(capture);
            FAKE.clear();
        }
        assertFalse(lines.isEmpty(), "nothing was captured: the test would prove nothing");
        assertTrue(lines.stream().anyMatch(l -> l.contains("refused")), String.join("\n", lines));
        for (String line : lines) {
            for (String secret : secrets) {
                assertFalse(line.contains(secret), "a secret was logged: " + line);
            }
        }
    }

    private static void collect(Set<String> secrets, AtprotoSession s) {
        secrets.add(s.accessToken());
        secrets.add(s.refreshToken());
        secrets.add(String.valueOf(s.dpopPrivateJwk().get("d")));
        // the access token's signature part on its own
        secrets.add(s.accessToken().substring(s.accessToken().lastIndexOf('.') + 1));
    }
}
