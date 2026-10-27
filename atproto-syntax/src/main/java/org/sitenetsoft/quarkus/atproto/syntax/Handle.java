package org.sitenetsoft.quarkus.atproto.syntax;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** An internet-domain handle, stored lowercase. */
public record Handle(String value) implements AtIdentifier {
  private static final Pattern PATTERN = Pattern.compile(
      "([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?");
  private static final Set<String> DISALLOWED_TLDS =
      Set.of("alt", "arpa", "example", "internal", "invalid", "local", "localhost", "onion");
  private static final String INVALID_VALUE = "handle.invalid";

  /** The special placeholder for a handle that failed verification. */
  public static final Handle INVALID = new Handle(INVALID_VALUE);

  public Handle {
    if (value == null) {
      throw new SyntaxException("Invalid handle: null");
    }
    value = value.toLowerCase(Locale.ROOT);
    if (!isValidSyntax(value)) {
      throw new SyntaxException("Invalid handle: " + Did.abbreviate(value));
    }
    if (!value.equals(INVALID_VALUE)) {
      String tld = value.substring(value.lastIndexOf('.') + 1);
      if (DISALLOWED_TLDS.contains(tld)) {
        throw new SyntaxException("Handle uses a disallowed TLD: " + value);
      }
    }
  }

  /** Syntax only (regex and length), without the disallowed-TLD policy. */
  public static boolean isValidSyntax(String s) {
    return s != null && s.length() <= 253 && PATTERN.matcher(s).matches();
  }

  public static Handle parse(String s) {
    return new Handle(s);
  }

  public static boolean isValid(String s) {
    try {
      parse(s);
      return true;
    } catch (SyntaxException e) {
      return false;
    }
  }

  /** Cleans typical user input: strips a leading {@code @} and a trailing dot, lowercases, then parses. */
  public static Handle normalise(String userInput) {
    if (userInput == null) {
      throw new SyntaxException("Invalid handle: null");
    }
    String s = userInput.strip();
    if (s.startsWith("@")) {
      s = s.substring(1);
    }
    if (s.endsWith(".")) {
      s = s.substring(0, s.length() - 1);
    }
    return parse(s.toLowerCase(Locale.ROOT));
  }

  @Override
  public String toString() {
    return value;
  }
}
