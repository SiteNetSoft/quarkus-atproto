package org.sitenetsoft.quarkus.atproto.lexicon;

import java.text.BreakIterator;

/**
 * String length measures used by Lexicon {@code string} constraints: {@code minGraphemes}/{@code maxGraphemes} count
 * extended grapheme clusters (what a reader sees as one character: {@code 👩‍💻}, a flag, {@code e} plus a combining
 * accent), {@code minLength}/{@code maxLength} count UTF-8 bytes.
 */
public final class Graphemes {
  private Graphemes() {
  }

  /**
   * The number of extended grapheme clusters, from the JDK's {@link BreakIterator#getCharacterInstance()} (which
   * follows Unicode extended grapheme cluster rules since JDK 20).
   */
  public static int count(String s) {
    if (s.isEmpty()) {
      return 0;
    }
    BreakIterator it = BreakIterator.getCharacterInstance();
    it.setText(s);
    int n = 0;
    while (it.next() != BreakIterator.DONE) {
      n++;
    }
    return n;
  }

  /** The length of {@code s} encoded as UTF-8, without allocating the encoding. */
  public static int utf8Length(String s) {
    int bytes = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x80) {
        bytes += 1;
      } else if (c < 0x800) {
        bytes += 2;
      } else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
        bytes += 4;
        i++;
      } else {
        bytes += 3; // rest of the BMP; a lone surrogate is counted as 3 bytes too (as in WTF-8)
      }
    }
    return bytes;
  }
}
