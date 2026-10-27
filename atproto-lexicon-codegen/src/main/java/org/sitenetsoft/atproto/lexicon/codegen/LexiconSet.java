package org.sitenetsoft.atproto.lexicon.codegen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.sitenetsoft.atproto.lexicon.codegen.model.*;
import org.sitenetsoft.atproto.syntax.Nsid;

/**
 * A set of Lexicon documents keyed by NSID that resolves {@code #local}, {@code nsid} and {@code nsid#def} references
 * across files. Construction checks every reference whose target document is in the set: the def must exist, and a
 * union member must be an {@code object} or {@code record} (never a {@code token}). References to documents outside
 * the set are left to the generator, which resolves them through a dependency index.
 */
public final class LexiconSet {
  /** A def address: NSID plus def name ({@code main} for the primary def). */
  public record DefKey(Nsid nsid, String name) {
    /** {@code nsid#name}, the form used as {@link GeneratedIndex} key. */
    public String key() {
      return nsid.value() + "#" + name;
    }

    /** The {@code $type} value naming this def: {@code nsid} for {@code main}, else {@code nsid#name}. */
    public String typeId() {
      return name.equals("main") ? nsid.value() : key();
    }

    @Override
    public String toString() {
      return key();
    }
  }

  /** A resolved reference. */
  public record Resolved(DefKey key, LexDef def) {
  }

  private final Map<String, LexiconDoc> docs;

  private LexiconSet(Map<String, LexiconDoc> docs) {
    this.docs = docs;
  }

  public static LexiconSet of(Collection<LexiconDoc> documents) {
    Map<String, LexiconDoc> map = new TreeMap<>();
    for (LexiconDoc d : documents) {
      if (map.putIfAbsent(d.id().value(), d) != null) {
        throw new LexiconException(d.id() + ": defined more than once");
      }
    }
    LexiconSet set = new LexiconSet(map);
    set.validate();
    return set;
  }

  /** Loads every {@code *.json} file under the given directories. */
  public static LexiconSet load(Path... dirs) {
    List<LexiconDoc> all = new ArrayList<>();
    for (Path dir : dirs) {
      try (Stream<Path> s = Files.walk(dir)) {
        for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".json") && Files.isRegularFile(f))
            .sorted().toList()) {
          all.add(LexiconParser.parse(p));
        }
      } catch (IOException e) {
        throw new UncheckedIOException("cannot list " + dir, e);
      }
    }
    return of(all);
  }

  /** The documents, ordered by NSID. */
  public List<LexiconDoc> docs() {
    return List.copyOf(docs.values());
  }

  public Optional<LexiconDoc> doc(Nsid nsid) {
    return Optional.ofNullable(docs.get(nsid.value()));
  }

  /** Parses a reference as written in {@code context}: {@code #def}, {@code nsid} or {@code nsid#def}. */
  public static DefKey parseRef(String ref, Nsid context) {
    int hash = ref.indexOf('#');
    String nsid = hash < 0 ? ref : ref.substring(0, hash);
    String name = hash < 0 ? "main" : ref.substring(hash + 1);
    if (name.isEmpty()) {
      throw new LexiconException(context + ": invalid ref \"" + ref + "\"");
    }
    if (nsid.isEmpty()) {
      return new DefKey(context, name);
    }
    if (!Nsid.isValid(nsid)) {
      throw new LexiconException(context + ": invalid ref \"" + ref + "\"");
    }
    return new DefKey(Nsid.parse(nsid), name);
  }

  /** Resolves a def key; empty when its document is not in the set. Throws when the document lacks the def. */
  public Optional<Resolved> resolve(DefKey key) {
    LexiconDoc doc = docs.get(key.nsid().value());
    if (doc == null) {
      return Optional.empty();
    }
    LexDef def = doc.defs().get(key.name());
    if (def == null) {
      throw new LexiconException("unresolved ref " + key + ": " + key.nsid() + " has no def \"" + key.name() + "\"");
    }
    return Optional.of(new Resolved(key, def));
  }

  private void validate() {
    for (LexiconDoc doc : docs.values()) {
      doc.defs().forEach((name, def) -> walk(def, (d, unused) -> {
        if (d instanceof LexRef r) {
          resolve(parseRef(r.ref(), doc.id()));
        } else if (d instanceof LexUnion u) {
          for (String ref : u.refs()) {
            DefKey key = parseRef(ref, doc.id());
            resolve(key).ifPresent(res -> {
              if (!(res.def() instanceof LexObject) && !(res.def() instanceof LexRecord)) {
                throw new LexiconException(doc.id() + "#" + name + ": union ref " + key + " points to a "
                    + res.def().type() + "; union members must be objects or records");
              }
            });
          }
        }
      }));
    }
  }

  /** Visits {@code def} and every schema nested in it (properties, items, params, bodies), depth first. */
  static void walk(LexDef def, BiConsumer<LexDef, String> visitor) {
    walk(def, "", visitor);
  }

  private static void walk(LexDef def, String path, BiConsumer<LexDef, String> visitor) {
    if (def == null) {
      return;
    }
    visitor.accept(def, path);
    switch (def) {
      case LexRecord r -> walk(r.record(), path, visitor);
      case LexObject o -> o.properties().forEach((k, v) -> walk(v, path + "." + k, visitor));
      case LexParams p -> p.properties().forEach((k, v) -> walk(v, path + "." + k, visitor));
      case LexArray a -> walk(a.items(), path + "[]", visitor);
      case LexQuery q -> {
        walk(q.parameters(), path + ".parameters", visitor);
        walk(q.output() == null ? null : q.output().schema(), path + ".output", visitor);
      }
      case LexProcedure p -> {
        walk(p.parameters(), path + ".parameters", visitor);
        walk(p.input() == null ? null : p.input().schema(), path + ".input", visitor);
        walk(p.output() == null ? null : p.output().schema(), path + ".output", visitor);
      }
      case LexSubscription s -> {
        walk(s.parameters(), path + ".parameters", visitor);
        walk(s.message(), path + ".message", visitor);
      }
      default -> {
      }
    }
  }
}
