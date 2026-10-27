package org.sitenetsoft.quarkus.atproto.xrpc;

import java.util.List;
import java.util.stream.Collectors;

import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.quarkus.atproto.lexicon.ValidationProblem;

/** A record broke its Lexicon (or was not a record of the collection) and was not sent. */
public class RecordValidationException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final transient Nsid collection;
  private final transient List<ValidationProblem> problems;

  public RecordValidationException(Nsid collection, List<ValidationProblem> problems) {
    super("Invalid " + collection + " record: "
        + problems.stream().map(ValidationProblem::toString).collect(Collectors.joining("; ")));
    this.collection = collection;
    this.problems = List.copyOf(problems);
  }

  public Nsid collection() {
    return collection;
  }

  /** What is wrong, never empty. */
  public List<ValidationProblem> problems() {
    return problems;
  }
}
