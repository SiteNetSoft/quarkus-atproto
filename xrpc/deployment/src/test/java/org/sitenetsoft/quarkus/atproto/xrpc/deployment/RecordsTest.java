package org.sitenetsoft.quarkus.atproto.xrpc.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.AtUri;
import org.sitenetsoft.atproto.syntax.Cid;
import org.sitenetsoft.atproto.syntax.Datetime;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.atproto.syntax.RecordKey;
import org.sitenetsoft.quarkus.atproto.lexicon.BlobRef;
import org.sitenetsoft.quarkus.atproto.xrpc.AtprotoRecords;
import org.sitenetsoft.quarkus.atproto.xrpc.ReactiveAtprotoRecords;
import org.sitenetsoft.quarkus.atproto.xrpc.RecordResult;
import org.sitenetsoft.quarkus.atproto.xrpc.RecordValidationException;
import org.sitenetsoft.quarkus.atproto.xrpc.RecordsImpl;
import org.sitenetsoft.quarkus.atproto.xrpc.WriteGuard;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcAuth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.mutiny.Uni;
import site.standard.Document;

class RecordsTest {

    static final Nsid DOCUMENT = Nsid.parse(Document.NSID);
    /** A test Lexicon whose record key type is {@code literal:self}. */
    static final Nsid PROFILE = Nsid.parse("org.example.profile");
    static final Did REPO = Did.parse(PdsStub.REPO);
    static final XrpcAuth AUTH = r -> Uni.createFrom().item(r.withHeader("Authorization", "Bearer t"));

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClasses(PdsStub.class)
                    .addAsResource("lexicons/profile.json", "META-INF/atproto/lexicons/org/example/profile.json"))
            .overrideConfigKey("quarkus.safe-fetch.https-only", "false")
            .overrideConfigKey("quarkus.safe-fetch.allow-private-addresses", "true");

    @Inject
    RecordsImpl records;

    @Inject
    ObjectMapper mapper;

    static Document document(String title) {
        return new Document(null, URI.create("https://example.com"), null, null, title, null, null, null, null, null,
                null, Datetime.parse("2026-10-27T10:00:00.000Z"), null, null);
    }

    @Test
    void createValidatesBeforeSending() {
        URI pds = PdsStub.service("ok");
        // a plain map: the generated Document cannot be built without its required title
        Map<String, Object> missingTitle = Map.of("$type", Document.NSID, "site", "https://example.com",
                "publishedAt", "2026-10-27T10:00:00.000Z");
        RecordValidationException e = assertThrows(RecordValidationException.class,
                () -> records.blockingForRepo(REPO, pds, AUTH).create(DOCUMENT, missingTitle));
        assertEquals(DOCUMENT, e.collection());
        assertTrue(e.problems().stream().anyMatch(p -> p.path().equals("/title")), e.problems().toString());
        assertTrue(e.getMessage().contains("/title"), e.getMessage());
        assertTrue(PdsStub.requests(pds).isEmpty());
    }

    @Test
    void recordOfAnotherCollectionRefused() {
        URI pds = PdsStub.service("ok");
        RecordValidationException e = assertThrows(RecordValidationException.class, () -> records
                .blockingForRepo(REPO, pds, AUTH).create(Nsid.parse("org.example.unknown"), document("Hi")));
        assertEquals("/$type", e.problems().get(0).path());
        assertTrue(PdsStub.requests(pds).isEmpty());
    }

    @Test
    void createSendsTypedRecordWithType() throws Exception {
        URI pds = PdsStub.service("ok");
        RecordResult result = records.blockingForRepo(REPO, pds, AUTH).create(DOCUMENT, document("Hello"));
        assertEquals(AtUri.parse("at://" + PdsStub.REPO + "/site.standard.document/3kxyzservertid"), result.uri());
        assertEquals(Cid.parse(PdsStub.CID), result.cid());
        assertEquals(Optional.of("unknown"), result.validationStatus());

        JsonNode request = PdsStub.requests(pds).get(0);
        assertTrue(request.get("path").asText().endsWith("/xrpc/com.atproto.repo.createRecord"));
        assertEquals("Bearer t", request.get("headers").get("authorization").asText());
        JsonNode body = mapper.readTree(request.get("body").asText());
        assertEquals(PdsStub.REPO, body.get("repo").asText());
        assertEquals("site.standard.document", body.get("collection").asText());
        assertFalse(body.has("rkey"), "server-generated TID: no rkey sent");
        assertEquals("site.standard.document", body.get("record").get("$type").asText());
        assertEquals("Hello", body.get("record").get("title").asText());
    }

    @Test
    void untypedRecordGetsCollectionAsType() throws Exception {
        URI pds = PdsStub.service("ok");
        records.blockingForRepo(REPO, pds, AUTH).create(DOCUMENT, RecordKey.parse("3kclienttidaa"),
                Map.of("site", "https://example.com", "title", "T", "publishedAt", "2026-10-27T10:00:00.000Z"));
        JsonNode body = mapper.readTree(PdsStub.requests(pds).get(0).get("body").asText());
        assertEquals("3kclienttidaa", body.get("rkey").asText());
        assertEquals("site.standard.document", body.get("record").get("$type").asText());
    }

    @Test
    void putSendsRkeyAndSwap() throws Exception {
        URI pds = PdsStub.service("ok");
        RecordResult result = records.forRepo(REPO, pds, AUTH)
                .put(DOCUMENT, RecordKey.parse("3kputrecordaa"), document("Put"), Optional.of(Cid.parse(PdsStub.CID)))
                .await().indefinitely();
        assertEquals("3kputrecordaa", result.uri().rkey().map(RecordKey::value).orElseThrow());
        JsonNode request = PdsStub.requests(pds).get(0);
        assertTrue(request.get("path").asText().endsWith("/xrpc/com.atproto.repo.putRecord"));
        JsonNode body = mapper.readTree(request.get("body").asText());
        assertEquals("3kputrecordaa", body.get("rkey").asText());
        assertEquals(PdsStub.CID, body.get("swapRecord").asText());
        assertEquals("Put", body.get("record").get("title").asText());
    }

    @Test
    void slugKeyRefusedForTidCollection() {
        // site.standard.document declares key "tid": a slug key would be a non-conformant record forever
        URI pds = PdsStub.service("ok");
        AtprotoRecords blocking = records.blockingForRepo(REPO, pds, AUTH);
        RecordKey slug = RecordKey.parse("my-first-post");
        RecordValidationException put = assertThrows(RecordValidationException.class,
                () -> blocking.put(DOCUMENT, slug, document("Slug"), Optional.empty()));
        assertEquals(List.of("rkey"), put.problems().stream().map(p -> p.path()).toList());
        assertTrue(put.getMessage().contains("my-first-post") && put.getMessage().contains("tid"), put.getMessage());
        assertThrows(RecordValidationException.class, () -> blocking.create(DOCUMENT, slug, document("Slug")));
        assertTrue(PdsStub.requests(pds).isEmpty(), "nothing sent to the PDS");
    }

    @Test
    void tidKeyAccepted() throws Exception {
        URI pds = PdsStub.service("ok");
        RecordKey tid = RecordKey.parse(org.sitenetsoft.atproto.syntax.Tid.next().value());
        records.blockingForRepo(REPO, pds, AUTH).put(DOCUMENT, tid, document("Tid"), Optional.empty());
        records.blockingForRepo(REPO, pds, AUTH).create(DOCUMENT, tid, document("Tid"));
        assertEquals(2, PdsStub.requests(pds).size());
        assertEquals(tid.value(), mapper.readTree(PdsStub.requests(pds).get(0).get("body").asText()).get("rkey")
                .asText());
    }

    @Test
    void literalKeyChecked() {
        URI pds = PdsStub.service("ok");
        AtprotoRecords blocking = records.blockingForRepo(REPO, pds, AUTH);
        Map<String, Object> profile = Map.of("name", "Alice");
        blocking.put(PROFILE, RecordKey.parse("self"), profile, Optional.empty());
        assertEquals(1, PdsStub.requests(pds).size());
        RecordValidationException e = assertThrows(RecordValidationException.class,
                () -> blocking.put(PROFILE, RecordKey.parse("other"), profile, Optional.empty()));
        assertEquals("rkey", e.problems().get(0).path());
        assertThrows(RecordValidationException.class, () -> blocking.create(PROFILE,
                RecordKey.parse(org.sitenetsoft.atproto.syntax.Tid.next().value()), profile));
        assertEquals(1, PdsStub.requests(pds).size());
    }

    @Test
    void getMapsTypedRecordAndMissingIsEmpty() {
        URI pds = PdsStub.service("ok");
        AtprotoRecords blocking = records.blockingForRepo(REPO, pds, AUTH);
        Document doc = blocking.get(DOCUMENT, RecordKey.parse("3kabc"), Document.class).orElseThrow();
        assertEquals("Hello", doc.title());
        assertEquals(42, doc.unknownFields().get("futureField").asInt());
        assertEquals(Optional.empty(), blocking.get(DOCUMENT, RecordKey.parse("missing"), Document.class));
    }

    @Test
    void deleteSendsRkey() throws Exception {
        URI pds = PdsStub.service("ok");
        records.blockingForRepo(REPO, pds, AUTH).delete(DOCUMENT, RecordKey.parse("3kabc"));
        JsonNode request = PdsStub.requests(pds).get(0);
        assertTrue(request.get("path").asText().endsWith("/xrpc/com.atproto.repo.deleteRecord"));
        assertEquals("3kabc", mapper.readTree(request.get("body").asText()).get("rkey").asText());
    }

    @Test
    void uploadBlobReturnsBlobRef() {
        URI pds = PdsStub.service("ok");
        byte[] png = "not really a png".getBytes(StandardCharsets.UTF_8);
        BlobRef blob = records.blockingForRepo(REPO, pds, AUTH).uploadBlob(png, "image/png");
        assertEquals(BlobRef.TYPE, blob.type());
        assertEquals("image/png", blob.mimeType());
        assertEquals(png.length, blob.size());
        assertEquals(PdsStub.BLOB_CID, blob.ref().link());
        JsonNode request = PdsStub.requests(pds).get(0);
        assertTrue(request.get("path").asText().endsWith("/xrpc/com.atproto.repo.uploadBlob"));
        assertEquals("image/png", request.get("headers").get("content-type").asText());
        assertEquals("not really a png", request.get("body").asText());
    }

    @Test
    void writeGuardConsulted() {
        URI pds = PdsStub.service("ok");
        IllegalStateException denied = new IllegalStateException("scope does not allow this");
        List<String> calls = new ArrayList<>();
        WriteGuard guard = (collection, action, blobMime) -> {
            calls.add(collection + " " + action + " " + blobMime.orElse("-"));
            throw denied;
        };
        ReactiveAtprotoRecords guarded = records.forRepo(REPO, pds, AUTH, guard);
        assertSame(denied, assertThrows(IllegalStateException.class,
                () -> guarded.create(DOCUMENT, document("No")).await().indefinitely()));
        assertSame(denied, assertThrows(IllegalStateException.class, () -> guarded
                .put(DOCUMENT, RecordKey.parse("self"), document("No"), Optional.empty()).await().indefinitely()));
        assertSame(denied, assertThrows(IllegalStateException.class,
                () -> guarded.delete(DOCUMENT, RecordKey.parse("self")).await().indefinitely()));
        assertSame(denied, assertThrows(IllegalStateException.class,
                () -> guarded.uploadBlob(new byte[] {1}, "image/png").await().indefinitely()));
        assertEquals(List.of("site.standard.document create -", "site.standard.document update -",
                "site.standard.document delete -", "com.atproto.repo.uploadBlob create image/png"), calls);
        assertTrue(PdsStub.requests(pds).isEmpty());
    }

    @Test
    void defaultWriteGuardAllowsEverything() {
        URI pds = PdsStub.service("ok");
        records.blockingForRepo(REPO, pds, AUTH).delete(DOCUMENT, RecordKey.parse("x"));
        assertEquals(1, PdsStub.requests(pds).size());
    }
}
