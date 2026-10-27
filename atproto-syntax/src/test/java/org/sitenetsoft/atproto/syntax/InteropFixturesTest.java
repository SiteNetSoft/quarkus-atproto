package org.sitenetsoft.atproto.syntax;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs every line of the bluesky-social/atproto-interop-tests syntax fixtures. */
class InteropFixturesTest {

  record Kind(String file, Function<String, Object> parser, Predicate<String> syntaxOnly) {}

  // Handle: the fixtures are syntax-level (they allow .local/.onion/.arpa), Handle.parse also applies the
  // disallowed-TLD policy, so the valid list is checked with Handle.isValidSyntax.
  static final List<Kind> KINDS = List.of(
      new Kind("did", Did::parse, null),
      new Kind("handle", Handle::parse, Handle::isValidSyntax),
      new Kind("nsid", Nsid::parse, null),
      new Kind("tid", Tid::parse, null),
      new Kind("recordkey", RecordKey::parse, null),
      new Kind("aturi", AtUri::parse, null),
      new Kind("datetime", Datetime::parse, null),
      new Kind("atidentifier", AtIdentifier::parse, null),
      new Kind("cid", Cid::parse, null),
      new Kind("language", Language::parse, null),
      // the syntax-only parses (Jackson, records read back) accept exactly the fixtures' valid lines
      new Kind("handle", Handle::parseSyntax, null),
      new Kind("aturi", AtUri::parseSyntax, null),
      new Kind("atidentifier", AtIdentifier::parseSyntax, null));

  static List<String> lines(String name) {
    try (InputStream in = InteropFixturesTest.class.getResourceAsStream("/interop/syntax/" + name)) {
      assertTrue(in != null, "missing fixture " + name);
      List<String> out = new ArrayList<>();
      for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1)) {
        if (l.isEmpty() || l.startsWith("#")) continue;
        out.add(l);
      }
      return out;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static Stream<Arguments> valid() {
    return cases("_syntax_valid.txt");
  }

  static Stream<Arguments> invalid() {
    return Stream.concat(cases("_syntax_invalid.txt"),
        Stream.of(new Kind("datetime", Datetime::parse, null), new Kind("language", Language::parse, null))
            .flatMap(k -> lines(k.file() + "_parse_invalid.txt").stream().map(l -> Arguments.of(k, l))));
  }

  static Stream<Arguments> cases(String suffix) {
    return KINDS.stream().flatMap(k -> lines(k.file() + suffix).stream().map(l -> Arguments.of(k, l)));
  }

  @ParameterizedTest(name = "{0} valid")
  @MethodSource("valid")
  void validLinesParse(Kind kind, String line) {
    if (kind.syntaxOnly() != null) {
      assertTrue(kind.syntaxOnly().test(line), line);
    } else {
      assertDoesNotThrow(() -> kind.parser().apply(line), line);
    }
  }

  @ParameterizedTest(name = "{0} invalid")
  @MethodSource("invalid")
  void invalidLinesRejected(Kind kind, String line) {
    assertThrows(SyntaxException.class, () -> kind.parser().apply(line), line);
  }

  @Test
  void fixturesAreNotEmpty() {
    assertTrue(lines("did_syntax_valid.txt").size() > 10);
    assertFalse(lines("handle_syntax_invalid.txt").isEmpty());
  }
}
