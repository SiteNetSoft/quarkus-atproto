package org.sitenetsoft.atproto.syntax;

/** Either a {@link Did} or a {@link Handle}. */
public sealed interface AtIdentifier permits Did, Handle {
  String value();

  static AtIdentifier parse(String s) {
    if (s == null) {
      throw new SyntaxException("Invalid AT identifier: null");
    }
    return s.startsWith("did:") ? Did.parse(s) : Handle.parse(s);
  }

  static boolean isValid(String s) {
    try {
      parse(s);
      return true;
    } catch (SyntaxException e) {
      return false;
    }
  }
}
