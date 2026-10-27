package org.sitenetsoft.quarkus.atproto.identity.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;

/**
 * One loopback server playing a PLC directory ({@code /did:plc:...}) and the handle well-known endpoints
 * ({@code /wk/<handle>/.well-known/atproto-did}, reached through {@code quarkus.atproto.identity.https-well-known-override}).
 * QuarkusUnitTest loads test classes in two class loaders, so the server is started once per JVM (port kept in a system
 * property) and request counts are read back over HTTP ({@code /__count?path=...}).
 */
public final class FakeIdentityHost {

    static final String PDS = "https://pds.example.test";
    static final String KEY = "zQ3shXjHeiBuRCKmM36cuYnm7YEMzhGnCmCyW92sRJ9pribSF";

    static final String ALICE = plc('a');
    static final String BOB = plc('b');
    static final String CAROL = plc('c');
    static final String CAROL_HTTPS = plc('d');
    static final String CONFLICT_1 = plc('e');
    static final String CONFLICT_2 = plc('f');
    static final String ERIN = plc('g');
    static final String FRANK = plc('h');
    static final String PDS_MULTI = plc('i');
    static final String PDS_PATH = plc('j');
    static final String PDS_NONE = plc('k');
    static final String PDS_WRONG_TYPE = plc('l');
    static final String CACHED = plc('m');
    static final String FLAKY = plc('n');

    /** Handles whose well-known goes to this host (all test handles, so no test reaches the network). */
    static final List<String> HANDLES = List.of("alice.example.test", "bob.example.test", "carol.example.test",
            "conflict.example.test", "erin.example.test", "cached.example.test", "nobody.example.test",
            "flaky.example.test", "broken.example.test", "shaky.example.test");

    /** Handle well-known answers; handles not listed answer 404. */
    private static final Map<String, String> WELL_KNOWN = Map.of(
            "bob.example.test", BOB + "\n",
            "carol.example.test", CAROL_HTTPS,
            "conflict.example.test", CONFLICT_1);

    private static final String PORT_PROPERTY = "atproto.identity.test.fake.port";

    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final Map<String, String> documents = Map.ofEntries(
            Map.entry(ALICE, document(ALICE, "alice.example.test", pds(PDS))),
            Map.entry(BOB, document(BOB, "bob.example.test", pds(PDS))),
            Map.entry(CAROL, document(CAROL, "carol.example.test", pds(PDS))),
            // claims bob, who resolves to BOB
            Map.entry(ERIN, document(ERIN, "bob.example.test", pds(PDS))),
            // claims alice, who resolves to ALICE
            Map.entry(FRANK, document(FRANK, "alice.example.test", pds(PDS))),
            Map.entry(PDS_MULTI, document(PDS_MULTI, null, """
                    {"id":"#atproto_labeler","type":"AtprotoLabeler","serviceEndpoint":"https://labeler.example.test"},
                    {"id":"#other_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"https://wrong.example.test"},
                    {"id":"%s#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"https://pds.example.test:8443"}
                    """.formatted(PDS_MULTI))),
            Map.entry(PDS_PATH, document(PDS_PATH, null, pds("https://pds.example.test/xrpc"))),
            Map.entry(PDS_NONE, document(PDS_NONE, null, "")),
            Map.entry(PDS_WRONG_TYPE, document(PDS_WRONG_TYPE, null,
                    "{\"id\":\"#atproto_pds\",\"type\":\"SomethingElse\",\"serviceEndpoint\":\"https://pds.example.test\"}")),
            Map.entry(CACHED, document(CACHED, "cached.example.test", pds(PDS))),
            Map.entry(FLAKY, document(FLAKY, "flaky.example.test", pds(PDS))));
    private final int port;

    private FakeIdentityHost() {
        Vertx vertx = Vertx.vertx();
        var server = vertx.createHttpServer().requestHandler(this::handle);
        server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().join();
        this.port = server.actualPort();
    }

    private static String plc(char c) {
        return "did:plc:" + String.valueOf(c).repeat(24);
    }

    /** The fake's port, starting it on first use in this JVM. */
    public static int port() {
        synchronized (System.getProperties()) {
            String existing = System.getProperty(PORT_PROPERTY);
            if (existing != null) {
                return Integer.parseInt(existing);
            }
            int port = new FakeIdentityHost().port;
            System.setProperty(PORT_PROPERTY, String.valueOf(port));
            return port;
        }
    }

    public static String base() {
        return "http://127.0.0.1:" + port();
    }

    /** Base URL standing in for {@code https://<handle>}. */
    public static String wellKnownBase(String handle) {
        return base() + "/wk/" + handle;
    }

    /** How many requests the fake has seen for {@code path}. */
    public static int hits(String path) {
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create(base() + "/__count?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8))).build(),
                    HttpResponse.BodyHandlers.ofString());
            return Integer.parseInt(r.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static String wellKnownPath(String handle) {
        return "/wk/" + handle + "/.well-known/atproto-did";
    }

    private void handle(HttpServerRequest req) {
        String path = req.path();
        if (path.equals("/__count")) {
            req.response().end(String.valueOf(hits.getOrDefault(req.getParam("path"), new AtomicInteger()).get()));
            return;
        }
        hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
        if (path.startsWith("/wk/") && path.endsWith("/.well-known/atproto-did")) {
            String handle = path.substring(4, path.length() - "/.well-known/atproto-did".length());
            String did = WELL_KNOWN.get(handle);
            if (handle.equals("broken.example.test")) {
                req.response().setStatusCode(500).end("upstream down");
            } else if (did == null) {
                req.response().setStatusCode(404).end("not found");
            } else {
                req.response().putHeader("Content-Type", "text/plain").end(did);
            }
            return;
        }
        String doc = path.length() > 1 ? documents.get(path.substring(1)) : null;
        if (doc == null) {
            req.response().setStatusCode(404).end("{\"message\":\"DID not registered\"}");
        } else {
            req.response().putHeader("Content-Type", "application/did+ld+json").end(doc);
        }
    }

    private static String pds(String endpoint) {
        return "{\"id\":\"#atproto_pds\",\"type\":\"AtprotoPersonalDataServer\",\"serviceEndpoint\":\"" + endpoint + "\"}";
    }

    private static String document(String did, String handle, String services) {
        String aka = handle == null ? "" : "\"alsoKnownAs\":[\"https://not-a-handle.example\",\"at://" + handle + "\"],";
        return """
                {"@context":["https://www.w3.org/ns/did/v1","https://w3id.org/security/multikey/v1"],
                 "id":"%1$s",
                 %2$s
                 "verificationMethod":[{"id":"%1$s#atproto","type":"Multikey","controller":"%1$s",
                   "publicKeyMultibase":"%3$s"}],
                 "service":[%4$s]}
                """.formatted(did, aka, KEY, services);
    }
}
