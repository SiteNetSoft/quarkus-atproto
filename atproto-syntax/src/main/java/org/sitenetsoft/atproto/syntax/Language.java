package org.sitenetsoft.atproto.syntax;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A BCP 47 language tag (well-formed syntax; primary language subtags are lowercase). */
public record Language(String value) {
  private static final Pattern GRANDFATHERED = Pattern.compile(
      "en-GB-oed|i-ami|i-bnn|i-default|i-enochian|i-hak|i-klingon|i-lux|i-mingo|i-navajo|i-pwn|i-tao|i-tay|i-tsu"
          + "|sgn-BE-FR|sgn-BE-NL|sgn-CH-DE|art-lojban|cel-gaulish|no-bok|no-nyn|zh-guoyu|zh-hakka|zh-min"
          + "|zh-min-nan|zh-xiang");
  private static final Pattern PATTERN = Pattern.compile(
      "(?:(?:[a-z]{2,3}(?:-[a-zA-Z]{3}){0,3}|[a-z]{5,8})"
          + "(?:-[a-zA-Z]{4})?(?:-(?:[a-zA-Z]{2}|[0-9]{3}))?"
          + "(?<variants>(?:-(?:[a-zA-Z0-9]{5,8}|[0-9][a-zA-Z0-9]{3}))*)"
          + "(?<extensions>(?:-[0-9A-WY-Za-wy-z](?:-[a-zA-Z0-9]{2,8})+)*)"
          + "(?:-[xX](?:-[a-zA-Z0-9]{1,8})+)?"
          + "|[xX](?:-[a-zA-Z0-9]{1,8})+)");

  public Language {
    if (!isValid(value)) {
      throw new SyntaxException("Invalid language tag: " + Did.abbreviate(value));
    }
  }

  public static Language parse(String s) {
    return new Language(s);
  }

  public static boolean isValid(String s) {
    if (s == null || s.length() > 128) {
      return false;
    }
    if (GRANDFATHERED.matcher(s).matches()) {
      return true;
    }
    Matcher m = PATTERN.matcher(s);
    if (!m.matches()) {
      return false;
    }
    String variants = m.group("variants");
    String extensions = m.group("extensions");
    if (variants != null && !variants.isEmpty()) {
      Set<String> seen = new HashSet<>();
      for (String v : variants.substring(1).split("-")) {
        if (!seen.add(v.toLowerCase(Locale.ROOT))) {
          return false;
        }
      }
    }
    if (extensions != null && !extensions.isEmpty()) {
      Set<String> seen = new HashSet<>();
      // one-character subtags are the extension singletons; each may appear once
      String[] subtags = extensions.substring(1).split("-");
      for (String t : subtags) {
        if (t.length() == 1 && !seen.add(t.toLowerCase(Locale.ROOT))) {
          return false;
        }
      }
    }
    return true;
  }

  @Override
  public String toString() {
    return value;
  }
}
