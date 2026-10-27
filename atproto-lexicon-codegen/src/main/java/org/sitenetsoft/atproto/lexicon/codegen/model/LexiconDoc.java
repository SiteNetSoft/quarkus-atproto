package org.sitenetsoft.atproto.lexicon.codegen.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.sitenetsoft.atproto.syntax.Nsid;

/** One Lexicon file: {@code lexicon} version, {@code id}, optional description and its defs in file order. */
public record LexiconDoc(int lexicon, Nsid id, String description, Map<String, LexDef> defs) {
  public LexiconDoc {
    Objects.requireNonNull(id, "id");
    defs = Collections.unmodifiableMap(new LinkedHashMap<>(defs));
  }
}
