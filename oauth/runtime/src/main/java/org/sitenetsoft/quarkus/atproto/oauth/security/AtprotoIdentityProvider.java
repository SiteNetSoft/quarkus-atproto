package org.sitenetsoft.quarkus.atproto.oauth.security;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionStores;

import io.quarkus.arc.All;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;

/**
 * Session id → {@code SecurityIdentity}: principal name = the DID; attributes {@value #HANDLE}, {@value #PDS},
 * {@value #SCOPE}, {@value #SESSION_ID}; roles from every {@link AtprotoRoleMapper} (united). Runs on a worker
 * thread (stores and role mappers may block). A session past {@code session.max-age} is deleted and gives no identity.
 */
@ApplicationScoped
public class AtprotoIdentityProvider implements IdentityProvider<AtprotoAuthenticationRequest> {

    public static final String HANDLE = "atproto.handle";
    public static final String PDS = "atproto.pds";
    public static final String SCOPE = "atproto.scope";
    public static final String SESSION_ID = "atproto.session-id";

    private final SessionStores stores;
    private final OAuthConfig config;
    private final List<AtprotoRoleMapper> mappers;

    public AtprotoIdentityProvider(SessionStores stores, OAuthConfig config, @All List<AtprotoRoleMapper> mappers) {
        this.stores = stores;
        this.config = config;
        this.mappers = mappers;
    }

    @Override
    public Class<AtprotoAuthenticationRequest> getRequestType() {
        return AtprotoAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(AtprotoAuthenticationRequest request,
            AuthenticationRequestContext context) {
        return context.runBlocking(() -> {
            Optional<AtprotoSession> found = stores.blockingSessions().find(request.sessionId());
            if (found.isEmpty()) {
                return null;
            }
            AtprotoSession s = found.get();
            if (s.created().plus(config.session().maxAge()).isBefore(Instant.now())) {
                stores.blockingSessions().delete(s.id());
                return null;
            }
            Set<String> roles = new LinkedHashSet<>();
            for (AtprotoRoleMapper m : mappers) {
                Set<String> r = m.roles(s.did(), s.handle());
                if (r != null) {
                    roles.addAll(r);
                }
            }
            return QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(s.did().value()))
                    .addRoles(roles).addAttribute(HANDLE, s.handle().value()).addAttribute(PDS, s.pds().toString())
                    .addAttribute(SCOPE, s.grantedScope()).addAttribute(SESSION_ID, s.id()).build();
        });
    }
}
