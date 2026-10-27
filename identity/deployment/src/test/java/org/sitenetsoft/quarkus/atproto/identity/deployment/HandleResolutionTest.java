package org.sitenetsoft.quarkus.atproto.identity.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.AtIdentifier;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.quarkus.atproto.identity.AtprotoIdentity;
import org.sitenetsoft.quarkus.atproto.identity.HandleNotFound;
import org.sitenetsoft.quarkus.atproto.identity.IdentityResolutionFailed;
import org.sitenetsoft.quarkus.atproto.identity.IdentityResolver;
import org.sitenetsoft.quarkus.atproto.identity.ReactiveIdentityResolver;

import io.quarkus.test.QuarkusUnitTest;

public class HandleResolutionTest {

    @RegisterExtension
    static final QuarkusUnitTest config = IdentityTests.unitTest();

    @Inject
    IdentityResolver resolver;

    @Inject
    ReactiveIdentityResolver reactive;

    @Test
    void dnsOnly() {
        assertEquals(Did.parse(FakeIdentityHost.ALICE), resolver.resolveHandle(Handle.parse("alice.example.test")));
    }

    @Test
    void httpsOnly() {
        // the well-known body ends with a newline; it is trimmed
        assertEquals(Did.parse(FakeIdentityHost.BOB), resolver.resolveHandle(Handle.parse("bob.example.test")));
    }

    @Test
    void dnsWinsOnDisagreement() {
        assertEquals(Did.parse(FakeIdentityHost.CAROL), resolver.resolveHandle(Handle.parse("carol.example.test")));
    }

    @Test
    void conflictingDnsRecordsFail() {
        // two different did= TXT records fail the handle even though the well-known answers
        assertThrows(IdentityResolutionFailed.class,
                () -> resolver.resolveHandle(Handle.parse("conflict.example.test")));
    }

    @Test
    void unknownHandleIsNotFound() {
        assertThrows(HandleNotFound.class, () -> resolver.resolveHandle(Handle.parse("nobody.example.test")));
        assertThrows(HandleNotFound.class, () -> resolver.resolveHandle(Handle.INVALID));
    }

    @Test
    void userInputNormalised() {
        AtIdentifier id = AtIdentifier.parse(Handle.normalise("@Alice.Example.Test.").value());
        AtprotoIdentity identity = resolver.resolve(id);
        assertEquals(Did.parse(FakeIdentityHost.ALICE), identity.did());
        assertEquals(Handle.parse("alice.example.test"), identity.handle());
    }

    @Test
    void reactiveTwin() {
        assertEquals(Did.parse(FakeIdentityHost.ALICE),
                reactive.resolveHandle(Handle.parse("alice.example.test")).await().indefinitely());
    }
}
