package org.sitenetsoft.quarkus.atproto.identity.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.quarkus.atproto.identity.HandleNotFound;
import org.sitenetsoft.quarkus.atproto.identity.IdentityResolutionFailed;
import org.sitenetsoft.quarkus.atproto.identity.IdentityResolver;

import io.quarkus.test.QuarkusUnitTest;

/** Transient failures must read as "try again", never as "no such handle", and must not be cached. */
public class TransientFailureTest {

    @RegisterExtension
    static final QuarkusUnitTest config = IdentityTests.unitTest();

    private static final String FLAKY_TXT = "_atproto.flaky.example.test";

    @Inject
    IdentityResolver resolver;

    @Inject
    TestTxtResolver dns;

    @Test
    void transientDnsErrorIsResolutionFailedNotNotFound() {
        // DNS errors and the well-known answers 404: undecided, not "not found"
        Handle shaky = Handle.parse("shaky.example.test");
        dns.failing("_atproto.shaky.example.test", true);
        try {
            assertThrows(IdentityResolutionFailed.class, () -> resolver.resolveHandle(shaky));
        } finally {
            dns.failing("_atproto.shaky.example.test", false);
        }
        // once DNS answers cleanly (no record) the same handle is "not found": the failure was not cached
        assertThrows(HandleNotFound.class, () -> resolver.resolveHandle(shaky));
    }

    @Test
    void httpsServerErrorIsResolutionFailed() {
        // no DNS record, well-known answers 500
        assertThrows(IdentityResolutionFailed.class, () -> resolver.resolveHandle(Handle.parse("broken.example.test")));
    }

    @Test
    void reverseCheckTransientErrorIsNotCachedAsInvalid() {
        Did flaky = Did.parse(FakeIdentityHost.FLAKY);
        dns.failing(FLAKY_TXT, true);
        try {
            // the account still resolves; its handle cannot be checked right now
            assertEquals(Handle.INVALID, resolver.resolveDid(flaky).handle());
        } finally {
            dns.failing(FLAKY_TXT, false);
        }
        // after recovery the next call re-checks instead of serving a cached handle.invalid
        assertEquals(Handle.parse("flaky.example.test"), resolver.resolveDid(flaky).handle());
    }
}
