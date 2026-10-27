package org.sitenetsoft.quarkus.atproto.oauth.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Optional;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientIdentity;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.test.QuarkusUnitTest;
import io.quarkus.test.common.http.TestHTTPResource;

public class DevClientIdTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class).addClasses(Http.class))
            // a loopback base URL still means the development client outside production
            .overrideConfigKey("quarkus.atproto.oauth.client-base-url", "http://localhost:8080")
            .overrideConfigKey("quarkus.atproto.oauth.scope", "atproto transition:generic");

    @TestHTTPResource("/")
    URI root;

    @Inject
    ClientIdentity identity;

    @Test
    void localhostClientIdInTestMode() {
        int port = root.getPort();
        assertEquals("http://localhost?redirect_uri=http%3A%2F%2F127.0.0.1%3A" + port
                + "%2Fatproto%2Foauth%2Fcallback&scope=atproto%20transition%3Ageneric", identity.clientId());
        assertFalse(identity.confidential());
        assertTrue(identity.signingKey().isEmpty());
        assertEquals(URI.create("http://127.0.0.1:" + port + "/atproto/oauth/callback"), identity.redirectUri());
        assertEquals("atproto transition:generic", identity.scope());
        // the authorization server derives the development client's metadata; nothing is served
        assertEquals(404, Http.get(root.resolve("/oauth-client-metadata.json")).statusCode());
        assertEquals(404, Http.get(root.resolve("/oauth/jwks.json")).statusCode());
    }

    @Test
    void productionWithoutBaseUrlNamesTheProperty() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ClientIdentity.resolve(Optional.empty(), "atproto", "/oauth-client-metadata.json",
                        "/atproto/oauth/callback", "/oauth/jwks.json", LaunchMode.NORMAL, () -> 8080,
                        Optional.empty()));
        assertTrue(e.getMessage().contains("quarkus.atproto.oauth.client-base-url"), e.getMessage());
        IllegalStateException loopback = assertThrows(IllegalStateException.class,
                () -> ClientIdentity.resolve(Optional.of("http://127.0.0.1:8080"), "atproto",
                        "/oauth-client-metadata.json", "/atproto/oauth/callback", "/oauth/jwks.json",
                        LaunchMode.NORMAL, () -> 8080, Optional.empty()));
        assertTrue(loopback.getMessage().contains("quarkus.atproto.oauth.client-base-url"), loopback.getMessage());
        // the development client is also used in dev mode
        assertFalse(ClientIdentity.resolve(Optional.empty(), "atproto", "/m", "/cb", "/j", LaunchMode.DEVELOPMENT,
                () -> 8080, Optional.empty()).confidential());
    }
}
