package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import org.eclipse.microprofile.config.ConfigProvider;

import io.restassured.response.Response;

/**
 * A browser for the login flow, redirects followed by hand: the app's routes through REST Assured, the fake
 * authorization server's {@code /oauth/authorize} through the JDK client.
 */
final class Browser {

    static final String COOKIE = "atproto-session";
    private static final HttpClient JDK = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** A finished login: the authorization server's redirect to the callback, the callback's answer, the cookie. */
    record Login(URI callbackUrl, Response callback, String cookie) {
    }

    private Browser() {
    }

    static int port() {
        return ConfigProvider.getConfig().getValue("quarkus.http.test-port", Integer.class);
    }

    /** {@code GET /atproto/login?handle=...}, not followed. */
    static Response startLogin(String handle) {
        return given().redirects().follow(false).queryParam("handle", handle).get("/atproto/login");
    }

    /** {@code GET /atproto/login?handle=...&redirect=...}, not followed. */
    static Response startLogin(String handle, String redirect) {
        return given().redirects().follow(false).queryParam("handle", handle).queryParam("redirect", redirect)
                .get("/atproto/login");
    }

    /** Login started, sent to the authorization server: the URL it redirects back to (the callback). */
    static URI authorize(Response login) {
        assertEquals(302, login.statusCode(), () -> "login: " + login.statusCode() + " " + login.body().asString());
        URI authorize = URI.create(login.header("Location"));
        HttpResponse<String> r = jdkGet(authorize);
        assertEquals(302, r.statusCode(), () -> "authorize: " + r.statusCode() + " " + r.body());
        return URI.create(r.headers().firstValue("Location").orElseThrow());
    }

    /** {@code GET} the callback URL on the app (its origin replaced by the test server's). */
    static Response callback(URI callbackUrl) {
        String local = "http://127.0.0.1:" + port() + callbackUrl.getRawPath() + "?" + callbackUrl.getRawQuery();
        return given().redirects().follow(false).urlEncodingEnabled(false).get(local);
    }

    /** The whole flow for {@code handle}; asserts the callback redirected and set the session cookie. */
    static Login login(String handle) {
        URI callbackUrl = authorize(startLogin(handle));
        Response cb = callback(callbackUrl);
        assertEquals(302, cb.statusCode(), () -> "callback: " + cb.statusCode() + " " + cb.body().asString());
        String cookie = cb.cookie(COOKIE);
        assertNotNull(cookie, "no session cookie");
        return new Login(callbackUrl, cb, cookie);
    }

    static Response get(String path, String cookie) {
        return with(cookie).get(path);
    }

    static Response post(String path, String cookie) {
        return with(cookie).post(path);
    }

    static Response publish(String cookie, String title) {
        return with(cookie).queryParam("title", title).post("/publish");
    }

    private static io.restassured.specification.RequestSpecification with(String cookie) {
        io.restassured.specification.RequestSpecification spec = given().redirects().follow(false);
        return cookie == null ? spec : spec.cookie(COOKIE, cookie);
    }

    /** A query parameter of {@code uri}, decoded. */
    static Optional<String> param(URI uri, String name) {
        String q = uri.getRawQuery();
        if (q == null) {
            return Optional.empty();
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (k.equals(name)) {
                return Optional.of(java.net.URLDecoder.decode(eq < 0 ? "" : pair.substring(eq + 1),
                        java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }

    static HttpResponse<String> jdkGet(URI uri) {
        try {
            return JDK.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
