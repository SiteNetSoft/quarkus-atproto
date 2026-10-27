package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.AtUri;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.test.FakeAccount;
import org.sitenetsoft.quarkus.atproto.test.FakeAtproto;
import org.sitenetsoft.quarkus.atproto.test.Recorded;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.QuarkusUnitTest;
import io.restassured.response.Response;

/** Resource requests to the PDS with DPoP: the PDS's own nonce, rotation, the token binding. */
public class DpopResourceTest {

    static final String HANDLE = "alice.example.test";
    static final String CREATE = "/xrpc/com.atproto.repo.createRecord";
    static final int[] PORTS = FakeAtproto.reservePorts(DpopResourceTest.class.getName() + ".ports");
    static final FakeAtproto FAKE = FakeAtproto.start(PORTS[0], PORTS[1], PORTS[2]);
    static final FakeAccount ALICE = FAKE.account(HANDLE);
    static final ObjectMapper JSON = new ObjectMapper();

    @RegisterExtension
    static final QuarkusUnitTest config = OAuthTestSupport.app(FAKE, List.of(HANDLE));

    @Inject
    AtprotoSessionStore sessions;

    @AfterEach
    void reset() {
        FAKE.clear();
        sessions.findByDid(ALICE.did()).forEach(s -> sessions.delete(s.id()));
    }

    @Test
    void publishWritesDocument() {
        Browser.Login login = Browser.login(HANDLE);
        Response r = Browser.publish(login.cookie(), "Hello AT");
        assertEquals(200, r.statusCode(), r.body().asString());
        AtUri uri = AtUri.parse(r.body().asString());
        Map<AtUri, JsonNode> records = FAKE.pds().records();
        assertEquals(1, records.size());
        JsonNode stored = records.get(uri);
        assertEquals("site.standard.document", stored.get("$type").asText());
        assertEquals("Hello AT", stored.get("title").asText());
        assertTrue(uri.toString().startsWith("at://" + ALICE.did().value() + "/site.standard.document/"), uri.toString());
    }

    @Test
    void pdsNonceRotationRetriedTransparently() {
        Browser.Login login = Browser.login(HANDLE);
        assertEquals(200, Browser.publish(login.cookie(), "one").statusCode());
        int before = creates().size();
        FAKE.pds().rotateNonceNow();
        Response second = Browser.publish(login.cookie(), "two");
        assertEquals(200, second.statusCode(), second.body().asString());
        List<Recorded> after = creates().subList(before, creates().size());
        // the old nonce is refused once (401 use_dpop_nonce), the retry with the rotated one is accepted
        assertEquals(List.of(401, 200), after.stream().map(Recorded::status).toList());
        assertEquals(FAKE.pds().nonce(), claims(after.get(1)).get("nonce").asText());
        assertEquals(2, FAKE.pds().records().size());
        // and the third publish uses the stored nonce straight away
        assertEquals(200, Browser.publish(login.cookie(), "three").statusCode());
        assertEquals(List.of(200), creates().subList(before + 2, creates().size()).stream().map(Recorded::status)
                .toList());
    }

    @Test
    void proofUsesPdsNonceNotAsNonce() {
        Browser.Login login = Browser.login(HANDLE);
        assertEquals(200, Browser.publish(login.cookie(), "one").statusCode());
        assertEquals(200, Browser.publish(login.cookie(), "two").statusCode());
        String asNonce = FAKE.as().nonce();
        String pdsNonce = FAKE.pds().nonce();
        assertNotEquals(asNonce, pdsNonce);
        List<Recorded> creates = creates();
        for (Recorded r : creates) {
            JsonNode c = claims(r);
            assertNotEquals(asNonce, c.path("nonce").asText(null), "a PDS proof carried the authorization server's nonce");
            assertEquals("POST", c.get("htm").asText());
            assertEquals(FAKE.pdsUrl() + CREATE, c.get("htu").asText());
            assertTrue(c.hasNonNull("ath"));
            assertTrue(r.header("authorization").orElseThrow().startsWith("DPoP "));
        }
        for (Recorded r : creates.stream().filter(r -> r.status() == 200).toList()) {
            assertEquals(pdsNonce, claims(r).get("nonce").asText());
        }
        // token and PAR proofs used the authorization server's nonce
        Recorded token = FAKE.as().requests().stream().filter(r -> r.path().equals("/oauth/token") && r.status() == 200)
                .findFirst().orElseThrow();
        assertEquals(asNonce, claims(token).get("nonce").asText());
    }

    static List<Recorded> creates() {
        return FAKE.pds().requests().stream().filter(r -> r.path().equals(CREATE)).toList();
    }

    static JsonNode claims(Recorded r) {
        String jws = r.header("dpop").orElseThrow();
        try {
            return JSON.readTree(Base64.getUrlDecoder().decode(jws.split("\\.")[1]));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
