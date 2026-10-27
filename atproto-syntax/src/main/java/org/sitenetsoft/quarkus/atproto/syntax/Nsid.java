package org.sitenetsoft.quarkus.atproto.syntax;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** A namespaced identifier such as {@code app.bsky.feed.post}. The authority is lowercased, the name is not. */
public record Nsid(String value) {
  private static final Pattern PATTERN = Pattern.compile(
      "[a-zA-Z]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+(\\.[a-zA-Z]([a-zA-Z0-9]{0,62})?)");

  public Nsid {
    if (value == null || value.length() > 317 || !PATTERN.matcher(value).matches()) {
      throw new SyntaxException("Invalid NSID: " + Did.abbreviate(value));
    }
    int last = value.lastIndexOf('.');
    value = value.substring(0, last).toLowerCase(Locale.ROOT) + value.substring(last);
  }

  public static Nsid parse(String s) {
    return new Nsid(s);
  }

  public static boolean isValid(String s) {
    try {
      parse(s);
      return true;
    } catch (SyntaxException e) {
      return false;
    }
  }

  /** The lowercase dotted prefix in NSID order (for {@code com.example.foo}: {@code com.example}). */
  public String authority() {
    return value.substring(0, value.lastIndexOf('.'));
  }

  /** The final, case-sensitive name segment. */
  public String name() {
    return value.substring(value.lastIndexOf('.') + 1);
  }

  public List<String> segments() {
    return List.of(value.split("\\.")).stream().toList();
  }

  /** The authority as a domain name (reversed segment order), e.g. {@code example.com}. */
  public String authorityDomain() {
    String[] parts = authority().split("\\.");
    StringBuilder sb = new StringBuilder();
    for (int i = parts.length - 1; i >= 0; i--) {
      sb.append(parts[i]);
      if (i > 0) {
        sb.append('.');
      }
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return value;
  }
}
