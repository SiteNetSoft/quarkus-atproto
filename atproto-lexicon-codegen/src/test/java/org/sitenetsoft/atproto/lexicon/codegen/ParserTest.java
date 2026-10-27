package org.sitenetsoft.atproto.lexicon.codegen;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.sitenetsoft.atproto.lexicon.codegen.model.*;

class ParserTest {
  private static List<Path> files(String sub) throws IOException {
    try (Stream<Path> s = Files.walk(Fixtures.lexiconDir().resolve(sub))) {
      return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
    }
  }

  private static String nsidOf(Path file) {
    String rel = Fixtures.lexiconDir().relativize(file).toString();
    return rel.substring(0, rel.length() - ".json".length()).replace('/', '.');
  }

  @Test void parsesEveryCorePackFile() throws IOException {
    List<Path> files = files("com/atproto");
    assertEquals(12, files.size());
    for (Path f : files) {
      LexiconDoc doc = LexiconParser.parse(f);
      assertEquals(nsidOf(f), doc.id().value(), f.toString());
      assertEquals(1, doc.lexicon());
      assertFalse(doc.defs().isEmpty());
    }
    LexiconDoc create = LexiconParser.parse(Fixtures.lexiconDir().resolve("com/atproto/repo/createRecord.json"));
    LexProcedure p = assertInstanceOf(LexProcedure.class, create.defs().get("main"));
    LexObject in = assertInstanceOf(LexObject.class, p.input().schema());
    assertEquals(List.of("repo", "collection", "rkey", "validate", "record", "swapCommit"),
        List.copyOf(in.properties().keySet()));
    assertInstanceOf(LexUnknown.class, in.properties().get("record"));
    assertEquals("at-identifier", ((LexString) in.properties().get("repo")).format());
    LexObject out = assertInstanceOf(LexObject.class, p.output().schema());
    assertEquals("com.atproto.repo.defs#commitMeta", ((LexRef) out.properties().get("commit")).ref());
    assertEquals(List.of("valid", "unknown"), ((LexString) out.properties().get("validationStatus")).knownValues());

    LexiconDoc upload = LexiconParser.parse(Fixtures.lexiconDir().resolve("com/atproto/repo/uploadBlob.json"));
    LexProcedure u = (LexProcedure) upload.defs().get("main");
    assertEquals("*/*", u.input().encoding());
    assertNull(u.input().schema());
    assertInstanceOf(LexBlob.class, ((LexObject) u.output().schema()).properties().get("blob"));

    LexiconDoc put = LexiconParser.parse(Fixtures.lexiconDir().resolve("com/atproto/repo/putRecord.json"));
    assertEquals(List.of("swapRecord"), ((LexObject) ((LexProcedure) put.defs().get("main")).input().schema()).nullable());

    LexiconDoc label = LexiconParser.parse(Fixtures.lexiconDir().resolve("com/atproto/label/defs.json"));
    assertEquals(8, ((LexString) label.defs().get("labelValue")).knownValues().size());
    assertInstanceOf(LexBytes.class, ((LexObject) label.defs().get("label")).properties().get("sig"));

    LexQuery list = (LexQuery) LexiconParser.parse(Fixtures.lexiconDir().resolve("com/atproto/repo/listRecords.json"))
        .defs().get("main");
    assertEquals(List.of("repo", "collection"), list.parameters().required());
    assertEquals(50L, ((LexInteger) list.parameters().properties().get("limit")).defaultValue());

    assertDoesNotThrow(() -> Fixtures.only("com.atproto."));
  }

