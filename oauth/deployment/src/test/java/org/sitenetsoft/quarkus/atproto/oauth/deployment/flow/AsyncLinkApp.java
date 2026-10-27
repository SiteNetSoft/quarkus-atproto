package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.quarkus.atproto.oauth.security.AtprotoUser;

import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;

/** Link-mode endpoints that run on the event loop (they return {@link Uni}). */
@Path("/async")
public class AsyncLinkApp {

    static final Nsid DOCUMENT = Nsid.parse("site.standard.document");

    @Inject
    AtprotoUser user;

    @GET
    @Path("linked")
    @Authenticated
    public Uni<String> linked() {
        boolean loop = Context.isOnEventLoopThread();
        return user.didAsync().map(did -> did.value() + " loop=" + loop);
    }

    @POST
    @Path("publish")
    @Authenticated
    public Uni<String> publish(@QueryParam("title") String title) {
        return user.reactiveRecordsAsync().chain(r -> r.create(DOCUMENT, Map.of("site", "https://example.com",
                "title", title, "publishedAt", "2026-10-27T10:00:00.000Z"))).map(r -> r.uri().toString());
    }

    /** The synchronous accessors after {@code resolve()}: answered from the request's cache, no store read. */
    @GET
    @Path("handle")
    @Authenticated
    public Uni<String> handle() {
        return user.resolve().map(u -> u.handle().value() + " " + u.scope());
    }

    /** The synchronous accessor without {@code resolve()} on the event loop: fails fast with the way out. */
    @GET
    @Path("sync")
    @Authenticated
    public Uni<Response> sync() {
        try {
            return Uni.createFrom().item(Response.ok(user.did().value()).build());
        } catch (IllegalStateException e) {
            return Uni.createFrom().item(Response.status(409).entity(e.getMessage() + " loop="
                    + Context.isOnEventLoopThread()).build());
        }
    }
}
