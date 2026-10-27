package org.sitenetsoft.atproto.lexicon.codegen.model;

/** {@code array} of {@code items}. */
public record LexArray(String description, LexDef items, Integer minLength, Integer maxLength) implements LexDef {
  @Override
  public String type() {
    return "array";
  }
}
