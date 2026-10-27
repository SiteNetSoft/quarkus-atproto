package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoTemporarilyUnavailable;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.oauth.session.Refresher;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;

import io.quarkus.test.QuarkusUnitTest;

/**
 * Several nodes, one store: only the holder of the store's refresh lease spends the refresh token; the others wait for
 * its tokens (or give up with {@link AtprotoTemporarilyUnavailable}), so the authorization server never sees the token
 * twice.
 */
public class RefreshLeaseTest {

    static final String HANDLE = "alice.example.test";
    static final int[] PORTS = FakeAtproto.reservePorts(RefreshLeaseTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.app(FAKE, List.of(HANDLE), LeasedSessionStore.class)
            .overrideConfigKey("quarkus.atproto.oauth.session.refresh-lease", "2s");

    @Inject
    AtprotoSessionStore sessions;

    @Inject
    Refresher refresher;

    @AfterEach
    void reset() {
        FAKE.clear();
        LeasedSessionStore.reset();
        sessions.findByDid(ALICE.did()).forEach(s -> sessions.delete(s.id()));
    }

    private AtprotoSession loggedIn() {
        Browser.login(HANDLE);
        return sessions.findByDid(ALICE.did()).get(0);
    }

    private CompletableFuture<AtprotoSession> refreshAsync(AtprotoSession s) {
        return refresher.refresh(s.id(), s.accessToken()).subscribeAsCompletionStage();
    }

    @Test
    void leaseTakenAndGivenBackAroundTheSpend() {
        AtprotoSession s = loggedIn();
        AtprotoSession refreshed = refresher.refresh(s.id(), s.accessToken()).await().atMost(Duration.ofSeconds(20));
        assertEquals(1, FAKE.as().refreshCount());
        assertTrue(!refreshed.refreshToken().equals(s.refreshToken()));
        assertEquals(1, LeasedSessionStore.acquired.size());
        assertEquals(LeasedSessionStore.acquired, LeasedSessionStore.released);
        // the lease key is a hash: the refresh token itself never reaches the lease
        assertFalse(LeasedSessionStore.acquired.get(0).contains(s.refreshToken()));
    }

    @Test
    void otherNodesTokensAreUsedWithoutSpending() throws Exception {
        AtprotoSession s = loggedIn();
        LeasedSessionStore.heldElsewhere = true;
        CompletableFuture<AtprotoSession> waiting = refreshAsync(s);
        Thread.sleep(300);
        assertFalse(waiting.isDone(), "waits for the lease holder");
        // the other node finishes its refresh and stores its tokens
        sessions.save(new AtprotoSession(s.id(), s.did(), s.handle(), s.pds(), s.issuer(), s.grantedScope(),
                "other-node-access", "other-node-refresh", Instant.now().plusSeconds(900), s.dpopPrivateJwk(),
                s.nonces(), s.created()));
        AtprotoSession result = waiting.get(10, TimeUnit.SECONDS);
        assertEquals("other-node-access", result.accessToken());
        assertEquals(0, FAKE.as().refreshCount(), "no token-endpoint call from this node");
        assertTrue(FAKE.as().requests().stream().noneMatch(r -> r.path().endsWith("/oauth/token")
                && r.body() != null && r.body().contains("grant_type=refresh_token")));
    }

    @Test
    void noTokensFromTheLeaseHolderIsTemporarilyUnavailable() throws Exception {
        AtprotoSession s = loggedIn();
        LeasedSessionStore.heldElsewhere = true;
        ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> refreshAsync(s).get(15, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof AtprotoTemporarilyUnavailable, String.valueOf(e.getCause()));
        assertEquals(0, FAKE.as().refreshCount());
        assertTrue(FAKE.as().requests().stream().noneMatch(r -> r.path().endsWith("/oauth/token")
                && r.body() != null && r.body().contains("grant_type=refresh_token")));
        assertEquals(s.refreshToken(), sessions.find(s.id()).orElseThrow().refreshToken(), "the session is kept");
    }
}
