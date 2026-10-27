package org.sitenetsoft.quarkus.atproto.lexicon;

import java.util.List;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

/**
 * Java code generation from the application's Lexicons ({@code src/main/lexicons}), run in the build's
 * {@code generate-code} phase. Read by the code generator from the application configuration.
 */
@ConfigMapping(prefix = "quarkus.atproto.lexicon.codegen")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface LexiconCodegenConfig {

  /**
   * NSID globs ({@code *} matches any characters, e.g. {@code com.example.*}) of the Lexicons to generate. Default:
   * all of them.
   */
  Optional<List<String>> include();

  /** NSID globs of Lexicons not to generate (still read to resolve references). */
  Optional<List<String>> exclude();

  /** Package prefix for generated types: {@code com.example.note} becomes {@code <prefix>.com.example.Note}. */
  Optional<String> packagePrefix();

  /**
   * Dependencies ({@code groupId:artifactId}) that ship Lexicon JSON under {@code META-INF/atproto/lexicons/}
   * <em>without</em> generated classes, whose Lexicons are generated into this application. A dependency that ships
   * classes (it has {@code META-INF/atproto/lexicons/index.json}) cannot be listed: its types already exist.
   */
  Optional<List<String>> generateFromDependencies();
}
