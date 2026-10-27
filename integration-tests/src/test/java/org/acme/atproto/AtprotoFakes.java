package org.acme.atproto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;
import org.sitenetsoft.quarkus.atproto.test.FakeDns;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Starts the fake AT Protocol network in the test JVM and points the packaged application (a separate process under
 * {@code @QuarkusIntegrationTest}) at it with the configuration this resource returns.
 * <p>
 * The packaged application runs in normal (production) launch mode, so it is wired the way a production deployment
 * is, only with loopback fakes behind the URLs:
 * <ul>
 * <li>a <b>confidential client</b>: {@code client-base-url} {@value #BASE} (an ES256 key generated per run, an
 * encryption key for the session cookie; both in {@code target/it-secrets.properties}, passed with
 * {@code quarkus.config.locations} so they stay out of the launch command line). The fake authorization server fetches the client metadata and JWKS of
 * {@value #BASE} from the application on {@code http://127.0.0.1:<quarkus.http.test-port>}, and the tests call the
 * callback URL's path on that port;</li>
 * <li>handles resolve through <b>DNS</b>: {@code quarkus.atproto.identity.dns.servers} names a {@link FakeDns} that
 * answers {@code _atproto.<handle>} TXT records (the HTTPS well-known override is ignored in production);</li>
 * <li>DID documents come from the fake PLC directory ({@code quarkus.did.plc.url}), and safe-fetch allows
 * {@code http} and loopback addresses, which the fakes need.</li>
 * </ul>
 * The application's HTTP port is {@code quarkus.http.test-port}, which the build reserves and passes to the tests
 * (failsafe system property), so the client metadata fetches reach it.
 */
public class AtprotoFakes implements QuarkusTestResourceLifecycleManager {

    static final String BASE = "https://app.example.test";
    static final String HANDLE = "alice.example.test";

    private static volatile FakeAtproto fake;
    private static volatile FakeDns dns;

    /** The running fakes (set while the application under test runs). */
    static FakeAtproto fake() {
        return fake;
    }

    static FakeAccount alice() {
        return fake.account(HANDLE);
    }

    /** The port the application listens on. */
    static int appPort() {
        return Integer.parseInt(System.getProperty("quarkus.http.test-port", "8081"));
    }

    @Override
    public Map<String, String> start() {
        fake = FakeAtproto.start();
        dns = FakeDns.start(0);
        dns.handle(fake.account(HANDLE));
        fake.as().rewriteClientFetches(BASE, "http://127.0.0.1:" + appPort());

        Map<String, String> config = new HashMap<>();
        config.put("quarkus.atproto.oauth.client-base-url", BASE);
        // the per-run secrets go through a config file, not -D system properties (those are printed in the
        // launcher's "Executing" log line)
        config.put("quarkus.config.locations", secretsFile().toUri().toString());
        config.put("quarkus.did.plc.url", fake.plcUrl().toString());
        config.put("quarkus.atproto.identity.dns.servers", dns.server());
        config.put("quarkus.safe-fetch.https-only", "false");
        config.put("quarkus.safe-fetch.allow-private-addresses", "true");
        return config;
    }

    /** Clears requests, tokens and records between tests; the accounts and the DNS records stay. */
    static void clear() {
        fake.clear();
        fake.as().rewriteClientFetches(BASE, "http://127.0.0.1:" + appPort());
    }

    @Override
    public void stop() {
        if (fake != null) {
            fake.close();
        }
        if (dns != null) {
            dns.close();
        }
    }

    /** {@code target/it-secrets.properties}: the signing key location and the session encryption key. */
    private static Path secretsFile() {
        try {
            Path file = Path.of("target", "it-secrets.properties").toAbsolutePath();
            java.util.Properties p = new java.util.Properties();
            p.setProperty("quarkus.atproto.oauth.signing-key.location", signingKey().toString());
            p.setProperty("quarkus.atproto.oauth.session.encryption-key", randomKey());
            try (var out = Files.newOutputStream(file)) {
                p.store(out, "per-run secrets of the integration tests");
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A fresh ES256 key as a PKCS#8 PEM file under {@code target/}. */
    private static Path signingKey() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = g.generateKeyPair();
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(pair.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            Path file = Path.of("target", "it-client-key.pem").toAbsolutePath();
            Files.createDirectories(file.getParent());
            Files.writeString(file, pem);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomKey() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
