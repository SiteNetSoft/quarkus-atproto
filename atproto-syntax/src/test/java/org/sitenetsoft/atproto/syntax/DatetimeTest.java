package org.sitenetsoft.atproto.syntax;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DatetimeTest {
  @Test void keepsOriginalString() {
    String s = "1985-04-12T23:20:50.123000Z";
    Datetime d = Datetime.parse(s);
    assertEquals(s, d.toString());
    assertEquals(Instant.parse("1985-04-12T23:20:50.123Z"), d.instant());
  }
  @Test void offsetConverts() {
    assertEquals(Instant.parse("1985-04-12T22:20:50Z"), Datetime.parse("1985-04-12T23:20:50+01:00").instant());
  }
  @Test void rejectsLowercaseZ() { assertFalse(Datetime.isValid("1985-04-12T23:20:50.123z")); }
  @Test void rejectsMinusZeroOffset() { assertFalse(Datetime.isValid("1985-04-12T23:20:50.123-00:00")); }
  @Test void ofInstant() {
    assertEquals("1985-04-12T23:20:50.123Z", Datetime.of(Instant.parse("1985-04-12T23:20:50.123Z")).toString());
  }
  @Test void noBypassConstructor() {
    assertEquals(0, Datetime.class.getConstructors().length);
    assertTrue(java.lang.reflect.Modifier.isFinal(Datetime.class.getModifiers()));
  }
  @Test void arbitraryFractionDigits() {
    String s = "1985-04-12T23:20:50." + "1".repeat(60) + "Z";
    assertEquals(s, Datetime.parse(s).toString());
  }
}
