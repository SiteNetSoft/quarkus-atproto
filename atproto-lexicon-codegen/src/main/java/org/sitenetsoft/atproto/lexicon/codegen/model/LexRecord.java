package org.sitenetsoft.atproto.lexicon.codegen.model;

/** {@code record}: a repository record type with its record-key type ({@code tid}, {@code any}, ...). */
public record LexRecord(String description, String key, LexObject record) implements LexDef {
  @Override
  public String type() {
    return "record";
  }
}
