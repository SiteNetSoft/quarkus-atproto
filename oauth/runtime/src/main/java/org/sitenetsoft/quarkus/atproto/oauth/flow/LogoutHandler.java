package org.sitenetsoft.quarkus.atproto.oauth.flow;

import java.net.URI;
import java.util.Optional;

import jakarta.enterprise.event.Event;
import jakarta.inject.Singleton;

import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;
import org.sitenetsoft.quarkus.atproto.oauth.discovery.Discovery;
import org.sitenetsoft.quarkus.atproto.oauth.events.AtprotoLoggedOut;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionCookie;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionStores;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code POST {logout-path}}: revokes the session's refresh token at the authorization server's
 * {@code revocation_endpoint} when it has one (best effort), deletes the session, clears the cookie, 204. Without a
 * session it only clears the cookie.
 */
@Singleton
public class LogoutHandler implements Handler<RoutingContext> {

    private static final Logger LOG = Logger.getLogger(LogoutHandler.class);

    private final OAuthConfig config;
    private final SessionStores stores;
    private final SessionCookie cookie;
    private final Discovery discovery;
    private final TokenClient tokens;
    private final Event<AtprotoLoggedOut> loggedOut;

    public LogoutHandler(OAuthConfig config, SessionStores stores, SessionCookie cookie, Discovery discovery,
            TokenClient tokens, Event<AtprotoLoggedOut> loggedOut) {
        this.config = config;
        this.stores = stores;
        this.cookie = cookie;
        this.discovery = discovery;
        this.tokens = tokens;
        this.loggedOut = loggedOut;
    }

    @Override
    public void handle(RoutingContext ctx) {
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
        return revoke(session).chain(v -> stores.sessions().delete(session.id())).chain(v -> Uni.createFrom().item(() -> {
            try {
                loggedOut.fire(new AtprotoLoggedOut(session.did(), Optional.empty()));
            } catch (RuntimeException e) {
                LOG.warnf("An AtprotoLoggedOut observer failed for %s: %s", session.did(), e.toString());
            }
            return (Void) null;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool()));
    }

    private Uni<Void> revoke(AtprotoSession session) {
        return discovery.authorizationServer(URI.create(session.issuer())).chain(as -> {
            if (as.revocationEndpoint().isEmpty()) {
                return Uni.createFrom().voidItem();
            }
            return tokens.revoke(as.issuer(), as.revocationEndpoint().get(), session.dpopKey(), session.refreshToken(),
                    "refresh_token");
        }).onFailure().recoverWithItem(e -> {
            LOG.debugf("Revoking the session of %s at %s failed (%s); deleting it anyway", session.did(),
                    session.issuer(), e.getClass().getSimpleName());
            return null;
        });
    }
}
