package org.sitenetsoft.atproto.lexicon.codegen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.sitenetsoft.atproto.lexicon.codegen.model.LexiconDoc;
import org.sitenetsoft.atproto.syntax.Nsid;

/**
 * Command line entry point, run by the Lexicon packs' builds through {@code exec-maven-plugin:java}:
 *
 * <pre>
 * --lexicons &lt;dir&gt;              Lexicon JSON to generate (every *.json below it)
 * --out &lt;dir&gt;                   source root to write the generated .java files into
 * --index &lt;file&gt;                where to write the generated index (META-INF/atproto/lexicons/index.json)
 * [--package-prefix &lt;p&gt;]        optional package prefix
 * [--dependency-index &lt;file&gt;]   index of a dependency pack (index.json, or a pack .jar); repeatable
 * [--dependency-lexicons &lt;dir&gt;] Lexicon JSON of dependencies, read for ref resolution only; repeatable
 * </pre>
 *
 * <p>Errors are thrown, never {@code System.exit}: the Maven plugin runs this in the build's own JVM.
 */
public final class Cli {
  private static final String USAGE = "usage: --lexicons <dir> --out <src dir> --index <file> "
      + "[--package-prefix <p>] [--dependency-index <index.json|pack.jar>]... [--dependency-lexicons <dir>]...";

  private Cli() {
  }

  public static void main(String[] args) {
    Path lexicons = null;
    Path out = null;
    Path index = null;
    Optional<String> prefix = Optional.empty();
    List<Path> depIndexes = new ArrayList<>();
    List<Path> depLexicons = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      if (i + 1 >= args.length) {
        throw new IllegalArgumentException("missing value for " + a + "\n" + USAGE);
      }
      String v = args[++i];
      switch (a) {
        case "--lexicons" -> lexicons = Path.of(v);
        case "--out" -> out = Path.of(v);
        case "--index" -> index = Path.of(v);
        case "--package-prefix" -> prefix = Optional.of(v).filter(p -> !p.isBlank());
        case "--dependency-index" -> depIndexes.add(Path.of(v));
        case "--dependency-lexicons" -> depLexicons.add(Path.of(v));
        default -> throw new IllegalArgumentException("unknown option " + a + "\n" + USAGE);
      }
    }
    if (lexicons == null || out == null || index == null) {
      throw new IllegalArgumentException("--lexicons, --out and --index are required\n" + USAGE);
    }
    Set<Nsid> toGenerate = LexiconSet.load(lexicons).docs().stream().map(LexiconDoc::id)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    List<Path> dirs = new ArrayList<>();
    dirs.add(lexicons);
    dirs.addAll(depLexicons);
    LexiconSet set = LexiconSet.load(dirs.toArray(Path[]::new));
    Map<String, String> dependencyIndex = new TreeMap<>();
    for (Path p : depIndexes) {
      dependencyIndex.putAll(GeneratedIndex.read(p));
    }
    List<Path> written = Generator.generate(set, toGenerate, dependencyIndex, out, prefix);
    GeneratedIndex.write(index, GeneratedIndex.compute(set, toGenerate, prefix));
    System.out.println("atproto-lexicon-codegen: " + toGenerate.size() + " Lexicons, " + written.size()
        + " Java files into " + out + ", index " + index);
  }
}
