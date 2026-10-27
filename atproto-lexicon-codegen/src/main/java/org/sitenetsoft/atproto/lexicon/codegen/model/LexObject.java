package org.sitenetsoft.atproto.lexicon.codegen.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code object}: named properties in schema order, with the required and nullable property names. */
public record LexObject(String description, List<String> required, List<String> nullable,
                        Map<String, LexDef> properties) implements LexDef {
  public LexObject {
    required = List.copyOf(required);
    nullable = List.copyOf(nullable);
    properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
  }

  @Override
  public String type() {
    return "object";
  }
}
