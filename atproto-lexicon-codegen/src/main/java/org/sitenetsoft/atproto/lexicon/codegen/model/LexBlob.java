package org.sitenetsoft.atproto.lexicon.codegen.model;

import java.util.List;

/** {@code blob}: a reference to uploaded binary data, with accepted MIME patterns and a size limit. */
public record LexBlob(String description, List<String> accept, Long maxSize) implements LexDef {
  public LexBlob {
    accept = List.copyOf(accept);
  }

  @Override
  public String type() {
    return "blob";
  }
}
