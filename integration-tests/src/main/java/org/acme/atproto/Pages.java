package org.acme.atproto;

import java.net.URI;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * The sample's only page. The sign-in form posts {@code handle} to the login endpoint: a sign-in must start with a
 * same-origin {@code POST} from the application's own page (a link or a cross-site form is refused).
 */
@Path("/")
public class Pages {

    @Inject
    SecurityIdentity identity;

    @ConfigProperty(name = "quarkus.atproto.oauth.mode", defaultValue = "login")
    String mode;

    @ConfigProperty(name = "quarkus.atproto.oauth.login-path", defaultValue = "/atproto/login")
    String loginPath;

    @ConfigProperty(name = "quarkus.atproto.oauth.logout-path", defaultValue = "/atproto/logout")
    String logoutPath;

    @ConfigProperty(name = "quarkus.atproto.oauth.unlink-path", defaultValue = "/atproto/unlink")
    String unlinkPath;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String index() {
        boolean link = "link".equalsIgnoreCase(mode);
        StringBuilder html = new StringBuilder("""
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
                <title>quarkus-atproto sample</title>
                <style>body{font:16px/1.5 system-ui,sans-serif;max-width:40rem;margin:2rem auto;padding:0 1rem}
                form{margin:1rem 0;display:flex;gap:.5rem;flex-wrap:wrap}input{flex:1;min-width:12rem}</style>
                </head><body>
                <h1>quarkus-atproto sample</h1>
                """);
        if (link) {
            html.append(identity.isAnonymous()
                    ? "<p>Link mode: <a href=\"/app-login\">sign in to this application</a> first, then link your "
                            + "Bluesky account.</p>\n"
                    : "<p>Signed in to this application as <b>" + escape(identity.getPrincipal().getName())
                            + "</b>.</p>\n");
        } else if (!identity.isAnonymous()) {
            html.append("<p>Signed in as <b>").append(escape(identity.getPrincipal().getName())).append("</b>.</p>\n");
        }
        html.append("<form method=\"post\" action=\"").append(escape(loginPath)).append("\">\n")
                .append("  <input name=\"handle\" placeholder=\"alice.bsky.social\" required>\n")
                .append("  <input type=\"hidden\" name=\"redirect\" value=\"/\">\n")
                .append("  <button>").append(link ? "Link" : "Sign in with").append(" Bluesky</button>\n</form>\n");
        html.append("""
                <h2>Publish</h2>
                <form method="post" action="/publish/publication">
                  <input name="name" placeholder="Publication name" required>
                  <input name="url" placeholder="https://blog.example.com" required>
                  <button>Publish publication</button>
                </form>
                <form method="post" action="/publish/document">
                  <input name="title" placeholder="Title" required>
                  <input name="path" placeholder="/hello">
                  <input name="text" placeholder="Plain text">
                  <button>Publish document</button>
                </form>
                <p><a href="/me">/me</a></p>
                """);
        html.append("<form method=\"post\" action=\"").append(escape(link ? unlinkPath : logoutPath))
                .append("\"><button>").append(link ? "Unlink" : "Sign out").append("</button></form>\n");
        return html.append("</body></html>\n").toString();
    }

    /** {@code link} mode: makes the browser sign in with the application's own mechanism (basic auth here). */
    @GET
    @Path("app-login")
    @Authenticated
    public Response appLogin() {
        return Response.seeOther(URI.create("/")).build();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
