package org.sitenetsoft.atproto.syntax;

import java.util.regex.Pattern;

/** A content identifier in its string form (CIDv1; the legacy {@code Qm...} CIDv0 form is rejected). */
public record Cid(String value) {
  private static final Pattern PATTERN = Pattern.compile("[a-zA-Z0-9+=]{8,256}");

  public Cid {
    if (!isValid(value)) {
      throw new SyntaxException("Invalid CID: " + Did.abbreviate(value));
    }
  }

  public static Cid parse(String s) {
    return new Cid(s);
  }

  public static boolean isValid(String s) {
    return s != null && !s.startsWith("Qm") && PATTERN.matcher(s).matches();
  }

  @Override
  public String toString() {
    return value;
  }
}
