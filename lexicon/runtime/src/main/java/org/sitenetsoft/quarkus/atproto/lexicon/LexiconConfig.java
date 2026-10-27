package org.sitenetsoft.quarkus.atproto.lexicon;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.atproto.lexicon")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface LexiconConfig {

  /**
   * Default mode of {@code LexiconValidator.validate}: {@code optimistic} validates records whose collection Lexicon
   * is on the classpath and accepts others, {@code true} also rejects records of unknown collections, {@code false}
   * skips validation.
   */
  @WithDefault("optimistic")
  ValidationMode validation();
}
