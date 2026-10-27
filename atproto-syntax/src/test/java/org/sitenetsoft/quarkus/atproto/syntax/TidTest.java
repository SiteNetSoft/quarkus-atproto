package org.sitenetsoft.quarkus.atproto.syntax;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TidTest {
  @Test void monotonic() {
    String prev = Tid.next().toString();
    for (int i = 0; i < 10_000; i++) {
      String n = Tid.next().toString();
      assertTrue(n.compareTo(prev) > 0, prev + " !< " + n);
      prev = n;
    }
  }
  @Test void firstCharRule() {
    assertTrue(Tid.isValid("3jzfcijpj2z2a"));
    assertFalse(Tid.isValid("kjzfcijpj2z2a"));
    assertTrue(Tid.next().toString().charAt(0) <= 'j');
  }
  @Test void timestampIsNow() {
    Instant t = Tid.next().timestamp();
    assertTrue(Duration.between(t, Instant.now()).abs().toSeconds() < 5);
  }
  @Test void roundTrip() {
    Tid t = Tid.next();
    assertEquals(t, Tid.parse(t.toString()));
  }
}
