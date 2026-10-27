package org.sitenetsoft.atproto.lexicon.codegen;

/** An invalid or unsupported Lexicon, or a reference that cannot be resolved. The message names the def. */
public class LexiconException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public LexiconException(String message) {
    super(message);
  }

  public LexiconException(String message, Throwable cause) {
    super(message, cause);
  }
}
