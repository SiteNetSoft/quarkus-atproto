package org.sitenetsoft.atproto.lexicon.codegen;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sitenetsoft.atproto.lexicon.codegen.model.LexiconDoc;
import org.sitenetsoft.atproto.syntax.Nsid;

class GeneratorGoldenTest {
  @TempDir Path out;

  private static String golden(String path) throws IOException {
    try (InputStream in = GeneratorGoldenTest.class.getResourceAsStream("/golden/" + path)) {
      assertNotNull(in, "missing golden file " + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private void generateStandardSite() {
    LexiconSet set = Fixtures.all();
    Generator.generate(set, Fixtures.nsids(set, "site.standard."), Fixtures.coreIndex(), out, Optional.empty());
  }

  @Test void siteStandardDocument() throws IOException {
    generateStandardSite();
    assertEquals(golden("site/standard/Document.java"), Files.readString(out.resolve("site/standard/Document.java")));
  }

  @Test void coreCreateRecordAndClient() throws IOException {
    LexiconSet core = Fixtures.only("com.atproto.");
    Generator.generate(core, Fixtures.nsids(core, "com.atproto."), Map.of(), out, Optional.empty());
    assertEquals(golden("com/atproto/repo/CreateRecord.java"),
        Files.readString(out.resolve("com/atproto/repo/CreateRecord.java")));
    assertEquals(golden("com/atproto/repo/RepoClient.java"),
        Files.readString(out.resolve("com/atproto/repo/RepoClient.java")));
  }

  @Test void openUnionHasUnknown() throws IOException {
    generateStandardSite();
    String doc = Files.readString(out.resolve("site/standard/Document.java"));
    // content: open union with no refs -> only the Unknown variant
    assertTrue(doc.contains("public sealed interface Content permits Content.Unknown"), doc);
    assertTrue(doc.contains("defaultImpl = Content.Unknown.class"), doc);
    assertTrue(doc.contains("record Unknown(JsonNode raw)"), doc);
    // labels: open union (closed defaults to false) -> the ref variant plus Unknown
    assertTrue(doc.contains("public sealed interface Labels permits Labels.SelfLabels, Labels.Unknown"), doc);
    assertTrue(doc.contains("name = \"com.atproto.label.defs#selfLabels\""), doc);
  }

  @Test void closedUnionHasNoUnknown() throws IOException {
    LexiconDoc doc = LexiconParser.parse("""
        {"lexicon":1,"id":"com.example.shape","defs":{
          "main":{"type":"object","properties":{"kind":{"type":"union","closed":true,"refs":["#square"]}}},
          "square":{"type":"object","properties":{"side":{"type":"integer"}}}}}
        """);
    Generator.generate(LexiconSet.of(List.of(doc)), Set.of(Nsid.parse("com.example.shape")), Map.of(), out,
        Optional.empty());
    String src = Files.readString(out.resolve("com/example/Shape.java"));
    assertTrue(src.contains("public sealed interface Kind permits Kind.Square {"), src);
    assertFalse(src.contains("record Unknown"), src);
    assertFalse(src.contains("defaultImpl"), src);
  }

  @Test void skipsPermissionSetsAndIndexesOnlyGeneratedDefs() {
    LexiconSet set = Fixtures.all();
    List<Path> files = Generator.generate(set, Fixtures.nsids(set, "site.standard."), Fixtures.coreIndex(), out,
        Optional.empty());
    assertFalse(Files.exists(out.resolve("site/standard/AuthFull.java")));
    assertTrue(files.contains(out.resolve("site/standard/Document.java")));
    Map<String, String> index = GeneratedIndex.compute(set, Fixtures.nsids(set, "site.standard."), Optional.empty());
    assertEquals("site.standard.Document", index.get("site.standard.document#main"));
    assertEquals("site.standard.Document$Contributor", index.get("site.standard.document#contributor"));
    assertEquals("site.standard.theme.Color$Rgb", index.get("site.standard.theme.color#rgb"));
    assertFalse(index.containsKey("site.standard.authFull#main"));
    assertFalse(index.keySet().stream().anyMatch(k -> k.startsWith("com.atproto.")));
  }

  @Test void nullableOptionalIsPlainNullableType() throws IOException {
    LexiconSet core = Fixtures.only("com.atproto.");
    Generator.generate(core, Fixtures.nsids(core, "com.atproto."), Map.of(), out, Optional.empty());
    String put = Files.readString(out.resolve("com/atproto/repo/PutRecord.java"));
    assertTrue(put.contains("@JsonProperty(\"swapRecord\") Cid swapRecord"), put);
    assertFalse(put.contains("Optional<"), put);
    String label = Files.readString(out.resolve("com/atproto/label/Defs.java"));
    assertTrue(label.contains("@JsonProperty(\"sig\") Bytes sig"), label);
  }

  @Test void mainRecordPropertyNamedNsidFails() {
    LexiconDoc doc = LexiconParser.parse("""
        {"lexicon":1,"id":"com.example.thing","defs":{"main":{"type":"record","key":"tid",
          "record":{"type":"object","properties":{"NSID":{"type":"string"}}}}}}
        """);
    LexiconException e = assertThrows(LexiconException.class, () -> Generator.generate(LexiconSet.of(List.of(doc)),
        Set.of(doc.id()), Map.of(), out, Optional.empty()));
    assertTrue(e.getMessage().contains("com.example.thing#main.NSID") && e.getMessage().contains("constant"),
        e.getMessage());
  }

  @Test void paramNamedToMapFails() {
    LexiconDoc doc = LexiconParser.parse("""
        {"lexicon":1,"id":"com.example.find","defs":{"main":{"type":"query",
          "parameters":{"type":"params","properties":{"toMap":{"type":"string"}}}}}}
        """);
    LexiconException e = assertThrows(LexiconException.class, () -> Generator.generate(LexiconSet.of(List.of(doc)),
        Set.of(doc.id()), Map.of(), out, Optional.empty()));
    assertTrue(e.getMessage().contains("com.example.find#main.parameters") && e.getMessage().contains("toMap"),
        e.getMessage());
  }
}
