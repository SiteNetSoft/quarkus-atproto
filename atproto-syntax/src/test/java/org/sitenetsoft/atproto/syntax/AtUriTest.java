package org.sitenetsoft.atproto.syntax;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AtUriTest {
  @Test void parsesFullForm() {
    AtUri u = AtUri.parse("at://did:plc:asdf123/app.bsky.feed.post/3jzfcijpj2z2a");
    assertEquals(Did.parse("did:plc:asdf123"), u.authority());
    assertEquals("app.bsky.feed.post", u.collection().orElseThrow().toString());
    assertEquals("3jzfcijpj2z2a", u.rkey().orElseThrow().toString());
    assertEquals("at://did:plc:asdf123/app.bsky.feed.post/3jzfcijpj2z2a", u.toString());
  }
  @Test void authorityOnly() {
    AtUri u = AtUri.parse("at://alice.bsky.social");
    assertTrue(u.collection().isEmpty());
    assertTrue(u.rkey().isEmpty());
  }
  @Test void rejectsQueryAndFragment() {
    assertFalse(AtUri.isValid("at://did:plc:asdf123/app.bsky.feed.post/x?q=1"));
    assertFalse(AtUri.isValid("at://did:plc:asdf123/app.bsky.feed.post#frag"));
    assertFalse(AtUri.isValid("at://did:plc:asdf123/"));
  }
  @Test void of() {
    AtUri u = AtUri.of(Did.parse("did:plc:a"), Nsid.parse("app.bsky.feed.post"), RecordKey.parse("self"));
    assertEquals("at://did:plc:a/app.bsky.feed.post/self", u.toString());
  }
  @Test void ofRejectsNulls() {
    Did d = Did.parse("did:plc:a");
    assertThrows(SyntaxException.class, () -> AtUri.of(null));
    assertThrows(SyntaxException.class, () -> AtUri.of(d, null));
    assertThrows(SyntaxException.class, () -> AtUri.of(null, Nsid.parse("a.b.c")));
  }
}
