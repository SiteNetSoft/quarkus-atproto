package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;

import io.quarkus.test.QuarkusUnitTest;

/** An application {@code AtprotoRoleMapper} adds roles; the extension's {@code user} role stays. */
public class RoleMapperTest {

    static final String HANDLE = "alice.example.test";
    static final String BOB_HANDLE = "bob.example.test";
    static final int[] PORTS = FakeAtproto.reservePorts(RoleMapperTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);
    static final FakeAccount BOB = FAKE.account(BOB_HANDLE);

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.app(FAKE, List.of(HANDLE, BOB_HANDLE),
            AdminRoleMapper.class);

    @Inject
    AtprotoSessionStore sessions;

    @AfterEach
    void reset() {
        FAKE.clear();
        for (FakeAccount a : List.of(ALICE, BOB)) {
            sessions.findByDid(a.did()).forEach(s -> sessions.delete(s.id()));
        }
    }

    @Test
    void appMapperAddsRole() {
        AdminRoleMapper.admin = ALICE.did();
        String alice = Browser.login(HANDLE).cookie();
        String bob = Browser.login(BOB_HANDLE).cookie();
        assertEquals(ALICE.did().value() + " " + HANDLE + " " + FAKE.pdsUrl() + " [admin, user]",
                Browser.get("/whoami", alice).body().asString());
        assertEquals(BOB.did().value() + " " + BOB_HANDLE + " " + FAKE.pdsUrl() + " [user]",
                Browser.get("/whoami", bob).body().asString());
        assertEquals(200, Browser.get("/admin", alice).statusCode());
        assertEquals(403, Browser.get("/admin", bob).statusCode());
    }
}
