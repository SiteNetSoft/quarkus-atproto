package org.sitenetsoft.quarkus.atproto.lexicon.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.quarkus.atproto.lexicon.LexiconValidator;
import org.sitenetsoft.quarkus.atproto.lexicon.ValidationMode;
import org.sitenetsoft.quarkus.atproto.lexicon.ValidationProblem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.test.QuarkusUnitTest;

class ValidatorTest {

    static final Nsid DOCUMENT = Nsid.parse("site.standard.document");
    static final Nsid NOTE = Nsid.parse("org.example.note");
    static final String ZWJ = "👩‍💻"; // 👩‍💻: 3 code points, 11 UTF-8 bytes, 1 grapheme

    static final List<String> RECORDS = List.of("document-canonicalurl.json", "document-langs.json",
            "document-pckt-labels.json", "document-visibility.json", "document-wordpress-bskypostref.json",
            "publication-leaflet-theme-prefs.json", "publication-sharpie.json", "publication-typed-prefs-theme.json");

    /** A committed real record that breaks site.standard.document: its open-union content is an array. */
    static final String INVALID_IN_THE_WILD = "document-visibility.json";

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> {
                jar.addAsResource("lexicons/org/example/note.json", "META-INF/atproto/lexicons/org/example/note.json");
                RECORDS.forEach(r -> jar.addAsResource("records/" + r));
            });

    @Inject
    LexiconValidator validator;

    @Inject
    ObjectMapper mapper;

    JsonNode fixture(String name) throws Exception {
        try (InputStream in = ValidatorTest.class.getResourceAsStream("/records/" + name)) {
            assertNotNull(in, name);
            return mapper.readTree(in);
        }
    }

    ObjectNode document() {
        ObjectNode d = mapper.createObjectNode();
        d.put("$type", "site.standard.document");
        d.put("site", "https://example.com");
        d.put("title", "Hello");
        d.put("publishedAt", "2026-10-26T12:00:00.000Z");
        return d;
    }

    ObjectNode note() {
        ObjectNode n = mapper.createObjectNode();
        n.put("$type", "org.example.note");
        n.put("text", "hi");
        n.put("createdAt", "2026-10-26T12:00:00Z");
        return n;
    }

    static void assertProblemAt(List<ValidationProblem> problems, String path, String messagePart) {
        assertTrue(problems.stream().anyMatch(p -> p.path().equals(path) && p.message().contains(messagePart)),
                () -> "expected a problem at " + path + " mentioning \"" + messagePart + "\", got " + problems);
    }

    static void assertValid(List<ValidationProblem> problems) {
        assertEquals(List.of(), problems);
    }

    @Test
    void validDocumentPasses() throws Exception {
        for (String name : RECORDS) {
            if (name.equals(INVALID_IN_THE_WILD)) {
                continue;
            }
            JsonNode record = fixture(name);
            Nsid collection = Nsid.parse(record.get("$type").asText());
            assertEquals(List.of(), validator.validate(collection, record), name);
        }
        assertValid(validator.validate(DOCUMENT, document()));
    }

    @Test
    void realRecordWithArrayInOpenUnionFails() throws Exception {
        // published with "content": [ ... ] although content is a union (must be an object with $type)
        List<ValidationProblem> problems = validator.validate(DOCUMENT, fixture(INVALID_IN_THE_WILD));
        assertEquals(1, problems.size(), problems::toString);
        assertProblemAt(problems, "/content", "$type");
    }

    @Test
    void validatesGeneratedObjectsThroughTheManagedObjectMapper() throws Exception {
        JsonNode record = fixture("document-langs.json");
        Object typed = mapper.treeToValue(record, Class.forName("site.standard.Document"));
        assertValid(validator.validate(DOCUMENT, typed));
    }

    @Test
    void missingTitleFails() {
        ObjectNode d = document();
        d.remove("title");
        List<ValidationProblem> problems = validator.validate(DOCUMENT, d);
        assertEquals(1, problems.size(), problems::toString);
        assertProblemAt(problems, "/title", "required");
    }

    @Test
    void titleOverMaxGraphemesFails() {
        assertProblemAt(validator.validate(DOCUMENT, document().put("title", "a".repeat(501))), "/title", "graphemes");
    }

    @Test
    void titleAt500GraphemesPasses() {
        // 400 × 👩‍💻 + 100 × "a": 500 graphemes, 1300 UTF-16 chars, 4500 UTF-8 bytes (under maxLength 5000)
        assertValid(validator.validate(DOCUMENT, document().put("title", ZWJ.repeat(400) + "a".repeat(100))));
    }

    @Test
    void titleAt501GraphemesFails() {
        // 501 graphemes, 4511 bytes: only maxGraphemes is exceeded
        List<ValidationProblem> problems = validator.validate(DOCUMENT,
                document().put("title", ZWJ.repeat(401) + "a".repeat(100)));
        assertEquals(1, problems.size(), problems::toString);
        assertProblemAt(problems, "/title", "graphemes");
    }

    @Test
    void byteLengthCountsUtf8() {
        // description: maxLength 30000 bytes, maxGraphemes 3000. 2727 × 11 = 29997 bytes passes, 2728 × 11 = 30008 fails
        assertValid(validator.validate(DOCUMENT, document().put("description", ZWJ.repeat(2727))));
        List<ValidationProblem> problems = validator.validate(DOCUMENT,
                document().put("description", ZWJ.repeat(2728)));
        assertEquals(1, problems.size(), problems::toString);
        assertProblemAt(problems, "/description", "bytes");
        // 500 × 👩‍💻 is 500 graphemes but 5500 bytes: over the title's maxLength 5000
        List<ValidationProblem> title = validator.validate(DOCUMENT, document().put("title", ZWJ.repeat(500)));
        assertEquals(1, title.size(), title::toString);
        assertProblemAt(title, "/title", "bytes");
    }

    @Test
    void badPublishedAtFails() {
        assertProblemAt(validator.validate(DOCUMENT, document().put("publishedAt", "2026-10-26 12:00:00")),
                "/publishedAt", "datetime");
        assertProblemAt(validator.validate(DOCUMENT, document().put("publishedAt", 12)), "/publishedAt", "string");
    }

    @Test
    void openUnionUnknownTypePasses() {
        ObjectNode d = document();
        d.putObject("content").put("$type", "com.example.whatever").put("x", 1);
        assertValid(validator.validate(DOCUMENT, d));
        ObjectNode untyped = document();
        untyped.putObject("content").put("x", 1);
        assertProblemAt(validator.validate(DOCUMENT, untyped), "/content", "$type");
    }

    @Test
    void closedUnionUnknownTypeFails() {
        ObjectNode n = note();
        n.putObject("embed").put("$type", "org.example.other");
        assertProblemAt(validator.validate(NOTE, n), "/embed", "org.example.other");

        ObjectNode ok = note();
        ok.putObject("embed").put("$type", "org.example.note#link").put("uri", "https://example.com/a");
        assertValid(validator.validate(NOTE, ok));

        ObjectNode badMember = note();
        badMember.putObject("embed").put("$type", "org.example.note#link").put("uri", "not a uri");
        assertProblemAt(validator.validate(NOTE, badMember), "/embed/uri", "uri");

        ObjectNode crossFile = note();
        ObjectNode quote = crossFile.putObject("embed").put("$type", "org.example.note#quote");
        quote.putObject("ref").put("uri", "at://did:plc:abc/site.standard.document/3k").put("cid", "nope");
        assertProblemAt(validator.validate(NOTE, crossFile), "/embed/ref/cid", "cid");
    }

    @Test
    void blobAcceptAndMaxSize() {
        assertValid(validator.validate(DOCUMENT, withCover("image/png", 1_000_000)));
        assertProblemAt(validator.validate(DOCUMENT, withCover("text/plain", 10)), "/coverImage", "text/plain");
        assertProblemAt(validator.validate(DOCUMENT, withCover("image/png", 1_000_001)), "/coverImage", "1000000");
        ObjectNode notBlob = document();
        notBlob.putObject("coverImage").put("mimeType", "image/png");
        assertProblemAt(validator.validate(DOCUMENT, notBlob), "/coverImage", "blob");
        // accept */* takes anything
        ObjectNode n = note();
        n.set("avatar", blob("application/octet-stream", 5));
        assertValid(validator.validate(NOTE, n));
    }

    ObjectNode withCover(String mimeType, long size) {
        ObjectNode d = document();
        d.set("coverImage", blob(mimeType, size));
        return d;
    }

    ObjectNode blob(String mimeType, long size) {
        ObjectNode b = mapper.createObjectNode();
        b.put("$type", "blob");
        b.putObject("ref").put("$link", "bafkreigklztkr2tnny2hrbsxwdllmkkhenleaywqhus4bwcjgephi42vy4");
        b.put("mimeType", mimeType);
        b.put("size", size);
        return b;
    }

    @Test
    void unknownCollectionPassesInOptimisticMode() {
        assertEquals(ValidationMode.OPTIMISTIC, validator.mode());
        Nsid unknown = Nsid.parse("com.example.unknown");
        assertValid(validator.validate(unknown, mapper.createObjectNode().put("$type", "com.example.unknown")));
    }

    @Test
    void failsInTrueMode() {
        Nsid unknown = Nsid.parse("com.example.unknown");
        assertProblemAt(validator.validate(unknown, mapper.createObjectNode().put("$type", "com.example.unknown"),
                ValidationMode.TRUE), "/", "com.example.unknown");
        // a known, valid record still passes in true mode
        assertValid(validator.validate(DOCUMENT, document(), ValidationMode.TRUE));
    }

    @Test
    void falseModeSkipsValidation() {
        ObjectNode d = document();
        d.remove("title");
        assertValid(validator.validate(DOCUMENT, d, ValidationMode.FALSE));
    }

    @Test
    void recordTypeMustMatchCollection() {
        assertProblemAt(validator.validate(DOCUMENT, document().put("$type", "site.standard.publication")),
                "/$type", "site.standard.document");
        ObjectNode untyped = document();
        untyped.remove("$type");
        assertProblemAt(validator.validate(DOCUMENT, untyped), "/$type", "required");
        assertProblemAt(validator.validate(DOCUMENT, mapper.createArrayNode()), "/", "object");
    }

    @Test
    void scalarRules() {
        assertValid(validator.validate(NOTE, note().put("priority", 5).put("kind", "final").put("version", 1)));
        assertProblemAt(validator.validate(NOTE, note().put("priority", 6)), "/priority", "maximum");
        assertProblemAt(validator.validate(NOTE, note().put("priority", 0)), "/priority", "minimum");
        assertProblemAt(validator.validate(NOTE, note().put("priority", 2.5)), "/priority", "integer");
        assertProblemAt(validator.validate(NOTE, note().put("kind", "other")), "/kind", "one of");
        assertProblemAt(validator.validate(NOTE, note().put("version", 2)), "/version", "1");
        assertProblemAt(validator.validate(NOTE, note().put("pinned", "yes")), "/pinned", "boolean");
        // knownValues is open
        assertValid(validator.validate(NOTE, note().put("mood", "elated")));
    }

    @Test
    void nullable() {
        assertValid(validator.validate(NOTE, note().putNull("mood")));
        assertProblemAt(validator.validate(NOTE, note().putNull("kind")), "/kind", "null");
        assertProblemAt(validator.validate(NOTE, note().putNull("text")), "/text", "null");
    }

    @Test
    void formats() {
        assertValid(validator.validate(NOTE, note().put("author", "alice.example.com")));
        assertValid(validator.validate(NOTE, note().put("author", "did:plc:z72i7hdynmk6r22z27h6tvur")));
        assertProblemAt(validator.validate(NOTE, note().put("author", "not an identifier")), "/author",
                "at-identifier");
        assertValid(validator.validate(NOTE, note().put("homepage", "mailto:alice@example.com")));
        assertProblemAt(validator.validate(NOTE, note().put("homepage", "/relative/path")), "/homepage", "uri");
        ObjectNode langs = note();
        langs.putArray("langs").add("en").add("not a language");
        assertProblemAt(validator.validate(NOTE, langs), "/langs/1", "language");
    }

    @Test
    void arraysBytesLinksAndUnknown() {
        ObjectNode tooMany = note();
        tooMany.putArray("langs").add("en").add("fr").add("de");
        assertProblemAt(validator.validate(NOTE, tooMany), "/langs", "at most 2");

        ObjectNode bytes = note();
        bytes.putObject("data").put("$bytes", "AQID"); // 3 bytes
        assertValid(validator.validate(NOTE, bytes));
        ObjectNode longBytes = note();
        longBytes.putObject("data").put("$bytes", "AQIDBAU"); // 5 bytes
        assertProblemAt(validator.validate(NOTE, longBytes), "/data", "bytes");
        assertProblemAt(validator.validate(NOTE, note().put("data", "AQID")), "/data", "$bytes");

        ObjectNode link = note();
        link.putObject("link").put("$link", "bafkreigklztkr2tnny2hrbsxwdllmkkhenleaywqhus4bwcjgephi42vy4");
        assertValid(validator.validate(NOTE, link));
        ObjectNode badLink = note();
        badLink.putObject("link").put("$link", "nope");
        assertProblemAt(validator.validate(NOTE, badLink), "/link", "cid");

        ObjectNode unknown = note();
        unknown.putObject("extra").put("anything", true);
        assertValid(validator.validate(NOTE, unknown));
        assertProblemAt(validator.validate(NOTE, note().put("extra", 3)), "/extra", "object");
    }

    @Test
    void crossFileRecordRef() {
        ObjectNode n = note();
        ObjectNode about = document();
        about.remove("title");
        n.set("about", about);
        assertProblemAt(validator.validate(NOTE, n), "/about/title", "required");
        ObjectNode ok = note();
        ok.set("about", document());
        assertValid(validator.validate(NOTE, ok));
    }

    @Test
    void reportsEveryProblem() {
        ObjectNode d = document().put("publishedAt", "yesterday").put("title", "a".repeat(501));
        d.remove("site");
        List<ValidationProblem> problems = validator.validate(DOCUMENT, d);
        assertEquals(3, problems.size(), problems::toString);
        assertFalse(problems.stream().anyMatch(p -> p.path().isEmpty()));
    }
}
