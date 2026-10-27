package org.sitenetsoft.quarkus.atproto.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.atproto.syntax.Did;

class IdentityRulesTest {

    @Test
    void didWebHostnameOnly() {
        assertEquals(Optional.empty(), IdentityResolverImpl.unsupportedDidWeb(Did.parse("did:web:example.com")));
        assertEquals(Optional.empty(), IdentityResolverImpl.unsupportedDidWeb(Did.parse("did:web:localhost%3A2583")));
        assertEquals(Optional.empty(), IdentityResolverImpl.unsupportedDidWeb(Did.parse("did:web:LOCALHOST%3a2583")));
        assertEquals(Optional.empty(),
                IdentityResolverImpl.unsupportedDidWeb(Did.parse("did:plc:abcdefghijklmnopqrstuvwx")));
        assertTrue(IdentityResolverImpl.unsupportedDidWeb(Did.parse("did:web:example.com:u:alice")).isPresent());
        assertTrue(IdentityResolverImpl.unsupportedDidWeb(Did.parse("did:web:example.com%3A8443")).isPresent());
        assertTrue(IdentityResolverImpl.unsupportedDidWeb(Did.parse("did:web:localhost%3A2583:u")).isPresent());
    }

    @Test
    void cacheTtlsTogetherAtMostTenMinutes() {
        assertEquals(Optional.empty(), IdentityResolverImpl.cacheTtlWarning(Duration.ofMinutes(5),
                Duration.ofMinutes(5)));
        assertEquals(Optional.empty(), IdentityResolverImpl.cacheTtlWarning(Duration.ZERO, Duration.ofMinutes(10)));
        Optional<String> warn = IdentityResolverImpl.cacheTtlWarning(Duration.ofMinutes(6), Duration.ofMinutes(5));
        assertTrue(warn.isPresent());
        assertTrue(warn.get().contains("quarkus.did.cache-ttl") && warn.get().contains("10 minutes"), warn.get());
    }
}
