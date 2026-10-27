package org.sitenetsoft.quarkus.atproto.oauth.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/** A confidential client needs a signing key. */
public class MissingSigningKeyTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.atproto.oauth.client-base-url", "https://example.org")
            .assertException(t -> {
                Throwable root = t;
                while (root.getCause() != null && !(root instanceof IllegalStateException)) {
                    root = root.getCause();
                }
                String m = String.valueOf(root.getMessage());
                assertTrue(m.contains("quarkus.atproto.oauth.signing-key.location"), m);
            });

    @Test
    void confidentialClientWithoutKeyFailsStartup() {
        fail("startup should have failed");
    }
}
