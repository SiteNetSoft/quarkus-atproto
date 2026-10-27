package org.sitenetsoft.quarkus.atproto.lexicon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GraphemesTest {

  @Test
  void flagAndZwj() {
    assertEquals(1, Graphemes.count("👩‍💻"), "ZWJ sequence");
    assertEquals(1, Graphemes.count("🇨🇦"), "regional indicator flag");
    assertEquals(1, Graphemes.count("👍🏽"), "emoji with skin tone modifier");
    assertEquals(1, Graphemes.count("é"), "e + combining acute");
    assertEquals(3, Graphemes.count("a👩‍💻🇨🇦"));
    assertEquals(0, Graphemes.count(""));
    assertEquals(500, Graphemes.count("👩‍💻".repeat(500)));
  }

  @Test
  void utf8Length() {
    assertEquals(11, Graphemes.utf8Length("👩‍💻"));
    assertEquals(3, Graphemes.utf8Length("€"));
    assertEquals(1, Graphemes.utf8Length("a"));
  }
}
