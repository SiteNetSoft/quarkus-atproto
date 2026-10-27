package org.sitenetsoft.quarkus.atproto.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.runtime.LaunchMode;

class WellKnownOverrideTest {

    private static final Map<String, URI> CONFIGURED = Map.of("Alice.Example.Test", URI.create("http://127.0.0.1:1/a"));

    @Test
    void overrideIgnoredInProduction() {
        assertTrue(IdentityResolverImpl.effectiveOverrides(CONFIGURED, LaunchMode.NORMAL).isEmpty());
    }

    @Test
    void overrideAppliedInTestAndDevWithLowercaseKeys() {
        Map<String, URI> expected = Map.of("alice.example.test", URI.create("http://127.0.0.1:1/a"));
        assertEquals(expected, IdentityResolverImpl.effectiveOverrides(CONFIGURED, LaunchMode.TEST));
        assertEquals(expected, IdentityResolverImpl.effectiveOverrides(CONFIGURED, LaunchMode.DEVELOPMENT));
        assertTrue(IdentityResolverImpl.effectiveOverrides(Map.of(), LaunchMode.TEST).isEmpty());
    }
}
