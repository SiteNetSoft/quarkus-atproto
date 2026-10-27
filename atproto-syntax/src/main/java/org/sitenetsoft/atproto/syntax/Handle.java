package org.sitenetsoft.atproto.syntax;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An internet-domain handle, stored lowercase. The canonical constructor checks the syntax only (as the AT Protocol
 * syntax fixtures and the Lexicon {@code handle} format do); {@link #parse} and {@link #normalise} also refuse the
 * disallowed TLDs ({@code .local}, {@code .onion}, ...), which is the policy for user input such as a sign-in.
 * {@link #parseSyntax} is the syntax-only parse, for data written by others (records read back).
 */
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
  }

  /** Syntax only (regex and length), without the disallowed-TLD policy. */
  public static boolean isValidSyntax(String s) {
    return s != null && s.length() <= 253 && PATTERN.matcher(s).matches();
  }

  /** Syntax and the disallowed-TLD policy. */
  public static Handle parse(String s) {
    Handle h = new Handle(s);
    if (!h.value.equals(INVALID_VALUE)) {
      String tld = h.value.substring(h.value.lastIndexOf('.') + 1);
      if (DISALLOWED_TLDS.contains(tld)) {
        throw new SyntaxException("Handle uses a disallowed TLD: " + h.value);
      }
    }
    return h;
  }

  /** Syntax only (lowercased), without the disallowed-TLD policy: what the Lexicon {@code handle} format accepts. */
  public static Handle parseSyntax(String s) {
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
