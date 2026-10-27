package org.sitenetsoft.atproto.lexicon.codegen.model;

import java.util.List;

/** {@code procedure}: an XRPC HTTP POST. {@code parameters}, {@code input} and {@code output} may be {@code null}. */
public record LexProcedure(String description, LexParams parameters, LexBody input, LexBody output,
                           List<LexError> errors) implements LexDef {
  public LexProcedure {
    errors = List.copyOf(errors);
  }

  @Override
  public String type() {
    return "procedure";
  }
}
