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
     * How long handle → DID and DID → identity results are cached. {@code 0} disables caching. Failures are not
     * cached. The identities are built from DID documents that {@code quarkus.did.cache-ttl} caches too, so an
     * identity can be as old as both TTLs together: when the results back AT Protocol OAuth, keep
     * {@code quarkus.did.cache-ttl + quarkus.atproto.identity.cache-ttl} at 10 minutes or less (the defaults, 5m + 5m,
     * do); a WARN at startup says when they exceed it.
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
     * server can stand in for the handle's host. Keys are matched case-insensitively. <strong>Ignored in production
     * (normal launch mode)</strong>, with a WARN, because it could redirect a handle's ownership proof anywhere.
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
