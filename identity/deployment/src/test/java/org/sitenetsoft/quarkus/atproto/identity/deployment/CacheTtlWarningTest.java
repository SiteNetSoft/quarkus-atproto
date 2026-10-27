package org.sitenetsoft.quarkus.atproto.identity.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/** The identity cache stacks on the DID cache: their TTLs together above 10 minutes WARN at startup. */
public class CacheTtlWarningTest {

    @RegisterExtension
    static final QuarkusUnitTest config = IdentityTests.withoutOverrides()
            .overrideConfigKey("quarkus.did.cache-ttl", "6m")
            .overrideConfigKey("quarkus.atproto.identity.cache-ttl", "5m")
            .setLogRecordPredicate(r -> r.getLevel().intValue() >= Level.WARNING.intValue()
                    && r.getLoggerName().startsWith("org.sitenetsoft.quarkus.atproto.identity"))
            .assertLogRecords(records -> {
                assertEquals(1, records.stream().filter(r -> r.getMessage().contains("quarkus.did.cache-ttl")).count(),
                        records.toString());
                assertTrue(records.stream().anyMatch(r -> r.getMessage().contains("PT11M")), records.toString());
            });

    @Test
    void startsAndWarns() {
        // assertLogRecords checks the WARN after the app stops
    }
}
