package org.acme.atproto;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.sitenetsoft.atproto.syntax.AtUri;
import org.sitenetsoft.atproto.syntax.Datetime;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoSessionExpired;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoTemporarilyUnavailable;
import org.sitenetsoft.quarkus.atproto.oauth.auth.AtprotoScopeException;
import org.sitenetsoft.quarkus.atproto.oauth.security.AtprotoUser;
import org.sitenetsoft.quarkus.atproto.xrpc.RecordResult;
import org.sitenetsoft.quarkus.atproto.xrpc.RecordValidationException;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcException;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import site.standard.Document;
import site.standard.Publication;

/**
 * Publishing to the signed-in user's own repository: a {@code site.standard.publication} (the blog), then
 * {@code site.standard.document}s (its posts) pointing at it. Works in both sign-in modes: in {@code login} mode the
 * user is the AT Protocol account; in {@code link} mode the application's user and their linked account.
 * <p>
 * Parameters come from the query string or a form body (the sample's page posts forms). These endpoints are blocking
 * (worker threads), so the synchronous {@link AtprotoUser} accessors are fine.
 */
@Path("/")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class PublishResource {

    /** The publication each account published last (in memory: enough for a sample). */
    private static final Map<Did, AtUri> PUBLICATIONS = new ConcurrentHashMap<>();

    @Inject
    AtprotoUser user;

    @Inject
    SecurityIdentity identity;

    /** Who is signed in: the application principal, the AT Protocol account and the granted scope. */
    @GET
    @Path("me")
    public Response me() {
        return call(() -> {
            Map<String, Object> me = new LinkedHashMap<>();
            me.put("principal", identity.getPrincipal().getName());
            me.put("did", user.did().value());
            me.put("handle", user.handle().value());
            me.put("pds", user.pds().toString());
            me.put("scope", user.scope());
            return me;
        });
    }

    /** {@code POST /publish/publication?name&url}: creates the blog's {@code site.standard.publication}. */
    @POST
    @Path("publish/publication")
    @Consumes({ MediaType.APPLICATION_FORM_URLENCODED, MediaType.WILDCARD })
    public Response publication(@QueryParam("name") String name, @QueryParam("url") String url,
            @FormParam("name") String formName, @FormParam("url") String formUrl) {
        String n = first(name, formName);
        String u = first(url, formUrl);
        if (n == null || u == null) {
            return error(400, "name and url are required");
        }
        return call(() -> {
            Publication publication = new Publication(URI.create(u), null, n, null, null, null, null);
            RecordResult r = user.records().create(org.sitenetsoft.atproto.syntax.Nsid.parse(Publication.NSID),
                    publication);
            PUBLICATIONS.put(user.did(), r.uri());
            return result(r);
        });
    }

    /**
     * {@code POST /publish/document?title&path&text}: creates a {@code site.standard.document} in the account's
     * publication (the one published last here, or {@code site} = a publication's {@code at://} URI).
     */
    @POST
    @Path("publish/document")
    @Consumes({ MediaType.APPLICATION_FORM_URLENCODED, MediaType.WILDCARD })
    public Response document(@QueryParam("title") String title, @QueryParam("path") String path,
            @QueryParam("text") String text, @QueryParam("site") String site, @FormParam("title") String formTitle,
            @FormParam("path") String formPath, @FormParam("text") String formText,
            @FormParam("site") String formSite) {
        String t = first(title, formTitle);
        if (t == null) {
            return error(400, "title is required");
        }
        String p = first(path, formPath);
        String body = first(text, formText);
        String s = first(site, formSite);
        return call(() -> {
            AtUri publication = s != null ? AtUri.parse(s) : PUBLICATIONS.get(user.did());
            if (publication == null) {
                throw new NoPublication();
            }
            Document document = new Document(p, URI.create(publication.toString()), null, null, t, null, null, null,
                    null, null, null, Datetime.now(), body, null);
            return result(user.records().create(org.sitenetsoft.atproto.syntax.Nsid.parse(Document.NSID), document));
        });
    }

    private static Map<String, Object> result(RecordResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uri", r.uri().toString());
        out.put("cid", r.cid().toString());
        r.validationStatus().ifPresent(v -> out.put("validationStatus", v));
        return out;
    }

    private static String first(String a, String b) {
        String v = a != null && !a.isBlank() ? a : b;
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static final class NoPublication extends RuntimeException {
        NoPublication() {
            super("publish a publication first (or pass site=<at:// URI of a site.standard.publication>)", null,
                    false, false);
        }
    }

    /** Maps the failures an application should expect to answers a user can act on. */
    private static Response call(java.util.function.Supplier<Object> work) {
        try {
            return Response.ok(work.get()).build();
        } catch (NoPublication e) {
            return error(409, e.getMessage());
        } catch (AtprotoScopeException e) {
            return error(403, e.getMessage());
        } catch (RecordValidationException e) {
            return error(422, e.getMessage());
        } catch (AtprotoSessionExpired e) {
            return error(401, e.getMessage());
        } catch (AtprotoTemporarilyUnavailable e) {
            return error(503, e.getMessage());
        } catch (XrpcException e) {
            return error(502, "the PDS answered " + e.status() + " " + e.error() + ": " + e.errorMessage());
        } catch (IllegalStateException e) {
            // link mode: no linked account, or several
            return error(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        }
    }

    private static Response error(int status, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(Map.of("error", message)).build();
    }
}
