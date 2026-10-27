package org.sitenetsoft.atproto.lexicon.codegen.model;

/** {@code cid-link}: a CID ({@code {"$link": cid}} in JSON). */
public record LexCidLink(String description) implements LexDef {
  @Override
  public String type() {
    return "cid-link";
  }
}
