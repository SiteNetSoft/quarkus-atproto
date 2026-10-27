package org.sitenetsoft.atproto.syntax;

import java.util.regex.Pattern;

/** A record key: 1 to 512 characters of {@code A-Za-z0-9.-_:~}, never {@code .} or {@code ..}. */
public record RecordKey(String value) {
  private static final Pattern PATTERN = Pattern.compile("[A-Za-z0-9.\\-_:~]{1,512}");

  public RecordKey {
    if (!isValid(value)) {
      throw new SyntaxException("Invalid record key: " + Did.abbreviate(value));
    }
  }

  public static RecordKey parse(String s) {
    return new RecordKey(s);
  }

  public static boolean isValid(String s) {
    return s != null && !s.equals(".") && !s.equals("..") && PATTERN.matcher(s).matches();
  }

  @Override
  public String toString() {
    return value;
  }
}
