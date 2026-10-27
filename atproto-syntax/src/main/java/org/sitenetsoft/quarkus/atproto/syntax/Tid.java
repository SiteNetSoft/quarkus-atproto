package org.sitenetsoft.quarkus.atproto.syntax;

import java.time.Instant;
import java.util.regex.Pattern;

/** A timestamp identifier: 13 base32-sortable chars, 53 bits of microseconds and a 10-bit clock id. */
public record Tid(String value) {
  static final String ALPHABET = "234567abcdefghijklmnopqrstuvwxyz";
  private static final Pattern PATTERN =
      Pattern.compile("[234567abcdefghij][234567abcdefghijklmnopqrstuvwxyz]{12}");

  public Tid {
    if (value == null || !PATTERN.matcher(value).matches()) {
      throw new SyntaxException("Invalid TID: " + Did.abbreviate(value));
    }
  }

  public static Tid parse(String s) {
    return new Tid(s);
  }

  public static boolean isValid(String s) {
    return s != null && PATTERN.matcher(s).matches();
  }

  /** A new TID, strictly greater than any previously returned in this JVM. */
  public static Tid next() {
    return ofMicros(TidClock.nextMicros(), TidClock.clockId());
  }

  static Tid ofMicros(long micros, long clockId) {
    long v = (micros << 10) | (clockId & 0x3FF);
    char[] out = new char[13];
    for (int i = 0; i < 13; i++) {
      out[i] = ALPHABET.charAt((int) ((v >>> ((12 - i) * 5)) & 31));
    }
    return new Tid(new String(out));
  }

  private long bits() {
    long v = 0;
    for (int i = 0; i < 13; i++) {
      v = (v << 5) | ALPHABET.indexOf(value.charAt(i));
    }
    return v;
  }

  public Instant timestamp() {
    long micros = bits() >>> 10;
    return Instant.ofEpochSecond(micros / 1_000_000L, (micros % 1_000_000L) * 1000);
  }

  @Override
  public String toString() {
    return value;
  }
}
