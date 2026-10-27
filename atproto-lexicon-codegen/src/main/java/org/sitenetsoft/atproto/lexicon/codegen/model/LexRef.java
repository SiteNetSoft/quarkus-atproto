package org.sitenetsoft.atproto.lexicon.codegen.model;

/** {@code ref}: a reference to another def, {@code #local}, {@code nsid} or {@code nsid#def}. */
public record LexRef(String description, String ref) implements LexDef {
  @Override
  public String type() {
    return "ref";
  }
}
