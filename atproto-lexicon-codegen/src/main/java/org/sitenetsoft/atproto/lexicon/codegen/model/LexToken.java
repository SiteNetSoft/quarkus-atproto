package org.sitenetsoft.atproto.lexicon.codegen.model;

/** {@code token}: a named symbol with no data; its value is the {@code nsid#name} reference. */
public record LexToken(String description) implements LexDef {
  @Override
  public String type() {
    return "token";
  }
}
