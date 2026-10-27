package org.sitenetsoft.atproto.lexicon.codegen;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.sitenetsoft.atproto.lexicon.codegen.model.LexiconDoc;
import org.sitenetsoft.atproto.syntax.Nsid;

final class Fixtures {
  private Fixtures() {
  }

  static Path lexiconDir() {
    try {
      return Path.of(Fixtures.class.getResource("/lexicons").toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  static LexiconSet all() {
    return LexiconSet.load(lexiconDir());
  }

  static LexiconSet only(String prefix) {
    return LexiconSet.of(all().docs().stream().filter(d -> d.id().value().startsWith(prefix)).toList());
  }

  static Set<Nsid> nsids(LexiconSet set, String prefix) {
    Set<Nsid> out = new TreeSet<>((a, b) -> a.value().compareTo(b.value()));
    set.docs().stream().map(LexiconDoc::id).filter(n -> n.value().startsWith(prefix)).forEach(out::add);
    return out;
  }

  /** The index the core pack's build would write. */
  static Map<String, String> coreIndex() {
    LexiconSet core = only("com.atproto.");
    return GeneratedIndex.compute(core, nsids(core, "com.atproto."), Optional.empty());
  }
}
