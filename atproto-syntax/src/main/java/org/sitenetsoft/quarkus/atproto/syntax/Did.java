package org.sitenetsoft.quarkus.atproto.syntax;

import java.util.Set;
import java.util.regex.Pattern;

/** A decentralised identifier, e.g. {@code did:plc:...}. */
public record Did(String value) implements AtIdentifier {
  private static final Pattern PATTERN = Pattern.compile("did:[a-z]+:[a-zA-Z0-9._:%-]*[a-zA-Z0-9._-]");
  private static final Set<String> BLESSED = Set.of("plc", "web");

  public Did {
    if (value == null || value.length() > 2048 || !PATTERN.matcher(value).matches()) {
      throw new SyntaxException("Invalid DID: " + abbreviate(value));
    }
  }

  static String abbreviate(String s) {
    return s == null ? "null" : s.length() > 100 ? s.substring(0, 100) + "..." : s;
  }

  public static Did parse(String s) {
    return new Did(s);
  }

  public static boolean isValid(String s) {
    return s != null && s.length() <= 2048 && PATTERN.matcher(s).matches();
  }

  public String method() {
    return value.substring(4, value.indexOf(':', 4));
  }

  public boolean isBlessed() {
    return BLESSED.contains(method());
  }

  @Override
  public String toString() {
    return value;
  }
}
