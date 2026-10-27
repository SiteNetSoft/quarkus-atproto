package org.acme.atproto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sitenetsoft.atproto.syntax.AtUri;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * The acceptance test, {@code link} mode: the packaged sample application runs with the {@code link} profile
 * (failsafe execution {@code link-mode}, {@code quarkus.test.integration-test-profile=link}). The user signs in to the
 * application with its own mechanism (basic auth, user {@code ann}), links their AT Protocol account, publishes, and
 * unlinks.
 */
@QuarkusIntegrationTest
@WithTestResource(AtprotoFakes.class)
class LinkModeIT {

    @AfterEach
    void reset() {
        AtprotoFakes.clear();
    }

    @Test
    void linksAnAccountAndPublishes() {
        Browser ann = Browser.signedInAs("ann", "ann-password");

        // signed in to the app but nothing linked yet
        HttpResponse<String> unlinked = ann.get("/me");
        assertEquals(409, unlinked.statusCode(), unlinked::body);

        HttpResponse<String> callback = ann.signIn(AtprotoFakes.HANDLE);
        assertEquals(302, callback.statusCode(), callback::body);
        assertNull(ann.cookie(Browser.SESSION), "link mode sets no session cookie of its own");

        JsonNode me = Json.parse(ann.get("/me"));
        assertEquals("ann", me.path("principal").asText());
        assertEquals(AtprotoFakes.alice().did().value(), me.path("did").asText());

        AtUri publication = AtUri.parse(Json.parse(ann.post(
                "/publish/publication?name=Ann%27s%20blog&url=https%3A%2F%2Fann.example.com")).path("uri").asText());
        AtUri document = AtUri.parse(Json.parse(ann.post(
                "/publish/document?title=Linked&path=%2Flinked&text=From%20a%20linked%20account")).path("uri").asText());

        JsonNode stored = AtprotoFakes.fake().pds().records().get(document);
        assertNotNull(stored, () -> "not on the PDS: " + document);
        assertEquals("site.standard.document", stored.path("$type").asText());
        assertEquals(publication.toString(), stored.path("site").asText());
        assertEquals(AtprotoFakes.alice().did(), document.authority());

        assertEquals(204, ann.postForm("/atproto/unlink", Map.of()).statusCode());
        assertEquals(409, ann.get("/me").statusCode());
    }

    @Test
    void linkingNeedsTheApplicationsSignIn() {
        Browser anonymous = Browser.anonymous();
        assertEquals(401, anonymous.postForm("/atproto/login", Map.of("handle", AtprotoFakes.HANDLE)).statusCode());
        assertEquals(0, AtprotoFakes.fake().as().parCount());
        // link mode: the application's own mechanism (basic auth) challenges
        HttpResponse<String> me = anonymous.get("/me");
        assertEquals(401, me.statusCode());
        assertTrue(me.headers().allValues("WWW-Authenticate").stream()
                .anyMatch(v -> v.toLowerCase(java.util.Locale.ROOT).startsWith("basic")), me.headers()::toString);
        HttpResponse<String> page = anonymous.get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("Link mode"), page.body());
    }
}
