package org.sitenetsoft.quarkus.atproto.xrpc;

import java.time.Duration;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.atproto.xrpc")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface XrpcConfig {

  /**
   * Timeout of one XRPC exchange (every redirect hop included); an authorization retry gets its own. Blob uploads
   * count too, so this is longer than the safe-fetch default.
   */
  @WithDefault("30s")
  Duration timeout();

  /** Largest XRPC response body accepted (a {@code listRecords} page can exceed safe-fetch's 1M default). */
  @WithDefault("5M")
  MemorySize maxResponseSize();
}
