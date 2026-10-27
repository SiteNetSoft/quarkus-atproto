package org.sitenetsoft.atproto.lexicon.codegen.model;

import java.util.List;

/** {@code query}: an XRPC HTTP GET. {@code parameters} and {@code output} may be {@code null}. */
public record LexQuery(String description, LexParams parameters, LexBody output, List<LexError> errors)
    implements LexDef {
  public LexQuery {
    errors = List.copyOf(errors);
  }

  @Override
  public String type() {
    return "query";
  }
}
