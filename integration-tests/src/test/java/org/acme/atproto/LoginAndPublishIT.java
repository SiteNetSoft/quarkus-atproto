package org.acme.atproto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * The acceptance test, {@code login} mode: the packaged sample application signs a user in with AT Protocol OAuth
 * (PAR, DPoP, PKCE, {@code private_key_jwt}) against the fakes and writes a {@code site.standard.publication} and a
 * {@code site.standard.document} pointing at it to the user's PDS.
 */
@QuarkusIntegrationTest
@WithTestResource(AtprotoFakes.class)
class LoginAndPublishIT {

    @AfterEach
    void reset() {
        AtprotoFakes.clear();
    }

    @Test
    void signsInAndPublishesADocument() {
        Browser browser = Browser.anonymous();
        HttpResponse<String> page = browser.get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<form method=\"post\" action=\"/atproto/login\""), page.body());

        HttpResponse<String> callback = browser.signIn("@Alice.Example.Test");
        assertEquals(302, callback.statusCode(), callback::body);
        assertEquals("/", callback.headers().firstValue("Location").orElseThrow());
        assertNotNull(browser.cookie(Browser.SESSION), "no session cookie");

        JsonNode me = Json.parse(browser.get("/me"));
        assertEquals(AtprotoFakes.alice().did().value(), me.path("did").asText());
        assertEquals(AtprotoFakes.HANDLE, me.path("handle").asText());
        assertTrue(me.path("scope").asText().contains("repo:site.standard.document"), me.toString());

        JsonNode publication = Json.parse(browser.post(
                "/publish/publication?name=Field%20Notes&url=https%3A%2F%2Fblog.example.com"));
        AtUri publicationUri = AtUri.parse(publication.path("uri").asText());
        assertEquals("site.standard.publication", publicationUri.collection().orElseThrow().value());

        // the document form as the sample's page posts it (form body, not query)
        JsonNode document = Json.parse(browser.postForm("/publish/document",
                Map.of("title", "Hello, AT Protocol", "path", "/hello", "text", "Written from Quarkus.")));
        AtUri documentUri = AtUri.parse(document.path("uri").asText());

        JsonNode stored = AtprotoFakes.fake().pds().records().get(documentUri);
        assertNotNull(stored, () -> "not on the PDS: " + documentUri + " " + AtprotoFakes.fake().pds().records());
        assertEquals(AtprotoFakes.alice().did(), documentUri.authority());
        assertEquals("site.standard.document", stored.path("$type").asText());
        assertEquals(publicationUri.toString(), stored.path("site").asText());
        assertEquals("Hello, AT Protocol", stored.path("title").asText());
        assertEquals("/hello", stored.path("path").asText());
        assertEquals("Written from Quarkus.", stored.path("textContent").asText());
        // a valid record: it reads back as the generated Lexicon type
        site.standard.Document typed = Json.read(stored, site.standard.Document.class);
        assertEquals(publicationUri.toString(), typed.site().toString());
        assertNotNull(typed.publishedAt());

        JsonNode storedPublication = AtprotoFakes.fake().pds().records().get(publicationUri);
        assertEquals("Field Notes", storedPublication.path("name").asText());
        assertEquals("https://blog.example.com", storedPublication.path("url").asText());

        assertEquals(204, browser.post("/atproto/logout").statusCode());
        assertEquals(401, browser.get("/me").statusCode());
    }

    @Test
    void signInStartsOnlyWithASameOriginPost() {
        Browser browser = Browser.anonymous();
        assertEquals(405, browser.get("/atproto/login?handle=" + AtprotoFakes.HANDLE).statusCode());
        assertEquals(403, browser.postForm("/atproto/login", Map.of("handle", AtprotoFakes.HANDLE),
                "https://evil.example").statusCode());
        assertEquals(0, AtprotoFakes.fake().as().parCount());
        HttpResponse<String> me = browser.get("/me");
        assertEquals(401, me.statusCode());
        // basic auth is enabled in the sample (for link mode), but signed-out users here must not get its prompt
        assertTrue(me.headers().allValues("WWW-Authenticate").stream()
                .noneMatch(v -> v.toLowerCase(java.util.Locale.ROOT).startsWith("basic")), me.headers()::toString);
        assertEquals(401, browser.post("/publish/publication?name=x&url=https%3A%2F%2Fx.example").statusCode());
    }
}
