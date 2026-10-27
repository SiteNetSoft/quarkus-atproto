package org.sitenetsoft.quarkus.atproto.identity.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.quarkus.atproto.identity.AtprotoIdentity;
import org.sitenetsoft.quarkus.atproto.identity.IdentityResolutionFailed;
import org.sitenetsoft.quarkus.atproto.identity.IdentityResolver;
import org.sitenetsoft.quarkus.atproto.identity.NoPds;

import io.quarkus.test.QuarkusUnitTest;

public class PdsExtractionTest {

    // also proves the app starts with the production default (no well-known overrides); these documents claim no handle
    @RegisterExtension
    static final QuarkusUnitTest config = IdentityTests.withoutOverrides();

    @Inject
    IdentityResolver resolver;

    @Test
    void picksAtprotoPdsService() {
        AtprotoIdentity identity = resolver.resolveDid(Did.parse(FakeIdentityHost.PDS_MULTI));
        assertEquals(URI.create("https://pds.example.test:8443"), identity.pds());
        assertEquals(FakeIdentityHost.KEY, identity.signingKeyMultibase().orElseThrow());
    }

    @Test
    void rejectsEndpointWithPath() {
        assertThrows(NoPds.class, () -> resolver.resolveDid(Did.parse(FakeIdentityHost.PDS_PATH)));
    }

    @Test
    void missingServiceIsNoPds() {
        assertThrows(NoPds.class, () -> resolver.resolveDid(Did.parse(FakeIdentityHost.PDS_NONE)));
        assertThrows(NoPds.class, () -> resolver.resolveDid(Did.parse(FakeIdentityHost.PDS_WRONG_TYPE)));
    }

    @Test
    void unregisteredDidFails() {
        assertThrows(IdentityResolutionFailed.class, () -> resolver.resolveDid(Did.parse("did:plc:" + "z".repeat(24))));
    }

    @Test
    void didWebWithPathOrPortRefused() {
        // AT Protocol supports hostname-level did:web only: a path or a port (except on localhost) is not an account
        IdentityResolutionFailed path = assertThrows(IdentityResolutionFailed.class,
                () -> resolver.resolveDid(Did.parse("did:web:shared-host.example.test:u:alice")));
        org.junit.jupiter.api.Assertions.assertTrue(path.getMessage().contains("path"), path.getMessage());
        IdentityResolutionFailed port = assertThrows(IdentityResolutionFailed.class,
                () -> resolver.resolveDid(Did.parse("did:web:host.example.test%3A8443")));
        org.junit.jupiter.api.Assertions.assertTrue(port.getMessage().contains("port"), port.getMessage());
    }

    @Test
    void nonAtprotoMethodFails() {
        assertThrows(IdentityResolutionFailed.class,
                () -> resolver.resolveDid(Did.parse("did:key:zQ3shXjHeiBuRCKmM36cuYnm7YEMzhGnCmCyW92sRJ9pribSF")));
    }
}
