package org.sitenetsoft.quarkus.atproto.oauth.flow;

import java.util.Optional;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code link} mode: the application user signed in on this request through the application's own mechanism (form,
 * OIDC, basic, ...), proactive or lazy authentication alike.
 */
public final class AppIdentity {

    private AppIdentity() {
    }

    /** The principal name of the request's non-anonymous identity; empty when nobody is signed in. */
    public static Uni<Optional<String>> principal(RoutingContext ctx, IdentityProviderManager identityProviderManager) {
        Uni<SecurityIdentity> identity = QuarkusHttpUser.getSecurityIdentity(ctx, identityProviderManager);
        if (identity == null) {
            return Uni.createFrom().item(Optional.empty());
        }
        return identity.map(i -> i == null || i.isAnonymous() || i.getPrincipal() == null
                ? Optional.<String> empty() : Optional.ofNullable(i.getPrincipal().getName()))
                .onFailure().recoverWithItem(Optional.empty());
    }

    /** The signed-in application user, or a 401 {@link LoginFailure}. */
    static Uni<String> required(RoutingContext ctx, IdentityProviderManager identityProviderManager, String what) {
        return principal(ctx, identityProviderManager).map(p -> p.orElseThrow(() -> new LoginFailure(401,
                "unauthenticated", "Sign in to the application before you " + what)));
    }
}
