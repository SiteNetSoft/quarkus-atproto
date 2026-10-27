package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/** The link-mode browser: signed in to the application with basic auth as {@code ann} or {@code ben}. */
final class LinkBrowser {

    private LinkBrowser() {
    }

    static RequestSpecification as(String user) {
        RequestSpecification spec = given().redirects().follow(false);
        if (user == null) {
            return spec;
        }
        return spec.auth().preemptive().basic(user, user.equals(OAuthTestSupport.ANN) ? OAuthTestSupport.ANN_PASSWORD
                : OAuthTestSupport.BEN_PASSWORD);
    }

    /** Starts a link from the application's own page (same-origin POST). */
    static Response startLink(String user, String handle) {
        return Browser.sameOrigin(as(user)).formParam("handle", handle).post("/atproto/login");
    }

    /** The callback in the browser that started the login (its binding cookie), signed in to the app as {@code user}. */
    static Response callback(String user, URI callbackUrl) {
        String local = "http://127.0.0.1:" + Browser.port() + callbackUrl.getRawPath() + "?" + callbackUrl.getRawQuery();
        return as(user).urlEncodingEnabled(false).cookie(Browser.BINDING, Browser.binding(callbackUrl)).get(local);
    }

    static Response link(String user, String handle) {
        Response cb = callback(user, Browser.authorize(startLink(user, handle)));
        assertEquals(302, cb.statusCode(), () -> "callback: " + cb.statusCode() + " " + cb.body().asString());
        return cb;
    }
}
