package org.sitenetsoft.quarkus.atproto.identity.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.quarkus.atproto.identity.AtprotoIdentity;
import org.sitenetsoft.quarkus.atproto.identity.IdentityResolver;

import io.quarkus.test.QuarkusUnitTest;

public class CacheTest {

    // the DID document cache is off, so a second PLC request could only be avoided by the identity cache
    @RegisterExtension
    static final QuarkusUnitTest config = IdentityTests.unitTest()
            .overrideConfigKey("quarkus.did.cache-ttl", "0");

    @Inject
    IdentityResolver resolver;

    @Inject
    TestTxtResolver dns;

    @Test
    void secondResolveHitsCache() {
        Handle handle = Handle.parse("cached.example.test");
        String txt = "_atproto.cached.example.test";
        int plcBefore = FakeIdentityHost.hits("/" + FakeIdentityHost.CACHED);
        int dnsBefore = dns.lookups(txt);

        AtprotoIdentity first = resolver.resolve(handle);
        AtprotoIdentity second = resolver.resolve(handle);
        AtprotoIdentity third = resolver.resolveDid(Did.parse(FakeIdentityHost.CACHED));

        assertEquals(handle, first.handle());
        assertEquals(first, second);
        assertEquals(first, third);
        // handle → DID looked up once (the reverse check reuses it), document fetched once
        assertEquals(1, dns.lookups(txt) - dnsBefore);
        assertEquals(1, FakeIdentityHost.hits("/" + FakeIdentityHost.CACHED) - plcBefore);
    }
}
