package org.sitenetsoft.quarkus.atproto.lexicon;

/**
 * One way a record breaks its Lexicon.
 *
 * @param path JSON Pointer to the offending value ({@code /title}, {@code /contributors/0/did}); {@code /} for the
 *     record itself
 * @param message what is wrong, in English
 */
public record ValidationProblem(String path, String message) {
  @Override
  public String toString() {
    return path + ": " + message;
  }
}
