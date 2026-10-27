package org.sitenetsoft.quarkus.atproto.oauth.client;

import java.util.List;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;

/**
 * Loads the signing keys and decides the {@link ClientIdentity} at startup, so a configuration that cannot work fails
 * the application instead of the first login.
 */
@Singleton
public class ClientSetup {

    private static final Logger LOG = Logger.getLogger(ClientSetup.class);

    private final OAuthConfig config;
    private volatile ClientKeys keys;
    private volatile ClientIdentity identity;

    public ClientSetup(OAuthConfig config) {
        this.config = config;
    }

    void onStart(@Observes StartupEvent event) {
        ClientKeys k = keys();
        // validates now; the development client's port is only known once the HTTP server listens
        ClientIdentity probe = resolve(k, () -> 0);
        if (!probe.confidential() && !k.keys().isEmpty()) {
            LOG.warnf("%s is set but the localhost development client is in use (no public %s): the keys are unused",
                    OAuthConfig.SIGNING_KEY_LOCATION, OAuthConfig.CLIENT_BASE_URL);
        }
        LOG.debugf("AT Protocol OAuth: %s", probe.confidential() ? probe : "localhost development client");
    }

    /** The configured signing keys (none for the development client). */
    @Produces
    @Singleton
    public ClientKeys keys() {
        ClientKeys k = keys;
        if (k == null) {
            synchronized (this) {
                k = keys;
                if (k == null) {
                    List<String> locations = config.signingKey().location().orElse(List.of());
                    k = locations.isEmpty() ? ClientKeys.none() : ClientKeys.load(locations);
                    keys = k;
                }
            }
        }
        return k;
    }

    /** This application's client identity. */
    @Produces
    @Singleton
    public ClientIdentity identity() {
        ClientIdentity i = identity;
        if (i == null) {
            synchronized (this) {
                i = identity;
                if (i == null) {
                    i = resolve(keys(), ClientSetup::httpPort);
                    identity = i;
                }
            }
        }
        return i;
    }

    private ClientIdentity resolve(ClientKeys k, java.util.function.IntSupplier port) {
        return ClientIdentity.resolve(config.clientBaseUrl(), registeredScope(config.scope(), config.fallbackScope()),
                config.metadataPath(),
                config.callbackPath(), config.jwksPath(), LaunchMode.current(), port, k.signing());
    }

    /**
     * The scope the client registers (its metadata {@code scope}, or the development {@code client_id}'s): the
     * configured scope plus the fallback scope's other values, since a client may only request what it registered.
     */
    static String registeredScope(String scope, java.util.Optional<String> fallback) {
        java.util.Set<String> all = new java.util.LinkedHashSet<>(java.util.List.of(scope.trim().split("\\s+")));
        fallback.map(String::trim).filter(f -> !f.isEmpty())
                .ifPresent(f -> all.addAll(java.util.List.of(f.split("\\s+"))));
        return String.join(" ", all);
    }

    /** The port the HTTP server listens on (Quarkus writes the actual port back when a random one was asked for). */
    static int httpPort() {
        boolean test = LaunchMode.current() == LaunchMode.TEST;
        return ConfigProvider.getConfig()
                .getOptionalValue(test ? "quarkus.http.test-port" : "quarkus.http.port", Integer.class)
                .orElse(test ? 8081 : 8080);
    }
}
