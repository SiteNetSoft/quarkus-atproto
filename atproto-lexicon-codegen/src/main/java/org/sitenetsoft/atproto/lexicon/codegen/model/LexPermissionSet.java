package org.sitenetsoft.atproto.lexicon.codegen.model;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code permission-set}: an OAuth scope bundle. Kept as raw JSON; the generator skips it. */
public record LexPermissionSet(String description, JsonNode raw) implements LexDef {
  @Override
  public String type() {
    return "permission-set";
  }
}
