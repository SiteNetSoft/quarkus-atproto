package org.sitenetsoft.atproto.lexicon.codegen.model;

/** {@code boolean}. */
public record LexBoolean(String description, Boolean defaultValue, Boolean constValue) implements LexDef {
  @Override
  public String type() {
    return "boolean";
  }
}
