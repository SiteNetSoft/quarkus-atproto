package org.sitenetsoft.atproto.lexicon.codegen;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {
  @TempDir Path tmp;

  @Test void generatesAndWritesIndexThenResolvesAgainstIt() throws Exception {
    Path coreLex = tmp.resolve("core-lex");
    Path siteLex = tmp.resolve("site-lex");
    copy(Fixtures.lexiconDir().resolve("com"), coreLex.resolve("com"));
    copy(Fixtures.lexiconDir().resolve("site"), siteLex.resolve("site"));
    Path coreIndex = tmp.resolve("core/META-INF/atproto/lexicons/index.json");
    Cli.main(new String[] {"--lexicons", coreLex.toString(), "--out", tmp.resolve("core-src").toString(),
        "--index", coreIndex.toString()});
    assertTrue(Files.exists(tmp.resolve("core-src/com/atproto/repo/StrongRef.java")));
    Map<String, String> index = GeneratedIndex.read(coreIndex);
    assertEquals("com.atproto.repo.StrongRef", index.get("com.atproto.repo.strongRef#main"));
    assertEquals(Fixtures.coreIndex(), index);

    Path siteIndex = tmp.resolve("site/index.json");
    Cli.main(new String[] {"--lexicons", siteLex.toString(), "--out", tmp.resolve("site-src").toString(),
        "--index", siteIndex.toString(), "--dependency-index", coreIndex.toString()});
    assertTrue(Files.readString(tmp.resolve("site-src/site/standard/Document.java"))
        .contains("StrongRef bskyPostRef"));
    assertEquals("site.standard.Document", GeneratedIndex.read(siteIndex).get("site.standard.document#main"));
  }

  @Test void badArgumentsThrowInsteadOfExiting() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> Cli.main(new String[] {"--out", tmp.toString()}));
    assertTrue(e.getMessage().contains("--lexicons"), e.getMessage());
  }

  private static void copy(Path from, Path to) throws Exception {
    try (var s = Files.walk(from)) {
      for (Path p : s.toList()) {
        Path target = to.resolve(from.relativize(p).toString());
        if (Files.isDirectory(p)) {
          Files.createDirectories(target);
        } else {
          Files.copy(p, target);
        }
      }
    }
  }
}
