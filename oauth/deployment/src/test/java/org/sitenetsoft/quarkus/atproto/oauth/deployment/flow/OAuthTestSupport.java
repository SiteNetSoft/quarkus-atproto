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

    /** Application users of the link-mode tests (basic auth stands in for the application's own sign-in). */
    static final String ANN = "ann";
    static final String ANN_PASSWORD = "ann-password";
    static final String BEN = "ben";
    static final String BEN_PASSWORD = "ben-password";

    static QuarkusUnitTest app(FakeAtproto fake, List<String> handles, Class<?>... extra) {
        return base(fake, handles, TestApp.class, extra);
    }

    /**
     * {@code link} mode behind basic auth with the embedded users {@value #ANN} and {@value #BEN}; {@code app} is the
     * application's resource (instead of {@link TestApp}).
     */
    static QuarkusUnitTest linkApp(FakeAtproto fake, List<String> handles, Class<?> app, Class<?>... extra) {
        return base(fake, handles, app, extra)
                .overrideConfigKey("quarkus.atproto.oauth.mode", "link")
                .overrideConfigKey("quarkus.http.auth.basic", "true")
                .overrideConfigKey("quarkus.security.users.embedded.enabled", "true")
                .overrideConfigKey("quarkus.security.users.embedded.plain-text", "true")
                .overrideConfigKey("quarkus.security.users.embedded.users." + ANN, ANN_PASSWORD)
                .overrideConfigKey("quarkus.security.users.embedded.users." + BEN, BEN_PASSWORD)
                .overrideConfigKey("quarkus.security.users.embedded.roles." + ANN, "member")
                .overrideConfigKey("quarkus.security.users.embedded.roles." + BEN, "member");
    }

    private static QuarkusUnitTest base(FakeAtproto fake, List<String> handles, Class<?> app, Class<?>... extra) {
        QuarkusUnitTest test = new QuarkusUnitTest()
                .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                        .addClasses(TestTxtResolver.class, app, Browser.class, LinkBrowser.class,
                                OAuthTestSupport.class)
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
