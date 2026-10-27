package org.sitenetsoft.quarkus.atproto.xrpc.deployment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.atproto.syntax.RecordKey;
import org.sitenetsoft.quarkus.atproto.xrpc.api.ReactiveXrpcClient;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcAuth;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcClient;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcException;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcRequest;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcResponse;
import org.sitenetsoft.quarkus.safefetch.SafeFetchException;

import com.atproto.repo.GetRecord;
import com.atproto.repo.RepoClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.mutiny.Uni;

class XrpcClientTest {

    static final Nsid GET_RECORD = Nsid.parse("com.atproto.repo.getRecord");
    static final Nsid ECHO = Nsid.parse("org.example.echo");

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClasses(PdsStub.class))
            .overrideConfigKey("quarkus.safe-fetch.https-only", "false")
            .overrideConfigKey("quarkus.safe-fetch.allow-private-addresses", "true");

    @Inject
    XrpcClient xrpc;

    @Inject
    ReactiveXrpcClient reactive;

    @Inject
    ObjectMapper mapper;

    @Test
    void queryBuildsPathAndParams() {
        URI pds = PdsStub.service("ok");
        GetRecord.Output out = new RepoClient(reactive).getRecord(pds, new GetRecord.Params(Did.parse(PdsStub.REPO),
                Nsid.parse("site.standard.document"), RecordKey.parse("3kabc"), null), Optional.empty())
                .await().indefinitely();
        assertEquals("Hello", out.value().get("title").asText());
        JsonNode request = PdsStub.requests(pds).get(0);
        assertEquals("GET", request.get("method").asText());
        assertTrue(request.get("path").asText().endsWith("/xrpc/com.atproto.repo.getRecord"), request.toString());
        assertEquals("repo=did%3Aplc%3A" + "a".repeat(24) + "&collection=site.standard.document&rkey=3kabc",
                request.get("query").asText());
        assertEquals("application/json", request.get("headers").get("accept").asText());
    }

    @Test
    void queryStringifiesAndRepeatsParams() {
        URI pds = PdsStub.service("ok");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("limit", 50);
        params.put("reverse", true);
        params.put("skipped", null);
        params.put("collection", List.of(Nsid.parse("a.b.c"), Nsid.parse("d.e.f")));
        params.put("q", "a b&c");
        JsonNode out = xrpc.query(pds, ECHO, params, JsonNode.class, Optional.empty());
        assertTrue(out.get("echo").asBoolean());
        assertEquals("limit=50&reverse=true&collection=a.b.c&collection=d.e.f&q=a%20b%26c",
                PdsStub.requests(pds).get(0).get("query").asText());
    }

    @Test
    void procedurePostsJson() throws Exception {
        URI pds = PdsStub.service("ok");
        JsonNode out = xrpc.procedure(pds, ECHO, Map.of("text", "héllo"), JsonNode.class, Optional.empty());
        assertTrue(out.get("echo").asBoolean());
        JsonNode request = PdsStub.requests(pds).get(0);
        assertEquals("POST", request.get("method").asText());
        assertTrue(request.get("path").asText().endsWith("/xrpc/org.example.echo"));
        assertTrue(request.get("headers").get("content-type").asText().startsWith("application/json"));
        assertEquals(mapper.readTree("{\"text\":\"héllo\"}"), mapper.readTree(request.get("body").asText()));
    }

    @Test
    void procedureBytesSendsRawBodyAndBytesOutput() {
        URI pds = PdsStub.service("ok");
        byte[] out = xrpc.procedureBytes(pds, ECHO, "raw".getBytes(StandardCharsets.UTF_8), "text/plain",
                byte[].class, Optional.empty());
        assertArrayEquals("{\"echo\":true}".getBytes(StandardCharsets.UTF_8), out);
        JsonNode request = PdsStub.requests(pds).get(0);
        assertEquals("text/plain", request.get("headers").get("content-type").asText());
        assertEquals("raw", request.get("body").asText());
    }

    @Test
    void errorBodyBecomesXrpcException() {
        URI pds = PdsStub.service("error");
        XrpcException e = assertThrows(XrpcException.class,
                () -> xrpc.procedure(pds, ECHO, Map.of(), JsonNode.class, Optional.empty()));
        assertEquals(400, e.status());
        assertEquals("InvalidRequest", e.error());
        assertEquals("x", e.errorMessage());
        assertTrue(e.rateLimit().isEmpty());
        // the reactive client fails its Uni instead of throwing
        XrpcException r = assertThrows(XrpcException.class,
                () -> reactive.query(pds, ECHO, Map.of(), JsonNode.class, Optional.empty()).await().indefinitely());
        assertEquals("InvalidRequest", r.error());
    }

    @Test
    void rateLimitHeadersExposed() {
        URI pds = PdsStub.service("ratelimit");
        XrpcException e = assertThrows(XrpcException.class,
                () -> xrpc.query(pds, ECHO, Map.of(), JsonNode.class, Optional.empty()));
        assertEquals(429, e.status());
        assertEquals("RateLimitExceeded", e.error());
        assertEquals(Map.of("ratelimit-limit", "3000", "ratelimit-remaining", "0", "ratelimit-reset", "1700000000",
                "ratelimit-policy", "3000;w=300"), e.rateLimit());
    }

    @Test
    void authRetryHappensOnce() {
        // stale token first; shouldRetry (true on 401) swaps in the fresh one: two requests, success
        URI pds = PdsStub.service("auth");
        RefreshingAuth auth = new RefreshingAuth();
        JsonNode out = xrpc.query(pds, ECHO, Map.of(), JsonNode.class, Optional.of(auth));
        assertTrue(out.get("ok").asBoolean());
        List<JsonNode> requests = PdsStub.requests(pds);
        assertEquals(2, requests.size());
        assertEquals("Bearer stale", requests.get(0).get("headers").get("authorization").asText());
        assertEquals("Bearer fresh", requests.get(1).get("headers").get("authorization").asText());
        assertEquals(List.of(401), auth.seen);

        // a server that keeps answering 401: retried exactly once, then the second 401 surfaces
        URI denied = PdsStub.service("unauthorized");
        RefreshingAuth always = new RefreshingAuth();
        XrpcException e = assertThrows(XrpcException.class,
                () -> xrpc.procedure(denied, ECHO, Map.of(), JsonNode.class, Optional.of(always)));
        assertEquals(401, e.status());
        assertEquals("InvalidToken", e.error());
        assertEquals(2, PdsStub.requests(denied).size());
        assertEquals(List.of(401), always.seen); // shouldRetry asked once per call
    }

    @Test
    void successIsNeverResent() {
        URI pds = PdsStub.service("ok");
        AtomicInteger asked = new AtomicInteger();
        XrpcAuth auth = new XrpcAuth() {
            @Override
            public Uni<XrpcRequest> authorize(XrpcRequest request) {
                return Uni.createFrom().item(request.withHeader("Authorization", "Bearer t"));
            }

            @Override
            public boolean shouldRetry(XrpcResponse response) {
                asked.incrementAndGet(); // sees the response (to store a DPoP nonce), but a write is not repeated
                return true;
            }
        };
        xrpc.procedure(pds, ECHO, Map.of(), JsonNode.class, Optional.of(auth));
        assertEquals(1, asked.get());
        assertEquals(1, PdsStub.requests(pds).size());
    }

    @Test
    void crossHostRedirectRefused() {
        URI pds = PdsStub.service("redirect");
        SafeFetchException e = assertThrows(SafeFetchException.class,
                () -> xrpc.query(pds, ECHO, Map.of(), JsonNode.class, Optional.empty()));
        assertTrue(e.getMessage().contains("localhost"), e.getMessage());
    }

    @Test
    void emptyOutputClassIsVoid() {
        URI pds = PdsStub.service("ok");
        assertNull(xrpc.procedure(pds, ECHO, null, Void.class, Optional.empty()));
        assertFalse(PdsStub.requests(pds).get(0).get("headers").has("content-type"));
    }

    /** {@code Bearer stale} until {@link #shouldRetry} sees a 401, {@code Bearer fresh} after. */
    static final class RefreshingAuth implements XrpcAuth {
        final List<Integer> seen = new ArrayList<>();
        volatile boolean fresh;

        @Override
        public Uni<XrpcRequest> authorize(XrpcRequest request) {
            return Uni.createFrom().item(request.withHeader("Authorization", fresh ? "Bearer fresh" : "Bearer stale"));
        }

        @Override
        public boolean shouldRetry(XrpcResponse response) {
            seen.add(response.status());
            if (response.status() == 401) {
                fresh = true;
                return true;
            }
            return false;
        }
    }
}
