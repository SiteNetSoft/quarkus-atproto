package org.sitenetsoft.quarkus.atproto.oauth.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

/** Only ES256 (P-256) signing keys are accepted; anything else fails startup naming the property. */
public class SigningKeyRejectedTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class).addAsResource("test-rsa-key.pem"))
            .overrideConfigKey("quarkus.atproto.oauth.client-base-url", "https://example.org")
            .overrideConfigKey("quarkus.atproto.oauth.signing-key.location", "test-rsa-key.pem")
            .assertException(t -> {
                Throwable root = t;
                while (root.getCause() != null && !(root instanceof IllegalStateException)) {
                    root = root.getCause();
                }
                String m = String.valueOf(root.getMessage());
                assertTrue(m.contains("quarkus.atproto.oauth.signing-key.location") && m.contains("P-256"), m);
            });

    @Test
    void rsaKeyFailsStartup() {
        fail("startup should have failed");
    }
}