  @Test void parsesStandardSite() throws IOException {
    List<Path> files = files("site/standard");
    assertEquals(8, files.size());
    for (Path f : files) {
      assertEquals(nsidOf(f), LexiconParser.parse(f).id().value());
    }
    LexiconDoc doc = LexiconParser.parse(Fixtures.lexiconDir().resolve("site/standard/document.json"));
    LexRecord rec = assertInstanceOf(LexRecord.class, doc.defs().get("main"));
    assertEquals("tid", rec.key());
    assertEquals(List.of("site", "title", "publishedAt"), rec.record().required());
    LexUnion content = (LexUnion) rec.record().properties().get("content");
    assertFalse(content.closed());
    assertTrue(content.refs().isEmpty());
    assertEquals(List.of("com.atproto.label.defs#selfLabels"), ((LexUnion) rec.record().properties().get("labels")).refs());
    assertInstanceOf(LexBlob.class, rec.record().properties().get("coverImage"));
    LexArray contributors = (LexArray) rec.record().properties().get("contributors");
    assertEquals("#contributor", ((LexRef) contributors.items()).ref());
    assertInstanceOf(LexObject.class, doc.defs().get("contributor"));

    LexiconDoc full = LexiconParser.parse(Fixtures.lexiconDir().resolve("site/standard/authFull.json"));
    assertInstanceOf(LexPermissionSet.class, full.defs().get("main"));
    LexiconDoc color = LexiconParser.parse(Fixtures.lexiconDir().resolve("site/standard/theme/color.json"));
    assertFalse(color.defs().containsKey("main"));
    assertEquals(2, color.defs().size());

    assertDoesNotThrow(Fixtures::all);
  }

  @Test void rejectsSecondPrimaryDef() {
    String json = """
        {"lexicon":1,"id":"com.example.thing","defs":{
          "main":{"type":"record","key":"tid","record":{"type":"object","properties":{}}},
          "other":{"type":"record","key":"tid","record":{"type":"object","properties":{}}}}}
        """;
    LexiconException e = assertThrows(LexiconException.class, () -> LexiconParser.parse(json));
    assertTrue(e.getMessage().contains("com.example.thing#other"), e.getMessage());
    assertTrue(e.getMessage().contains("record"), e.getMessage());
  }

  @Test void rejectsUnionOfToken() {
    String json = """
        {"lexicon":1,"id":"com.example.thing","defs":{
          "main":{"type":"object","properties":{"kind":{"type":"union","refs":["#red"]}}},
          "red":{"type":"token","description":"A colour."}}}
        """;
    LexiconDoc doc = LexiconParser.parse(json);
    LexiconException e = assertThrows(LexiconException.class, () -> LexiconSet.of(List.of(doc)));
    assertTrue(e.getMessage().contains("com.example.thing#red"), e.getMessage());
    assertTrue(e.getMessage().contains("token"), e.getMessage());
  }

  @Test void rejectsWrongLexiconVersionAndUnknownType() {
    assertThrows(LexiconException.class, () -> LexiconParser.parse("""
        {"lexicon":2,"id":"com.example.thing","defs":{"main":{"type":"token"}}}"""));
    LexiconException e = assertThrows(LexiconException.class, () -> LexiconParser.parse("""
        {"lexicon":1,"id":"com.example.thing","defs":{"main":{"type":"float"}}}"""));
    assertTrue(e.getMessage().contains("float"), e.getMessage());
  }

  @Test void rejectsRequiredOrNullableNamesNotInProperties() {
    LexiconException e = assertThrows(LexiconException.class, () -> LexiconParser.parse("""
        {"lexicon":1,"id":"com.example.thing","defs":{"main":{"type":"object",
          "required":["title","colour"],"properties":{"title":{"type":"string"}}}}}"""));
    assertTrue(e.getMessage().contains("com.example.thing#main") && e.getMessage().contains("\"colour\""),
        e.getMessage());
    e = assertThrows(LexiconException.class, () -> LexiconParser.parse("""
        {"lexicon":1,"id":"com.example.thing","defs":{"main":{"type":"object",
          "nullable":["ghost"],"properties":{"title":{"type":"string"}}}}}"""));
    assertTrue(e.getMessage().contains("nullable") && e.getMessage().contains("\"ghost\""), e.getMessage());
    e = assertThrows(LexiconException.class, () -> LexiconParser.parse("""
        {"lexicon":1,"id":"com.example.thing","defs":{"main":{"type":"query",
          "parameters":{"type":"params","required":["q"],"properties":{"limit":{"type":"integer"}}}}}}"""));
    assertTrue(e.getMessage().contains("parameters") && e.getMessage().contains("\"q\""), e.getMessage());
  }
}
