package org.sitenetsoft.atproto.syntax;

/** Thrown when a string is not a valid AT Protocol identifier of the requested type. */
public class SyntaxException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  public SyntaxException(String message) {
    super(message);
  }
}
