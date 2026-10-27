package org.sitenetsoft.atproto.lexicon.codegen;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sitenetsoft.atproto.syntax.AtprotoSyntaxModule;

class CompileTest {
  @TempDir Path core;
  @TempDir Path site;

  private List<Path> generateCore() {
    LexiconSet set = Fixtures.only("com.atproto.");
    return Generator.generate(set, Fixtures.nsids(set, "com.atproto."), Map.of(), core, Optional.empty());
  }

  @Test void generatedCorePackAndStandardSiteCompile() throws Exception {
    List<Path> sources = new ArrayList<>(generateCore());
    LexiconSet all = Fixtures.all();
    sources.addAll(Generator.generate(all, Fixtures.nsids(all, "site.standard."), Fixtures.coreIndex(), site,
        Optional.empty()));
    ClassLoader cl = InMemoryCompiler.compile(sources);

    Class<?> document = cl.loadClass("site.standard.Document");
    assertEquals("site.standard.document", document.getField("NSID").get(null));
    assertTrue(Class.forName("org.sitenetsoft.quarkus.atproto.lexicon.AtprotoRecord").isAssignableFrom(document));
    assertEquals(List.of("path", "site", "tags", "links", "title", "labels", "content", "updatedAt", "coverImage",
            "bskyPostRef", "description", "publishedAt", "textContent", "contributors"),
        Arrays.stream(document.getRecordComponents()).map(RecordComponent::getName).toList());
    Class<?> repoClient = cl.loadClass("com.atproto.repo.RepoClient");
    assertTrue(Arrays.stream(repoClient.getMethods()).anyMatch(m -> m.getName().equals("createRecord")));
    assertEquals("valid", cl.loadClass("com.atproto.repo.CreateRecord$Output").getField("VALIDATION_STATUS_VALID")
        .get(null));
    assertEquals("!hide", cl.loadClass("com.atproto.label.Defs$LabelValue").getField("HIDE").get(null));

    // Wire form: $type on the record and on union variants, open unions keep unknown members verbatim.
    ObjectMapper mapper = new ObjectMapper().registerModule(new AtprotoSyntaxModule());
    String json = """
        {"$type":"site.standard.document","site":"https://example.com","title":"Hello",
         "labels":{"$type":"com.atproto.label.defs#selfLabels","values":[{"val":"nudity"}]},
         "content":{"$type":"com.example.markdown","text":"# hi"},
         "bskyPostRef":{"uri":"at://did:plc:abc/app.bsky.feed.post/3jzfcijpj2z2a","cid":"bafyreie5737gdxlw5i64vzichcalba3z2v5n6icifvx5xytvske7mr3hpm"},
         "publishedAt":"2026-10-26T12:00:00.000Z",
         "contributors":[{"did":"did:plc:abc","role":"author"}]}
        """;
    Object doc = mapper.readValue(json, document);
    assertEquals(cl.loadClass("site.standard.Document$Labels$SelfLabels"),
        document.getMethod("labels").invoke(doc).getClass());
    assertEquals(cl.loadClass("site.standard.Document$Content$Unknown"),
        document.getMethod("content").invoke(doc).getClass());
    assertEquals(mapper.readTree(json), mapper.readTree(mapper.writeValueAsString(doc)));

    // A union member without $type falls back to Unknown on open unions.
    Class<?> basic = cl.loadClass("site.standard.theme.Basic");
    String theme = """
        {"$type":"site.standard.theme.basic",
         "background":{"$type":"site.standard.theme.color#rgb","r":1,"g":2,"b":3},
         "foreground":{"$type":"site.standard.theme.color#rgb","r":4,"g":5,"b":6},
         "accent":{"$type":"site.standard.theme.color#rgba","r":7,"g":8,"b":9,"a":10},
         "accentForeground":{"$type":"site.standard.theme.color#rgb","r":0,"g":0,"b":0}}
        """;
    Object t = mapper.readValue(theme, basic);
    assertEquals(cl.loadClass("site.standard.theme.Basic$Background$Rgb"),
        basic.getMethod("background").invoke(t).getClass());
    assertEquals(cl.loadClass("site.standard.theme.Basic$Accent$Unknown"),
        basic.getMethod("accent").invoke(t).getClass());
    JsonNode back = mapper.readTree(mapper.writeValueAsString(t));
    assertEquals(mapper.readTree(theme), back);

    // Plain object refs carry no $type.
    Class<?> contributor = cl.loadClass("site.standard.Document$Contributor");
    Object c = mapper.readValue("{\"did\":\"did:plc:abc\"}", contributor);
    assertEquals("{\"did\":\"did:plc:abc\"}", mapper.writeValueAsString(c));
  }

  @Test void crossPackRefUsesDependencyIndex() throws Exception {
    List<Path> coreSources = generateCore();
    // Only the Standard.site JSON is present: com.atproto refs resolve through the core pack's index alone.
    LexiconSet siteOnly = Fixtures.only("site.standard.");
    List<Path> siteSources = Generator.generate(siteOnly, Fixtures.nsids(siteOnly, "site.standard."),
        Fixtures.coreIndex(), site, Optional.empty());
    List<Path> sources = new ArrayList<>(coreSources);
    sources.addAll(siteSources);
    ClassLoader cl = InMemoryCompiler.compile(sources);
    Class<?> document = cl.loadClass("site.standard.Document");
    assertEquals(cl.loadClass("com.atproto.repo.StrongRef"), document.getMethod("bskyPostRef").getReturnType());
    assertEquals(cl.loadClass("com.atproto.label.Defs$SelfLabels"),
        cl.loadClass("site.standard.Document$Labels$SelfLabels").getMethod("value").getReturnType());
    // nothing under com/atproto was generated into the Standard.site output
    assertTrue(siteSources.stream().allMatch(p -> p.startsWith(site.resolve("site"))), siteSources.toString());
  }

  @Test void missingRefFailsWithName() {
    LexiconSet siteOnly = Fixtures.only("site.standard.");
    LexiconException e = assertThrows(LexiconException.class, () -> Generator.generate(siteOnly,
        Fixtures.nsids(siteOnly, "site.standard."), Map.of(), site, Optional.empty()));
    assertTrue(e.getMessage().contains("unresolved ref com.atproto.label.defs#selfLabels"), e.getMessage());
  }

  @Test void packagePrefixCompiles() throws Exception {
    LexiconSet set = Fixtures.only("com.atproto.");
    List<Path> sources = Generator.generate(set, Fixtures.nsids(set, "com.atproto."), Map.of(), core,
        Optional.of("org.example.gen"));
    ClassLoader cl = InMemoryCompiler.compile(sources);
    assertNotNull(cl.loadClass("org.example.gen.com.atproto.repo.RepoClient"));
  }
}
