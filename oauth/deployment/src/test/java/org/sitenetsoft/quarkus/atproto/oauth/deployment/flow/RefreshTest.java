package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.oauth.session.Refresher;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;
import org.sitenetsoft.quarkus.atproto.test.Recorded;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.response.Response;

/** Refresh: single-use refresh tokens, one refresh per session at a time, compare-and-set across nodes. */
public class RefreshTest {

    static final String HANDLE = "alice.example.test";
    static final String CREATE = "/xrpc/com.atproto.repo.createRecord";
    static final int[] PORTS = FakeAtproto.reservePorts(RefreshTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.app(FAKE, List.of(HANDLE), HookedSessionStore.class);

    @Inject
    AtprotoSessionStore sessions;

    @Inject
    Refresher refresher;

    @AfterEach
    void reset() {
        FAKE.clear();
        HookedSessionStore.otherNodeRefreshes = null;
        sessions.findByDid(ALICE.did()).forEach(s -> sessions.delete(s.id()));
    }

    @Test
    void expiredTokenRefreshedThenRetried() {
        Browser.Login login = Browser.login(HANDLE);
        assertEquals(200, Browser.publish(login.cookie(), "one").statusCode());
        String before = sessions.findByDid(ALICE.did()).get(0).accessToken();
        int seen = creates().size();
        FAKE.pds().expireTokens();
        Response r = Browser.publish(login.cookie(), "two");
        assertEquals(200, r.statusCode(), r.body().asString());
        assertEquals(1, FAKE.as().refreshCount());
        // the PDS refused the expired token once (401 invalid_token), the retry with the refreshed one succeeded
        assertEquals(List.of(401, 200), creates().subList(seen, creates().size()).stream().map(Recorded::status)
                .toList());
        AtprotoSession after = sessions.findByDid(ALICE.did()).get(0);
        assertTrue(!after.accessToken().equals(before));
        assertEquals(2, FAKE.pds().records().size());
    }

    @Test
    void concurrentRequestsRefreshOnce() throws Exception {
        FAKE.as().accessTokenTtl(Duration.ZERO); // the login's access token is born expired
        Browser.Login login = Browser.login(HANDLE);
        FAKE.as().accessTokenTtl(Duration.ofMinutes(15));
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Response>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String title = "parallel " + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return Browser.publish(login.cookie(), title);
                }));
            }
            start.countDown();
            for (Future<Response> f : results) {
                Response r = f.get();
                assertEquals(200, r.statusCode(), r.body().asString());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, FAKE.as().refreshCount());
        assertEquals(n, FAKE.pds().records().size());
    }

    @Test
    void replaceIfRefreshTokenLoserRereads() {
        Browser.login(HANDLE);
        AtprotoSession mine = sessions.findByDid(ALICE.did()).get(0);
        // another node refreshes the same session between our token request and our compare-and-set
        HookedSessionStore.otherNodeRefreshes = s -> new AtprotoSession(s.id(), s.did(), s.handle(), s.pds(),
                s.issuer(), s.grantedScope(), "other-node-access", "other-node-refresh",
                Instant.now().plusSeconds(900), s.dpopPrivateJwk(), s.nonces(), s.created());
        AtprotoSession result = refresher.refresh(mine.id(), mine.accessToken()).await().atMost(Duration.ofSeconds(20));
        // our refresh reached the authorization server but lost the compare-and-set: we take the stored session
        assertEquals(1, FAKE.as().refreshCount());
        assertEquals("other-node-access", result.accessToken());
        AtprotoSession stored = sessions.find(mine.id()).orElseThrow();
        assertEquals("other-node-access", stored.accessToken());
        assertEquals("other-node-refresh", stored.refreshToken());

        // a caller still holding the old access token does not spend the (new) refresh token again
        AtprotoSession again = refresher.refresh(mine.id(), mine.accessToken()).await().atMost(Duration.ofSeconds(20));
        assertEquals("other-node-access", again.accessToken());
        assertEquals(1, FAKE.as().refreshCount());
    }

    static List<Recorded> creates() {
        return FAKE.pds().requests().stream().filter(r -> r.path().equals(CREATE)).toList();
    }
}
