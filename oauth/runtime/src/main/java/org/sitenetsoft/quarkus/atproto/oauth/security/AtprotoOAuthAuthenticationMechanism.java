package org.sitenetsoft.quarkus.atproto.oauth.security;

import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionCookie;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code login} mode: the session cookie → a {@code SecurityIdentity} (principal = DID). No cookie, a cookie that does
 * not open (tampered, other key) or a session that is gone or past its maximum age: no identity (anonymous), so public
 * pages keep working and protected ones answer 401. In {@code link} mode it neither authenticates nor challenges:
 * the application's own mechanism does both.
 */
@ApplicationScoped
public class AtprotoOAuthAuthenticationMechanism implements HttpAuthenticationMechanism {

    /**
     * Above the built-in mechanisms (basic auth is 2000, form 1000): in {@code login} mode a signed-out request must get
     * this mechanism's challenge (a plain 401), not a basic-auth prompt that could never succeed. In {@code link} mode
     * {@link #getChallenge} returns nothing, so the application's own mechanism still challenges.
     */
    public static final int PRIORITY = 3000;

    private final OAuthConfig config;
    private final SessionCookie cookie;

    public AtprotoOAuthAuthenticationMechanism(OAuthConfig config, SessionCookie cookie) {
        this.config = config;
        this.cookie = cookie;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        if (config.mode() != OAuthConfig.Mode.LOGIN) {
            return Uni.createFrom().nullItem();
        }
        Optional<String> sessionId = cookie.sessionId(context.request());
        if (sessionId.isEmpty()) {
            return Uni.createFrom().nullItem();
        }
        return identityProviderManager
                .authenticate(HttpSecurityUtils.setRoutingContextAttribute(
                        new AtprotoAuthenticationRequest(sessionId.get()), context))
                .onFailure(AuthenticationFailedException.class).recoverWithNull();
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        if (config.mode() != OAuthConfig.Mode.LOGIN) {
            return Uni.createFrom().nullItem(); // link mode: the application's own mechanism challenges
        }
        return Uni.createFrom().item(new ChallengeData(401, null, null));
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(AtprotoAuthenticationRequest.class);
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        return Uni.createFrom().item(new HttpCredentialTransport(HttpCredentialTransport.Type.COOKIE, cookie.name()));
    }
}
