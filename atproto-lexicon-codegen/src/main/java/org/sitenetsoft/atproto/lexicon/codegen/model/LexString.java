package org.sitenetsoft.atproto.lexicon.codegen.model;

import java.util.List;

/** {@code string}, with its optional {@code format} and constraints. */
public record LexString(String description, String format, List<String> knownValues, List<String> enumValues,
                        String defaultValue, String constValue, Integer minLength, Integer maxLength,
                        Integer minGraphemes, Integer maxGraphemes) implements LexDef {
  public LexString {
    knownValues = List.copyOf(knownValues);
    enumValues = List.copyOf(enumValues);
  }

  @Override
  public String type() {
    return "string";
  }
}
