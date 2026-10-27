package org.sitenetsoft.quarkus.atproto.xrpc.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;

/**
 * A tiny loopback PDS for the XRPC tests (the full {@code FakePds} lives in {@code quarkus-atproto-test}). Every
 * service URL is {@code http://127.0.0.1:<port>/<id>/<scenario>}: {@code id} keeps each test's request log apart,
 * {@code scenario} picks the behaviour ({@code ok}, {@code error}, {@code ratelimit}, {@code unauthorized},
 * {@code auth}, {@code redirect}). QuarkusUnitTest loads test classes in two class loaders, so the server starts once
 * per JVM (port in a system property) and the log is read back over HTTP ({@code /__log?id=...}).
 */
public final class PdsStub {

    static final String CID = "bafyreie5737gdxlw5i64vzichcalba3z2v5n6icifvx5xytvske7mr3hpm";
    static final String BLOB_CID = "bafkreibme22gw2h7y2h7tg2fhqotaqjucnbc24deqo72b6mkl2egezxhvy";
    static final String REPO = "did:plc:" + "a".repeat(24);

    private static final String PORT_PROPERTY = "atproto.xrpc.test.stub.port";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, List<ObjectNode>> log = new ConcurrentHashMap<>();
    private final int port;

    private PdsStub() {
        Vertx vertx = Vertx.vertx();
        var server = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> handle(req, b)));
        server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().join();
        this.port = server.actualPort();
    }

    /** The stub's port, starting it on first use in this JVM. */
    public static int port() {
        synchronized (System.getProperties()) {
            String existing = System.getProperty(PORT_PROPERTY);
            if (existing != null) {
                return Integer.parseInt(existing);
            }
            int port = new PdsStub().port;
            System.setProperty(PORT_PROPERTY, String.valueOf(port));
            return port;
        }
    }

    /** A fresh service URL (own request log) playing {@code scenario}. */
    public static URI service(String scenario) {
        return URI.create("http://127.0.0.1:" + port() + "/" + UUID.randomUUID() + "/" + scenario);
    }

    /** The requests {@code service} received, oldest first: {@code method}, {@code path}, {@code query}, {@code headers} (lower-case names), {@code body}. */
    public static List<JsonNode> requests(URI service) {
        String id = service.getPath().split("/")[1];
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + "/__log?id=" + id)).build(),
                    HttpResponse.BodyHandlers.ofString());
            List<JsonNode> out = new ArrayList<>();
            JSON.readTree(r.body()).forEach(out::add);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void handle(HttpServerRequest req, Buffer body) {
        if (req.path().equals("/__log")) {
            ArrayNode all = JSON.createArrayNode();
            log.getOrDefault(req.getParam("id"), List.of()).forEach(all::add);
            req.response().putHeader("Content-Type", "application/json").end(all.toString());
            return;
        }
        String[] parts = req.path().split("/", 5); // "", id, scenario, "xrpc", nsid
        if (parts.length < 5 || !parts[3].equals("xrpc")) {
            req.response().setStatusCode(404).end();
            return;
        }
        ObjectNode entry = JSON.createObjectNode()
                .put("method", req.method().name())
                .put("path", req.path())
                .put("query", req.query())
                .put("body", body.toString(StandardCharsets.UTF_8));
        ObjectNode headers = entry.putObject("headers");
        req.headers().forEach(h -> headers.put(h.getKey().toLowerCase(), h.getValue()));
        log.computeIfAbsent(parts[1], k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(entry);
        String nsid = parts[4];
        switch (parts[2]) {
            case "error" -> json(req, 400, "{\"error\":\"InvalidRequest\",\"message\":\"x\"}");
            case "ratelimit" -> {
                req.response().putHeader("RateLimit-Limit", "3000").putHeader("ratelimit-remaining", "0")
                        .putHeader("ratelimit-reset", "1700000000").putHeader("ratelimit-policy", "3000;w=300");
                json(req, 429, "{\"error\":\"RateLimitExceeded\",\"message\":\"Rate Limit Exceeded\"}");
            }
            case "unavailable" -> json(req, 503, "{\"error\":\"Unavailable\",\"message\":\"down\"}");
            case "unauthorized" -> json(req, 401, "{\"error\":\"InvalidToken\",\"message\":\"Bad token\"}");
            case "auth" -> {
                if ("Bearer fresh".equals(req.getHeader("Authorization"))) {
                    json(req, 200, "{\"ok\":true}");
                } else {
                    json(req, 401, "{\"error\":\"ExpiredToken\",\"message\":\"Token has expired\"}");
                }
            }
            case "redirect" -> req.response().setStatusCode(302)
                    .putHeader("Location", "http://localhost:" + port + "/" + parts[1] + "/ok/xrpc/" + nsid)
                    .end();
            default -> ok(req, nsid, body);
        }
    }

    private void ok(HttpServerRequest req, String nsid, Buffer body) {
        switch (nsid) {
            case "com.atproto.repo.getRecord" -> {
                if ("missing".equals(req.getParam("rkey"))) {
                    json(req, 400, "{\"error\":\"RecordNotFound\",\"message\":\"Could not locate record\"}");
                    return;
                }
                String collection = req.getParam("collection");
                json(req, 200, """
                        {"uri":"at://%s/%s/%s","cid":"%s","value":{"$type":"%s","site":"https://example.com",
                         "title":"Hello","publishedAt":"2026-10-27T10:00:00.000Z","futureField":42}}"""
                        .formatted(req.getParam("repo"), collection, req.getParam("rkey"), CID, collection));
            }
            case "com.atproto.repo.createRecord", "com.atproto.repo.putRecord" -> {
                JsonNode in = read(body);
                String rkey = in.hasNonNull("rkey") ? in.get("rkey").asText() : "3kxyzservertid";
                json(req, 200, """
                        {"uri":"at://%s/%s/%s","cid":"%s","validationStatus":"unknown"}"""
                        .formatted(in.path("repo").asText(), in.path("collection").asText(), rkey, CID));
            }
            case "com.atproto.repo.deleteRecord" -> json(req, 200, "{}");
            case "com.atproto.repo.uploadBlob" -> json(req, 200, """
                    {"blob":{"$type":"blob","ref":{"$link":"%s"},"mimeType":"%s","size":%d}}"""
                    .formatted(BLOB_CID, req.getHeader("Content-Type"), body.length()));
            default -> json(req, 200, "{\"echo\":true}");
        }
    }

    private static JsonNode read(Buffer body) {
        try {
            return JSON.readTree(body.getBytes());
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    private static void json(HttpServerRequest req, int status, String body) {
        req.response().setStatusCode(status).putHeader("Content-Type", "application/json").end(body);
    }
}
