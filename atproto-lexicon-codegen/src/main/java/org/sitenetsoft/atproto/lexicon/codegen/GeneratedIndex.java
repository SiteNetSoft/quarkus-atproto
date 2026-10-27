package org.sitenetsoft.atproto.lexicon.codegen;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.sitenetsoft.atproto.syntax.Nsid;

/**
 * The index a Lexicon pack ships at {@value #RESOURCE}: a JSON object mapping {@code nsid#def} (main defs as
 * {@code nsid#main}) to the binary name of the Java type generated for it ({@code com.atproto.label.Defs$SelfLabels}).
 * Only defs of generated documents are listed: records, objects and unions map to their class; named
 * string/integer/boolean/blob/cid-link/unknown defs map to the Java type a ref to them takes. Subscriptions,
 * permission sets, tokens, bytes and array defs are not listed.
 */
public final class GeneratedIndex {
  public static final String RESOURCE = "META-INF/atproto/lexicons/index.json";
  private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {
  };

  private GeneratedIndex() {
  }

  /** The entries generating {@code toGenerate} from {@code set} produces. */
  public static Map<String, String> compute(LexiconSet set, Set<Nsid> toGenerate, Optional<String> packagePrefix) {
    return Generator.index(set, toGenerate, packagePrefix);
  }

  public static void write(Path file, Map<String, String> index) {
    try {
      if (file.getParent() != null) {
        Files.createDirectories(file.getParent());
      }
      Files.writeString(file, MAPPER.writeValueAsString(new TreeMap<>(index)) + "\n");
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write " + file, e);
    }
  }

  /** Reads an index file, or the {@value #RESOURCE} entry of a {@code .jar}. */
  public static Map<String, String> read(Path file) {
    try {
      if (file.getFileName().toString().endsWith(".jar")) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
          ZipEntry e = zip.getEntry(RESOURCE);
          if (e == null) {
            throw new LexiconException(file + ": no " + RESOURCE + " (not a Lexicon pack?)");
          }
          try (InputStream in = zip.getInputStream(e)) {
            return read(in);
          }
        }
      }
      try (InputStream in = Files.newInputStream(file)) {
        return read(in);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }

  public static Map<String, String> read(InputStream in) throws IOException {
    return new TreeMap<>(MAPPER.readValue(in, TYPE));
  }
}
