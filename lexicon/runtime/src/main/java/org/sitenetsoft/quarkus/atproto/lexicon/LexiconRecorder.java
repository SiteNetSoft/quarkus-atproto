package org.sitenetsoft.quarkus.atproto.lexicon;

import java.util.Map;

import io.quarkus.runtime.annotations.Recorder;

/** Hands the Lexicon resources discovered at build time to {@link LexiconRegistry}. */
@Recorder
public class LexiconRecorder {

  /** @param lexicons NSID → classpath resource name of its Lexicon JSON */
  public void register(Map<String, String> lexicons) {
    LexiconRegistry.discovered(lexicons);
  }
}
