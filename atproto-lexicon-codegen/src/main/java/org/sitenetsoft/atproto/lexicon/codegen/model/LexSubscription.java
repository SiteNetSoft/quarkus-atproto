package org.sitenetsoft.atproto.lexicon.codegen.model;

/** {@code subscription}: an event stream. Parsed for completeness; the generator skips it. */
public record LexSubscription(String description, LexParams parameters, LexDef message) implements LexDef {
  @Override
  public String type() {
    return "subscription";
  }
}
