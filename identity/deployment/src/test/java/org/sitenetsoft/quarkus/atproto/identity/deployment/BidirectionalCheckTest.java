package org.sitenetsoft.quarkus.atproto.identity.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.quarkus.atproto.identity.AtprotoIdentity;
import org.sitenetsoft.quarkus.atproto.identity.IdentityResolver;

import io.quarkus.test.QuarkusUnitTest;

public class BidirectionalCheckTest {

    @RegisterExtension
    static final QuarkusUnitTest config = IdentityTests.unitTest();

    @Inject
    IdentityResolver resolver;

    @Test
    void matchingHandleIsKept() {
        Handle alice = Handle.parse("alice.example.test");
        AtprotoIdentity fromDid = resolver.resolveDid(Did.parse(FakeIdentityHost.ALICE));
        assertEquals(alice, fromDid.handle());
        assertTrue(fromDid.handleVerified());
        assertEquals(URI.create(FakeIdentityHost.PDS), fromDid.pds());
        assertEquals(FakeIdentityHost.KEY, fromDid.signingKeyMultibase().orElseThrow());

        AtprotoIdentity fromHandle = resolver.resolve(alice);
        assertEquals(fromDid, fromHandle);

        // a handle resolved over HTTPS only is verified the same way
        assertEquals(Handle.parse("bob.example.test"), resolver.resolve(Handle.parse("bob.example.test")).handle());
    }

    @Test
    void documentClaimingOtherHandleGivesInvalid() {
        // erin.example.test resolves to ERIN, whose document claims bob.example.test
        AtprotoIdentity identity = resolver.resolve(Handle.parse("erin.example.test"));
        assertEquals(Did.parse(FakeIdentityHost.ERIN), identity.did());
        assertEquals(Handle.INVALID, identity.handle());
        assertFalse(identity.handleVerified());
        assertEquals(URI.create(FakeIdentityHost.PDS), identity.pds());
    }

    @Test
    void handleResolvingToOtherDidGivesInvalid() {
        // FRANK's document claims alice.example.test, which resolves to ALICE
        AtprotoIdentity identity = resolver.resolveDid(Did.parse(FakeIdentityHost.FRANK));
        assertEquals(Did.parse(FakeIdentityHost.FRANK), identity.did());
        assertEquals(Handle.INVALID, identity.handle());
    }

    @Test
    void documentWithoutHandleGivesInvalid() {
        assertEquals(Handle.INVALID, resolver.resolveDid(Did.parse(FakeIdentityHost.PDS_MULTI)).handle());
    }
}
