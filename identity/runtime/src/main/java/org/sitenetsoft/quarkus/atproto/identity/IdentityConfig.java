package org.sitenetsoft.quarkus.atproto.identity;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.atproto.identity")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface IdentityConfig {

    /**
     * How long handle → DID and DID → identity results are cached. {@code 0} disables caching. Keep it under 10
     * minutes when the results back AT Protocol OAuth. Failures are not cached.
     */
    @WithDefault("5m")
    Duration cacheTtl();

    /**
     * DNS settings for the default {@code TxtResolver}.
     */
    Dns dns();

    /**
     * <strong>Test and dev only.</strong> Handle → base URL: the handle's well-known is fetched from
     * {@code <base>/.well-known/atproto-did} instead of {@code https://<handle>/.well-known/atproto-did}, so a loopback
     * server can stand in for the handle's host. Empty in production.
     */
    Map<String, URI> httpsWellKnownOverride();

    interface Dns {
        /**
         * DNS servers ({@code host} or {@code host:port}, IPv6 as {@code [addr]:port}), tried in order. When unset the
         * system resolver configuration is used.
         */
        Optional<List<String>> servers();
    }
}
