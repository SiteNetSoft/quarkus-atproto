package org.sitenetsoft.quarkus.atproto.oauth.link;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.event.Event;
import jakarta.inject.Singleton;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.SyntaxException;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;
import org.sitenetsoft.quarkus.atproto.oauth.events.AtprotoLoggedOut;
import org.sitenetsoft.quarkus.atproto.oauth.flow.AppIdentity;
import org.sitenetsoft.quarkus.atproto.oauth.flow.EventFiring;
import org.sitenetsoft.quarkus.atproto.oauth.flow.LoginFailure;
import org.sitenetsoft.quarkus.atproto.oauth.flow.Responses;
import org.sitenetsoft.quarkus.atproto.oauth.flow.SessionRevoker;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionStores;

import io.quarkus.security.identity.IdentityProviderManager;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code POST {unlink-path}[?did=<DID>]} ({@code link} mode): the signed-in application user unlinks an AT Protocol
 * account: its session's refresh token is revoked at the authorization server (best effort), the session deleted, the
 * link removed, {@link AtprotoLoggedOut} fired with the application principal; 204. {@code did} (query or form) is
 * needed only when several accounts are linked (400 otherwise). 401 without an application identity, 404 when the
 * account is not linked to this user. In {@code login} mode it is not this extension's endpoint.
 */
@Singleton
public class UnlinkHandler implements Handler<RoutingContext> {

    private final OAuthConfig config;
    private final AccountLinks links;
    private final SessionStores stores;
    private final SessionRevoker revoker;
    private final IdentityProviderManager identities;
    private final Event<AtprotoLoggedOut> loggedOut;

    public UnlinkHandler(OAuthConfig config, AccountLinks links, SessionStores stores, SessionRevoker revoker,
            IdentityProviderManager identities, Event<AtprotoLoggedOut> loggedOut) {
        this.config = config;
        this.links = links;
        this.stores = stores;
        this.revoker = revoker;
        this.identities = identities;
        this.loggedOut = loggedOut;
    }

    @Override
    public void handle(RoutingContext ctx) {
        if (config.mode() != OAuthConfig.Mode.LINK) {
            ctx.next();
            return;
        }
        Context context = ctx.vertx().getOrCreateContext();
        Uni<java.util.Map<String, String>> params = Responses.params(ctx); // read before any hop
        AppIdentity.principal(ctx, identities).chain(principal -> {
            if (principal.isEmpty()) {
                return Uni.createFrom().failure(new LoginFailure(401, "unauthenticated",
                        "Sign in to the application to unlink an account"));
            }
            return params.chain(p -> unlink(principal.get(), Optional.ofNullable(p.get("did"))
                    .map(String::strip).filter(d -> !d.isEmpty())));
        }).subscribe().with(v -> Responses.on(context, () -> {
            if (!ctx.response().ended()) {
                ctx.response().setStatusCode(204).putHeader("Cache-Control", "no-store").end();
            }
        }), error -> Responses.on(context, () -> Responses.fail(ctx, config, "unlink", error)));
    }

    private Uni<Void> unlink(String principal, Optional<String> didParam) {
        return links.links().findByPrincipal(principal).chain(linked -> {
            Did did = pick(linked, didParam);
            return links.links().sessionFor(principal, did)
                    .chain(id -> id.map(i -> stores.sessions().find(i)).orElse(Uni.createFrom().item(Optional.empty())))
                    .chain(session -> session.map(revoker::end).orElse(Uni.createFrom().voidItem()))
                    .chain(v -> links.links().unlink(principal, did))
                    .chain(v -> EventFiring.fire(loggedOut, new AtprotoLoggedOut(did, Optional.of(principal))));
        });
    }

    private static Did pick(List<Did> linked, Optional<String> didParam) {
        if (didParam.isPresent()) {
            Did did;
            try {
                did = Did.parse(didParam.get());
            } catch (SyntaxException e) {
                throw new LoginFailure(400, "invalid_request", "did is not a DID");
            }
            if (!linked.contains(did)) {
                throw new LoginFailure(404, "not_linked", "This account is not linked to you");
            }
            return did;
        }
        if (linked.isEmpty()) {
            throw new LoginFailure(404, "not_linked", "No AT Protocol account is linked to you");
        }
        if (linked.size() > 1) {
            throw new LoginFailure(400, "invalid_request", "Several accounts are linked: say which one with did");
        }
        return linked.get(0);
    }
}
