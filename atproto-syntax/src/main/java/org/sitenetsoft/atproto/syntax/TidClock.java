package org.sitenetsoft.atproto.syntax;

import java.security.SecureRandom;
import java.time.Instant;

/** Source of strictly increasing microsecond timestamps for TIDs, plus this JVM's random clock id. */
final class TidClock {
  private static final long CLOCK_ID = new SecureRandom().nextInt(1024);
  private static long last;

  private TidClock() {}

  static long clockId() {
    return CLOCK_ID;
  }

  static synchronized long nextMicros() {
    Instant now = Instant.now();
    long micros = now.getEpochSecond() * 1_000_000L + now.getNano() / 1000;
    if (micros <= last) {
      micros = last + 1;
    }
    last = micros;
    return micros;
  }
}
