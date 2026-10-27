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
 * pages keep working and protected ones answer 401. In {@code link} mode it never authenticates.
 */
@ApplicationScoped
public class AtprotoOAuthAuthenticationMechanism implements HttpAuthenticationMechanism {

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
        return Uni.createFrom().item(new ChallengeData(401, null, null));
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
