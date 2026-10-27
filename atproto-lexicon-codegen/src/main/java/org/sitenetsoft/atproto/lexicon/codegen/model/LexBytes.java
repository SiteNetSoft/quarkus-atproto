package org.sitenetsoft.atproto.lexicon.codegen.model;

/** {@code bytes}: raw binary data ({@code {"$bytes": base64}} in JSON). */
public record LexBytes(String description, Integer minLength, Integer maxLength) implements LexDef {
  @Override
  public String type() {
    return "bytes";
  }
}
