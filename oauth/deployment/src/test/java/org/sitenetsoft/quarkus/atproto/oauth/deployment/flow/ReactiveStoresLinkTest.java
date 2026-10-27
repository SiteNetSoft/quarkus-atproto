package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.AtUri;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.response.Response;

/**
 * {@code link} mode with application-provided reactive stores and endpoints on the event loop: {@code AtprotoUser}
 * resolves the link without blocking.
 */
public class ReactiveStoresLinkTest {

    static final String HANDLE = "alice.example.test";
    static final String ANN = OAuthTestSupport.ANN;
    static final int[] PORTS = FakeAtproto.reservePorts(ReactiveStoresLinkTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.linkApp(FAKE, List.of(HANDLE), AsyncLinkApp.class,
            AsyncStores.class, AsyncStores.Links.class, AsyncStores.Sessions.class);

    @Inject
    AsyncStores.Links links;

    @Inject
    AsyncStores.Sessions sessions;

    @AfterEach
    void reset() {
        FAKE.clear();
        links.unlink(ANN, ALICE.did()).await().atMost(Duration.ofSeconds(5));
        sessions.findByDid(ALICE.did()).await().atMost(Duration.ofSeconds(5))
                .forEach(s -> sessions.delete(s.id()).await().atMost(Duration.ofSeconds(5)));
    }

    @Test
    void asyncAccessorsOnTheEventLoop() {
        LinkBrowser.link(ANN, HANDLE);
        // the link and session went to the application's stores
        assertEquals(List.of(ALICE.did()), links.findByPrincipal(ANN).await().atMost(Duration.ofSeconds(5)));
        assertEquals(1, sessions.findByDid(ALICE.did()).await().atMost(Duration.ofSeconds(5)).size());

        Response linked = LinkBrowser.as(ANN).get("/async/linked");
        assertEquals(200, linked.statusCode(), linked.body().asString());
        assertEquals(ALICE.did().value() + " loop=true", linked.body().asString());

        Response handle = LinkBrowser.as(ANN).get("/async/handle");
        assertEquals(200, handle.statusCode(), handle.body().asString());
        assertEquals(HANDLE + " " + OAuthTestSupport.SCOPE, handle.body().asString());

        Response published = LinkBrowser.as(ANN).queryParam("title", "async").post("/async/publish");
        assertEquals(200, published.statusCode(), published.body().asString());
        AtUri uri = AtUri.parse(published.body().asString());
        assertEquals(ALICE.did(), uri.authority());
        assertTrue(FAKE.pds().records().containsKey(uri));
    }

    @Test
    void syncAccessorOnTheEventLoopFailsFast() {
        LinkBrowser.link(ANN, HANDLE);
        Response r = LinkBrowser.as(ANN).get("/async/sync");
        assertEquals(409, r.statusCode(), r.body().asString());
        assertTrue(r.body().asString().contains("resolve()"), r.body().asString());
        assertTrue(r.body().asString().endsWith("loop=true"), r.body().asString());
    }

    @Test
    void asyncWithoutLink() {
        Response r = LinkBrowser.as(ANN).get("/async/linked");
        assertEquals(500, r.statusCode()); // IllegalStateException("no linked AT Protocol account") unhandled
        Response none = LinkBrowser.as(ANN).queryParam("title", "x").post("/async/publish");
        assertEquals(500, none.statusCode());
        assertTrue(FAKE.pds().records().isEmpty());
    }
}
