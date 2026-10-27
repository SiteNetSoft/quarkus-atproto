package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import java.util.Map;
import java.util.TreeSet;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.quarkus.atproto.oauth.auth.AtprotoScopeException;
import org.sitenetsoft.quarkus.atproto.oauth.security.AtprotoUser;
import org.sitenetsoft.quarkus.atproto.xrpc.RecordResult;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

/** The application endpoints the tests drive. */
@Path("/")
public class TestApp {

    static final Nsid DOCUMENT = Nsid.parse("site.standard.document");
    static final Nsid PUBLICATION = Nsid.parse("site.standard.publication");

    @Inject
    AtprotoUser user;

    @Inject
    SecurityIdentity identity;

    @GET
    @Path("me")
    @Authenticated
    public String me() {
        return user.did().value();
    }

    @GET
    @Path("scope")
    @Authenticated
    public String scope() {
        return user.scope();
    }

    @GET
    @Path("whoami")
    @Authenticated
    public String whoami() {
        return identity.getPrincipal().getName() + " " + identity.getAttribute("atproto.handle") + " "
                + identity.getAttribute("atproto.pds") + " " + new TreeSet<>(identity.getRoles());
    }

    @GET
    @Path("admin")
    @jakarta.annotation.security.RolesAllowed("admin")
    public String admin() {
        return "admin";
    }

    @POST
    @Path("publish")
    @Authenticated
    public String publish(@QueryParam("title") String title) {
        RecordResult r = user.records().create(DOCUMENT, Map.of("site", "https://example.com",
                "title", title == null ? "Hello" : title, "publishedAt", "2026-10-27T10:00:00.000Z"));
        return r.uri().toString();
    }

    @POST
    @Path("publication")
    @Authenticated
    public Response publication() {
        try {
            RecordResult r = user.records().create(PUBLICATION, Map.of("url", "https://example.com", "name", "Blog"));
            return Response.ok(r.uri().toString()).build();
        } catch (AtprotoScopeException e) {
            return Response.status(403).entity(e.getMessage()).build();
        }
    }
}
