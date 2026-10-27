package org.sitenetsoft.quarkus.atproto.lexicons.standardsite;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.sitenetsoft.atproto.syntax.AtprotoSyntaxModule;
import org.sitenetsoft.quarkus.atproto.lexicon.AtprotoRecord;
import site.standard.Document;
import site.standard.Publication;

/** Real {@code site.standard.*} records (see the pack README) survive a read and write through the generated types. */
class PackRoundTripTest {
  private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new AtprotoSyntaxModule());
  private static final String INDEX = "META-INF/atproto/lexicons/index.json";

  @Test void realDocumentsRoundTrip() throws Exception {
    for (String name : List.of("document-pckt-labels.json", "document-wordpress-bskypostref.json")) {
      JsonNode original = record(name);
      Document doc = MAPPER.treeToValue(original, Document.class);
      assertEquals(Document.NSID, doc.$type());
      assertEquals(original, MAPPER.readTree(MAPPER.writeValueAsString(doc)), name);
    }
  }

  @Test void realDocumentsKeepTheirTypedParts() throws Exception {
    Document labelled = MAPPER.treeToValue(record("document-pckt-labels.json"), Document.class);
    assertInstanceOf(Document.Labels.SelfLabels.class, labelled.labels());
    assertInstanceOf(Document.Content.Unknown.class, labelled.content());
    assertEquals("image/jpeg", labelled.coverImage().mimeType());
    Document linked = MAPPER.treeToValue(record("document-wordpress-bskypostref.json"), Document.class);
    assertEquals("at://did:plc:g7j6qok5us4hjqlwjxwrrkjm/app.bsky.feed.post/3mmwlyioqc46v",
        linked.bskyPostRef().uri().toString());
  }

  @Test void publicationRoundTrip() throws Exception {
    JsonNode original = record("publication-sharpie.json");
    Publication pub = MAPPER.treeToValue(original, Publication.class);
    assertEquals("Sharpie Posts", pub.name());
    assertNotNull(pub.basicTheme());
    assertEquals(original, MAPPER.readTree(MAPPER.writeValueAsString(pub)));
    assertInstanceOf(AtprotoRecord.class, pub);
  }

  @Test void indexListsEveryGeneratedDef() throws Exception {
    Map<String, String> index = ownIndex("site.standard.");
    List<String> expected = new ArrayList<>();
    for (Path file : lexiconFiles()) {
      JsonNode doc = MAPPER.readTree(file.toFile());
      String nsid = doc.get("id").asText();
      doc.get("defs").fields().forEachRemaining(e -> {
        String type = e.getValue().get("type").asText();
        if (List.of("record", "object", "union").contains(type)) {
          expected.add(nsid + "#" + e.getKey());
        }
      });
    }
    assertFalse(expected.isEmpty());
    for (String key : expected) {
      assertTrue(index.containsKey(key), "index lacks " + key);
    }
    for (Map.Entry<String, String> e : index.entrySet()) {
      assertTrue(e.getKey().startsWith("site.standard."), "foreign entry " + e.getKey());
      assertDoesNotThrow(() -> Class.forName(e.getValue()), e.getValue());
    }
    assertEquals("site.standard.Document", index.get("site.standard.document#main"));
    assertEquals("site.standard.Publication$Preferences", index.get("site.standard.publication#preferences"));
    // Permission sets are shipped as JSON for include: scopes, not generated.
    assertFalse(index.keySet().stream().anyMatch(k -> k.startsWith("site.standard.authFull")));
    assertNotNull(PackRoundTripTest.class.getClassLoader()
        .getResource("META-INF/atproto/lexicons/site/standard/authFull.json"));
  }

  static JsonNode record(String name) throws Exception {
    try (InputStream in = PackRoundTripTest.class.getResourceAsStream("/records/" + name)) {
      assertNotNull(in, name);
      return MAPPER.readTree(in);
    }
  }

  /** This pack's index: the classpath also holds the core pack's. */
  static Map<String, String> ownIndex(String prefix) throws Exception {
    for (URL url : Collections.list(PackRoundTripTest.class.getClassLoader().getResources(INDEX))) {
      try (InputStream in = url.openStream()) {
        Map<String, String> m = new TreeMap<>();
        MAPPER.readTree(in).fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
        if (!m.isEmpty() && m.keySet().iterator().next().startsWith(prefix)) {
          return m;
        }
      }
    }
    throw new AssertionError("no " + INDEX + " for " + prefix + " on the classpath");
  }

  static List<Path> lexiconFiles() throws Exception {
    try (var s = Files.walk(Path.of("src/main/resources/META-INF/atproto/lexicons"))) {
      return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
    }
  }
}
