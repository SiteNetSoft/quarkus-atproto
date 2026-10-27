package org.sitenetsoft.quarkus.atproto.oauth.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.AtIdentifier;
import org.sitenetsoft.quarkus.atproto.oauth.discovery.Discovery;
import org.sitenetsoft.quarkus.atproto.oauth.discovery.DiscoveredServer;
import org.sitenetsoft.quarkus.atproto.oauth.discovery.OAuthDiscoveryException;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;

import io.quarkus.test.QuarkusUnitTest;

public class DiscoveryTest {

    static final String HANDLE = "alice.example.test";
    static final int[] PORTS = FakeAtproto.reservePorts(DiscoveryTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class).addClasses(TestTxtResolver.class))
            .overrideConfigKey("quarkus.safe-fetch.https-only", "false")
            .overrideConfigKey("quarkus.safe-fetch.allow-private-addresses", "true")
            .overrideConfigKey("quarkus.did.plc.url", FAKE.plcUrl().toString())
            .overrideConfigKey("quarkus.atproto.identity.https-well-known-override.\"" + HANDLE + "\"",
                    FAKE.wellKnownBase(HANDLE).toString());

    @Inject
    Discovery discovery;

    @AfterEach
    void reset() {
        FAKE.clear();
    }

    @Test
    void handleToAuthorizationServer() {
        DiscoveredServer d = resolve();
        assertEquals(ALICE.did(), d.identity().orElseThrow().did());
        assertEquals(ALICE.handle(), d.identity().orElseThrow().handle());
        assertEquals(FAKE.pdsUrl(), d.pds());
        assertEquals(FAKE.as().issuer(), d.as().issuer());
        assertEquals(URI.create(FAKE.as().issuer() + "/oauth/par"), d.as().pushedAuthorizationRequestEndpoint());
        assertEquals(URI.create(FAKE.as().issuer() + "/oauth/token"), d.as().tokenEndpoint());
        assertEquals(URI.create(FAKE.as().issuer() + "/oauth/authorize"), d.as().authorizationEndpoint());
        assertEquals(URI.create(FAKE.as().issuer() + "/oauth/revoke"), d.as().revocationEndpoint().orElseThrow());
    }

    @Test
    void serverHostToAuthorizationServer() {
        DiscoveredServer viaPds = discovery.resolveServer(FAKE.pdsUrl()).await().atMost(Duration.ofSeconds(10));
        assertTrue(viaPds.identity().isEmpty());
        assertEquals(FAKE.pdsUrl(), viaPds.pds());
        assertEquals(FAKE.as().issuer(), viaPds.as().issuer());
        // an entryway that is itself the authorization server (no protected-resource metadata)
        DiscoveredServer viaAs = discovery.resolveServer(FAKE.asUrl()).await().atMost(Duration.ofSeconds(10));
        assertEquals(FAKE.as().issuer(), viaAs.as().issuer());
    }

    @Test
    void issuerMismatchRejected() {
        FAKE.as().overrideMetadata("issuer", "https://evil.example");
        assertEquals("issuer", failure().check());
    }

    @Test
    void twoAuthorizationServersRejected() {
        FAKE.pds().authorizationServers(List.of(FAKE.asUrl().toString(), "https://other.example"));
        assertEquals("authorization_servers", failure().check());
        FAKE.pds().authorizationServers(List.of());
        assertEquals("authorization_servers", failure().check());
    }

    @Test
    void missingParFlagRejected() {
        FAKE.as().overrideMetadata("require_pushed_authorization_requests", null);
        assertEquals("require_pushed_authorization_requests", failure().check());
        FAKE.clear();
        FAKE.as().overrideMetadata("require_pushed_authorization_requests", false);
        assertEquals("require_pushed_authorization_requests", failure().check());
    }

    @Test
    void otherRequiredFlagsChecked() {
        FAKE.as().overrideMetadata("authorization_response_iss_parameter_supported", false);
        assertEquals("authorization_response_iss_parameter_supported", failure().check());
        FAKE.clear();
        FAKE.as().overrideMetadata("client_id_metadata_document_supported", null);
        assertEquals("client_id_metadata_document_supported", failure().check());
        FAKE.clear();
        FAKE.as().overrideMetadata("dpop_signing_alg_values_supported", List.of("RS256"));
        assertEquals("dpop_signing_alg_values_supported", failure().check());
    }

    private DiscoveredServer resolve() {
        return discovery.resolve(AtIdentifier.parse(HANDLE)).await().atMost(Duration.ofSeconds(10));
    }

    private OAuthDiscoveryException failure() {
        OAuthDiscoveryException e = assertThrows(OAuthDiscoveryException.class, this::resolve);
        assertTrue(e.getMessage().contains(e.check()), e.getMessage());
        return e;
    }
}
