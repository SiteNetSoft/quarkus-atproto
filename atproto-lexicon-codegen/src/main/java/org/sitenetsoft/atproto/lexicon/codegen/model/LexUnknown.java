package org.sitenetsoft.atproto.lexicon.codegen.model;

/** {@code unknown}: any JSON object. */
public record LexUnknown(String description) implements LexDef {
  @Override
  public String type() {
    return "unknown";
  }
}
