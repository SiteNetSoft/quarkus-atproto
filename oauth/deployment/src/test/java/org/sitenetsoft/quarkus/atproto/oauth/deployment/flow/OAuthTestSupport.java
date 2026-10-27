package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import java.util.List;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;

import io.quarkus.test.QuarkusUnitTest;

/** The application under test for the login-flow tests: the fakes, a test REST resource, no DNS. */
final class OAuthTestSupport {

    /** Writes both Standard.site record types; tests that need less override it. */
    static final String SCOPE = "atproto repo:site.standard.document?action=create&action=update "
            + "repo:site.standard.publication?action=create&action=update blob:image/*";

    private OAuthTestSupport() {
    }

    static QuarkusUnitTest app(FakeAtproto fake, List<String> handles, Class<?>... extra) {
        QuarkusUnitTest test = new QuarkusUnitTest()
                .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                        .addClasses(TestTxtResolver.class, TestApp.class, Browser.class, OAuthTestSupport.class)
                        .addClasses(extra))
                .overrideConfigKey("quarkus.safe-fetch.https-only", "false")
                .overrideConfigKey("quarkus.safe-fetch.allow-private-addresses", "true")
                .overrideConfigKey("quarkus.did.plc.url", fake.plcUrl().toString())
                .overrideConfigKey("quarkus.atproto.oauth.scope", SCOPE);
        for (String h : handles) {
            test.overrideConfigKey("quarkus.atproto.identity.https-well-known-override.\"" + h + "\"",
                    fake.wellKnownBase(h).toString());
        }
        return test;
    }
}
