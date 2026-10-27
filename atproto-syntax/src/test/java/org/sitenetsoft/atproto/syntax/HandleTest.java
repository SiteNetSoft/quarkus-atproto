package org.sitenetsoft.atproto.syntax;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HandleTest {
  @Test void normalisesUserInput() {
    assertEquals("alice.bsky.social", Handle.normalise("@Alice.Bsky.Social.").toString());
  }
  @Test void storedLowercase() { assertEquals("xx.lcs.mit.edu", Handle.parse("XX.LCS.MIT.EDU").toString()); }
  @Test void rejectsDisallowedTld() {
    assertThrows(SyntaxException.class, () -> Handle.parse("x.local"));
    assertThrows(SyntaxException.class, () -> Handle.parse("x.onion"));
    assertFalse(Handle.isValid("x.arpa"));
    assertTrue(Handle.isValid("x.test"));
  }
  @Test void parseSyntaxSkipsTldPolicy() {
    assertEquals("alice.local", Handle.parseSyntax("Alice.Local").value());
    assertThrows(SyntaxException.class, () -> Handle.parseSyntax("not a handle"));
    assertEquals("at://alice.local/app.bsky.feed.post/x",
        AtUri.parseSyntax("at://alice.local/app.bsky.feed.post/x").toString());
    assertThrows(SyntaxException.class, () -> AtUri.parse("at://alice.local/app.bsky.feed.post/x"));
  }
  @Test void invalidConstant() {
    assertEquals("handle.invalid", Handle.INVALID.toString());
    assertEquals(Handle.INVALID, Handle.parse("handle.invalid"));
  }
}
