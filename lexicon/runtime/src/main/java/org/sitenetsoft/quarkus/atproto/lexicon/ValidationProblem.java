package org.sitenetsoft.quarkus.atproto.lexicon;

/**
 * One way a record breaks its Lexicon.
 *
 * @param path JSON Pointer to the offending value ({@code /title}, {@code /contributors/0/did}); {@code /} for the
 *     record itself; {@value #RKEY} for the record key ({@code LexiconValidator.validateKey})
 * @param message what is wrong, in English
 */
public record ValidationProblem(String path, String message) {
  /** The {@link #path} of a problem with the record key rather than the record. */
  public static final String RKEY = "rkey";

  @Override
  public String toString() {
    return path + ": " + message;
  }
}
