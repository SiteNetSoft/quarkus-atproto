package org.sitenetsoft.quarkus.atproto.lexicons.core;

import static org.junit.jupiter.api.Assertions.*;

import com.atproto.repo.CreateRecord;
import com.atproto.repo.RepoClient;
import com.atproto.repo.StrongRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.sitenetsoft.atproto.syntax.AtprotoSyntaxModule;

/** The generated {@code com.atproto.*} types read and write real data; the pack ships its JSON and index. */
class PackRoundTripTest {
  private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new AtprotoSyntaxModule());

  @Test void strongRefRoundTrip() throws Exception {
    JsonNode original;
    try (InputStream in = PackRoundTripTest.class.getResourceAsStream("/records/strongref.json")) {
      assertNotNull(in);
      original = MAPPER.readTree(in);
    }
    StrongRef ref = MAPPER.treeToValue(original, StrongRef.class);
    assertEquals("bafyreih7ihmehmg35id5x6hse5c4bbsjiddzseerbhjnpufwkfwolk3ghq", ref.cid().toString());
    assertEquals(original, MAPPER.readTree(MAPPER.writeValueAsString(ref)));
  }

  @Test void indexListsEveryGeneratedDef() throws Exception {
    Map<String, String> index = new TreeMap<>();
    try (InputStream in = PackRoundTripTest.class.getResourceAsStream("/META-INF/atproto/lexicons/index.json")) {
      assertNotNull(in, "index.json not packaged");
      MAPPER.readTree(in).fields().forEachRemaining(e -> index.put(e.getKey(), e.getValue().asText()));
    }
    List<String> expected = new ArrayList<>();
    try (var s = Files.walk(Path.of("src/main/resources/META-INF/atproto/lexicons"))) {
      for (Path file : s.filter(p -> p.toString().endsWith(".json")).toList()) {
        JsonNode doc = MAPPER.readTree(file.toFile());
        String nsid = doc.get("id").asText();
        doc.get("defs").fields().forEachRemaining(e -> {
          if (List.of("record", "object", "union").contains(e.getValue().get("type").asText())) {
            expected.add(nsid + "#" + e.getKey());
          }
        });
      }
    }
    assertFalse(expected.isEmpty());
    for (String key : expected) {
      assertTrue(index.containsKey(key), "index lacks " + key);
    }
    for (Map.Entry<String, String> e : index.entrySet()) {
      assertTrue(e.getKey().startsWith("com.atproto."), "foreign entry " + e.getKey());
      assertDoesNotThrow(() -> Class.forName(e.getValue()), e.getValue());
    }
    assertEquals("com.atproto.repo.StrongRef", index.get("com.atproto.repo.strongRef#main"));
    assertEquals("com.atproto.label.Defs$SelfLabels", index.get("com.atproto.label.defs#selfLabels"));
  }

  @Test void packShipsLexiconJsonAndClient() throws Exception {
    assertNotNull(PackRoundTripTest.class.getResource("/META-INF/atproto/lexicons/com/atproto/repo/createRecord.json"));
    assertEquals("com.atproto.repo.createRecord", CreateRecord.NSID);
    assertNotNull(RepoClient.class.getConstructor(org.sitenetsoft.quarkus.atproto.xrpc.api.ReactiveXrpcClient.class));
  }
}
