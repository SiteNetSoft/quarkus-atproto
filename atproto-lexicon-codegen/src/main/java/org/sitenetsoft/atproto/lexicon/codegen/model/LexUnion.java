package org.sitenetsoft.atproto.lexicon.codegen.model;

import java.util.List;

/** {@code union} of the referenced object types; open ({@code closed == false}, the default) admits others. */
public record LexUnion(String description, List<String> refs, boolean closed) implements LexDef {
  public LexUnion {
    refs = List.copyOf(refs);
  }

  @Override
  public String type() {
    return "union";
  }
}
