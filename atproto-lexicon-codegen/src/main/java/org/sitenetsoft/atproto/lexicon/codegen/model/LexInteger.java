package org.sitenetsoft.atproto.lexicon.codegen.model;

import java.util.List;

/** {@code integer}: a signed 64-bit integer with optional bounds. */
public record LexInteger(String description, Long minimum, Long maximum, List<Long> enumValues, Long defaultValue,
                         Long constValue) implements LexDef {
  public LexInteger {
    enumValues = List.copyOf(enumValues);
  }

  @Override
  public String type() {
    return "integer";
  }
}
