package org.sitenetsoft.quarkus.atproto.lexicon;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.atproto.syntax.Nsid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The Lexicon documents on the application's classpath: every {@code META-INF/atproto/lexicons/<nsid path>.json} of
 * the application and its dependencies (Lexicon packs), found at build time. No classpath scanning happens at run
 * time; a document is read from its resource the first time it is asked for.
 *
 * <p>Returned {@link JsonNode}s are shared: do not modify them.
 */
@ApplicationScoped
public class LexiconRegistry {
  /** Where Lexicon packs ship their JSON. */
  public static final String RESOURCE_DIR = "META-INF/atproto/lexicons";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static volatile Map<String, String> discovered = Map.of();

  private final Map<String, JsonNode> documents = new ConcurrentHashMap<>();

  static void discovered(Map<String, String> lexicons) {
    discovered = Map.copyOf(lexicons);
  }

  /** Whether a Lexicon document with this NSID is on the classpath. */
  public boolean knows(Nsid nsid) {
    return discovered.containsKey(nsid.value());
  }

  /** The NSIDs of every known Lexicon document. */
  public Set<Nsid> nsids() {
    return discovered.keySet().stream().map(Nsid::parse).collect(Collectors.toUnmodifiableSet());
  }

  /** The classpath resource of every known Lexicon document, by NSID. */
  public Map<String, String> resources() {
    return new TreeMap<>(discovered);
  }

  /** The whole Lexicon document ({@code lexicon}, {@code id}, {@code defs}, ...). */
  public Optional<JsonNode> document(Nsid nsid) {
    String resource = discovered.get(nsid.value());
    if (resource == null) {
      return Optional.empty();
    }
    return Optional.of(documents.computeIfAbsent(nsid.value(), id -> load(resource)));
  }

  /** The def {@code defName} ({@code main} for the primary def) of the document {@code nsid}. */
  public Optional<JsonNode> def(Nsid nsid, String defName) {
    return document(nsid).map(d -> d.path("defs").get(defName)).filter(JsonNode::isObject);
  }

  private static JsonNode load(String resource) {
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    if (cl == null) {
      cl = LexiconRegistry.class.getClassLoader();
    }
    try (InputStream in = cl.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Lexicon resource " + resource + " found at build time is missing");
      }
      return JSON.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read Lexicon resource " + resource, e);
    }
  }
}
