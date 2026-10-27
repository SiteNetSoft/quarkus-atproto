package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientIdentity;
import org.sitenetsoft.quarkus.atproto.oauth.devui.OAuthJsonRpcService;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionStores;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;

import io.quarkus.test.QuarkusUnitTest;

/** The Dev UI card's backend: plain maps and lists, sessions without secrets. */
public class DevUiServiceTest {

    static final String HANDLE = "alice.example.test";
    static final int[] PORTS = FakeAtproto.reservePorts(DevUiServiceTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.app(FAKE, List.of(HANDLE));

    @Inject
    ClientIdentity client;
    @Inject
    OAuthConfig oauth;
    @Inject
    SessionStores stores;
    @Inject
    AtprotoSessionStore sessions;

    @AfterEach
    void reset() {
        FAKE.clear();
        sessions.findByDid(ALICE.did()).forEach(s -> sessions.delete(s.id()));
    }

    @Test
    void infoAndSessionsWithoutSecrets() {
        OAuthJsonRpcService service = new OAuthJsonRpcService(client, oauth, stores);
        Map<String, Object> info = service.info();
        assertEquals("localhost development client (public)", info.get("clientType"));
        assertEquals(client.clientId(), info.get("clientId"));
        assertEquals("login", info.get("mode"));
        assertEquals("/atproto/login", info.get("loginPath"));
        assertInstanceOf(Map.class, info.get("metadata"));
        assertEquals(client.clientId(), ((Map<?, ?>) info.get("metadata")).get("client_id"));
        assertEquals(true, info.get("sessionsListed"));

        assertTrue(service.sessions().isEmpty());
        Browser.login(HANDLE);
        List<Map<String, Object>> list = service.sessions();
        assertEquals(1, list.size());
        Map<String, Object> row = list.get(0);
        assertEquals(ALICE.did().value(), row.get("did"));
        assertEquals(HANDLE, row.get("handle"));
        assertEquals(FAKE.pdsUrl().toString(), row.get("pds"));
        assertEquals(OAuthTestSupport.SCOPE, row.get("scope"));
        AtprotoSession s = sessions.findByDid(ALICE.did()).get(0);
        assertEquals(s.accessExpiry().toString(), row.get("accessExpiry"));
        String all = list.toString() + info;
        for (String secret : List.of(s.accessToken(), s.refreshToken(), s.id(),
                String.valueOf(s.dpopPrivateJwk().get("d")))) {
            assertFalse(all.contains(secret), "a secret reached the Dev UI");
        }
    }
}
