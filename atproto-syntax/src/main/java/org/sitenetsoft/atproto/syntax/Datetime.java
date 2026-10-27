package org.sitenetsoft.atproto.syntax;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An RFC 3339 datetime (AT Protocol profile). {@link #toString()} returns the original string. */
public record Datetime(String value, Instant instant) {
  private static final Pattern PATTERN = Pattern.compile(
      "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:\\d{2})");

  public static Datetime parse(String s) {
    if (s == null || s.length() > 64) {
      throw new SyntaxException("Invalid datetime: " + Did.abbreviate(s));
    }
    Matcher m = PATTERN.matcher(s);
    if (!m.matches() || s.endsWith("-00:00")) {
      throw new SyntaxException("Invalid datetime: " + Did.abbreviate(s));
    }
    try {
      String frac = m.group(7);
      int nanos = 0;
      if (frac != null) {
        String f = frac.length() > 9 ? frac.substring(0, 9) : frac;
        nanos = Integer.parseInt(f) * (int) Math.pow(10, 9 - f.length());
      }
      String z = m.group(8);
      ZoneOffset offset = z.equals("Z") ? ZoneOffset.UTC : ZoneOffset.of(z);
      OffsetDateTime odt = OffsetDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
          Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
          Integer.parseInt(m.group(6)), nanos, offset);
      Instant instant = odt.toInstant();
      if (instant.atOffset(ZoneOffset.UTC).getYear() < 0) {
        throw new SyntaxException("Datetime normalises to a negative year: " + s);
      }
      return new Datetime(s, instant);
    } catch (DateTimeException e) {
      throw new SyntaxException("Invalid datetime: " + Did.abbreviate(s) + " (" + e.getMessage() + ")");
    }
  }

  public static boolean isValid(String s) {
    try {
      parse(s);
      return true;
    } catch (SyntaxException e) {
      return false;
    }
  }

  /** The preferred form: UTC, millisecond precision, {@code Z}. */
  public static Datetime of(Instant instant) {
    String s = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(instant.atOffset(ZoneOffset.UTC));
    return parse(s);
  }

  public static Datetime now() {
    return of(Instant.now());
  }

  @Override
  public String toString() {
    return value;
  }
}
