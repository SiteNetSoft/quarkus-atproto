package org.acme.atproto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A browser for the packaged application, redirects followed by hand: it keeps cookies per name (the application's
 * cookies are {@code Secure} in production mode, which a real browser would only send over https; the requests go to
 * the application's loopback port), optionally signs in to the application with basic auth ({@code link} mode), and
 * sends same-origin form posts with the {@code Origin} of {@link AtprotoFakes#BASE}, as a browser on the application's
 * own page would.
 */
final class Browser {

    static final String SESSION = "atproto-session";
    static final String BINDING = "atproto-session-binding";
    private static final HttpClient JDK = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final String basicAuth;

    private Browser(String basicAuth) {
        this.basicAuth = basicAuth;
    }

    static Browser anonymous() {
        return new Browser(null);
    }

    /** Signed in to the application with its own mechanism (basic auth), as in {@code link} mode. */
    static Browser signedInAs(String user, String password) {
        return new Browser("Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
    }

    static String app(String pathAndQuery) {
        return "http://127.0.0.1:" + AtprotoFakes.appPort() + pathAndQuery;
    }

    HttpResponse<String> get(String pathAndQuery) {
        return send(request(app(pathAndQuery)).GET());
    }

    HttpResponse<String> post(String pathAndQuery) {
        return send(request(app(pathAndQuery)).POST(HttpRequest.BodyPublishers.noBody()));
    }

    /** A form post from one of the application's pages ({@code Origin} = the application's origin). */
    HttpResponse<String> postForm(String path, Map<String, String> form) {
        return postForm(path, form, AtprotoFakes.BASE);
    }

    HttpResponse<String> postForm(String path, Map<String, String> form, String origin) {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
        HttpRequest.Builder b = request(app(path)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) {
            b.header("Origin", origin);
        }
        return send(b);
    }

    /**
     * The whole sign-in from the application's login form: login → the authorization server (auto-approves) → the
     * callback (its URL's path and query, on the application's port, with the binding cookie). Returns the callback's
     * answer.
     */
    HttpResponse<String> signIn(String handle) {
        HttpResponse<String> login = postForm("/atproto/login", Map.of("handle", handle, "redirect", "/"));
        assertEquals(302, login.statusCode(), () -> "login: " + login.statusCode() + " " + login.body());
        URI authorize = URI.create(login.headers().firstValue("Location").orElseThrow());
        HttpResponse<String> approved = sendRaw(HttpRequest.newBuilder(authorize).GET());
        assertEquals(302, approved.statusCode(), () -> "authorize: " + approved.statusCode() + " " + approved.body());
        URI callback = URI.create(approved.headers().firstValue("Location").orElseThrow());
        assertEquals(AtprotoFakes.BASE + "/atproto/oauth/callback", callback.getScheme() + "://" + callback.getHost()
                + callback.getPath(), callback::toString);
        assertNotNull(cookies.get(BINDING), "no binding cookie after login");
        return get(callback.getRawPath() + "?" + callback.getRawQuery());
    }

    String cookie(String name) {
        return cookies.get(name);
    }

    private HttpRequest.Builder request(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        if (!cookies.isEmpty()) {
            b.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; ")));
        }
        if (basicAuth != null) {
            b.header("Authorization", basicAuth);
        }
        return b;
    }

    private HttpResponse<String> send(HttpRequest.Builder b) {
        HttpResponse<String> r = sendRaw(b);
        for (String setCookie : r.headers().allValues("Set-Cookie")) {
            String pair = setCookie.split(";", 2)[0];
            int eq = pair.indexOf('=');
            String name = pair.substring(0, eq).strip();
            String value = pair.substring(eq + 1).strip();
            boolean expired = setCookie.toLowerCase(java.util.Locale.ROOT).contains("max-age=0");
            if (value.isEmpty() || expired) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
        return r;
    }

    private static HttpResponse<String> sendRaw(HttpRequest.Builder b) {
        try {
            return JDK.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
