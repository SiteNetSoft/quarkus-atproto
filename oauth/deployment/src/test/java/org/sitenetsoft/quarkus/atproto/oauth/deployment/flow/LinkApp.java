package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.quarkus.atproto.oauth.security.AtprotoUser;
import org.sitenetsoft.quarkus.atproto.xrpc.RecordResult;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

/** The link-mode application: users sign in with basic auth, then link Bluesky accounts. */
@Path("/")
public class LinkApp {

    static final org.sitenetsoft.atproto.syntax.Nsid DOCUMENT = org.sitenetsoft.atproto.syntax.Nsid.parse("site.standard.document");

    @Inject
    AtprotoUser user;

    @Inject
    SecurityIdentity identity;

    @GET
    @Path("me")
    @Authenticated
    public String me() {
        return identity.getPrincipal().getName();
    }

    @GET
    @Path("linked")
    @Authenticated
    public Response linked() {
        try {
            return Response.ok(user.did().value() + " " + user.handle().value()).build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity(e.getMessage()).build();
        }
    }

    @POST
    @Path("publish")
    @Authenticated
    public Response publish(@QueryParam("title") String title, @QueryParam("did") String did) {
        try {
            AtprotoUser u = did == null ? user : user.forDid(Did.parse(did));
            RecordResult r = u.records().create(DOCUMENT, Map.of("site", "https://example.com",
                    "title", title == null ? "Hello" : title, "publishedAt", "2026-10-27T10:00:00.000Z"));
            return Response.ok(r.uri().toString()).build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity(e.getMessage()).build();
        }
    }
}
