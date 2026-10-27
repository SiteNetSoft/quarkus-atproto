package org.sitenetsoft.atproto.lexicon.codegen.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code params}: the query-string parameters of a query, procedure or subscription. */
public record LexParams(String description, List<String> required, Map<String, LexDef> properties)
    implements LexDef {
  public LexParams {
    required = List.copyOf(required);
    properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
  }

  @Override
  public String type() {
    return "params";
  }
}
