package org.sitenetsoft.quarkus.atproto.identity.deployment;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;

import io.quarkus.test.QuarkusUnitTest;

/**
 * QuarkusUnitTest wired to {@link FakeIdentityHost} and {@link TestTxtResolver}: safe-fetch relaxed for
 * {@code http://127.0.0.1}, the PLC directory pointed at the fake, and every test handle's well-known redirected to it.
 */
final class IdentityTests {

    private IdentityTests() {
    }

    static QuarkusUnitTest unitTest(Class<?>... classes) {
        QuarkusUnitTest test = base(classes);
        for (String handle : FakeIdentityHost.HANDLES) {
            test.overrideConfigKey("quarkus.atproto.identity.https-well-known-override.\"" + handle + "\"",
                    FakeIdentityHost.wellKnownBase(handle));
        }
        return test;
    }

    /** Without any well-known override (the production default: an empty map). */
    static QuarkusUnitTest withoutOverrides(Class<?>... classes) {
        return base(classes);
    }

    private static QuarkusUnitTest base(Class<?>... classes) {
        return new QuarkusUnitTest()
                .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                        .addClasses(IdentityTests.class, FakeIdentityHost.class, TestTxtResolver.class)
                        .addClasses(classes))
                .overrideConfigKey("quarkus.safe-fetch.https-only", "false")
                .overrideConfigKey("quarkus.safe-fetch.allow-private-addresses", "true")
                .overrideConfigKey("quarkus.did.plc.url", FakeIdentityHost.base());
    }
}
