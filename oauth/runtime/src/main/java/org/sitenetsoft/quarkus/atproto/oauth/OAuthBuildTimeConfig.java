package org.sitenetsoft.quarkus.atproto.oauth;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.atproto.oauth")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface OAuthBuildTimeConfig {

    /**
     * Whether the AT Protocol OAuth client is enabled: when {@code false} no routes are registered.
     */
    @WithDefault("true")
    boolean enabled();
}
