package org.sitenetsoft.quarkus.atproto.oauth.flow;

import java.util.Optional;

import jakarta.enterprise.event.Event;
import jakarta.inject.Singleton;

import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;
import org.sitenetsoft.quarkus.atproto.oauth.events.AtprotoLoggedOut;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionCookie;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionStores;

import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code POST {logout-path}}: revokes the session's refresh token at the authorization server's
 * {@code revocation_endpoint} when it has one (best effort), deletes the session, clears the cookie, 204. Without a
 * session it only clears the cookie. {@code link} mode has no session cookie: 404 (unlink instead).
 */
@Singleton
public class LogoutHandler implements Handler<RoutingContext> {

    private final OAuthConfig config;
    private final SessionStores stores;
    private final SessionCookie cookie;
    private final SessionRevoker revoker;
    private final Event<AtprotoLoggedOut> loggedOut;

    public LogoutHandler(OAuthConfig config, SessionStores stores, SessionCookie cookie, SessionRevoker revoker,
            Event<AtprotoLoggedOut> loggedOut) {
        this.config = config;
        this.stores = stores;
        this.cookie = cookie;
        this.revoker = revoker;
        this.loggedOut = loggedOut;
    }

    @Override
    public void handle(RoutingContext ctx) {
        if (config.mode() != OAuthConfig.Mode.LOGIN) {
            ctx.next(); // link mode: not this extension's endpoint (404 unless the application serves it)
            return;
        }
        Context context = ctx.vertx().getOrCreateContext();
        Optional<String> id = cookie.sessionId(ctx.request());
        Uni<Void> work = id.isEmpty() ? Uni.createFrom().voidItem()
                : stores.sessions().find(id.get()).chain(s -> s.map(this::end).orElse(Uni.createFrom().voidItem()));
        work.subscribe().with(v -> Responses.on(context, () -> done(ctx)),
                error -> Responses.on(context, () -> Responses.fail(ctx, config, "logout", error)));
    }

    private void done(RoutingContext ctx) {
        cookie.clear(ctx);
        ctx.response().setStatusCode(204).putHeader("Cache-Control", "no-store").end();
    }

    /** Revoke (best effort), delete, tell observers. */
    Uni<Void> end(AtprotoSession session) {
        return revoker.end(session).chain(v -> EventFiring.fire(loggedOut,
                new AtprotoLoggedOut(session.did(), Optional.empty())));
    }
}
