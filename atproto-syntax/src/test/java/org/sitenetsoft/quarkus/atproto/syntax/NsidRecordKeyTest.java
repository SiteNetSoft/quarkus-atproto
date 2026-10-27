package org.sitenetsoft.quarkus.atproto.syntax;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class NsidRecordKeyTest {
  @Test void nsidParts() {
    Nsid n = Nsid.parse("Com.Example.fooBar");
    assertEquals("com.example", n.authority());
    assertEquals("fooBar", n.name());
    assertEquals(List.of("com", "example", "fooBar"), n.segments());
    assertEquals("com.example.fooBar", n.toString());
  }
  @Test void didParts() {
    Did d = Did.parse("did:plc:abc");
    assertEquals("plc", d.method());
    assertTrue(d.isBlessed());
    assertFalse(Did.parse("did:key:zQ3").isBlessed());
  }
  @Test void recordKeyTypes() {
    RecordKeyType lit = RecordKeyType.parse("literal:self");
    assertTrue(lit.accepts(RecordKey.parse("self")));
    assertFalse(lit.accepts(RecordKey.parse("other")));
    assertTrue(RecordKeyType.parse("tid").accepts(RecordKey.parse("3jzfcijpj2z2a")));
    assertFalse(RecordKeyType.parse("tid").accepts(RecordKey.parse("self")));
    assertTrue(RecordKeyType.parse("nsid").accepts(RecordKey.parse("app.bsky.feed.post")));
    assertTrue(RecordKeyType.parse("any").accepts(RecordKey.parse("x")));
    assertThrows(SyntaxException.class, () -> RecordKeyType.parse("bogus"));
  }
}
