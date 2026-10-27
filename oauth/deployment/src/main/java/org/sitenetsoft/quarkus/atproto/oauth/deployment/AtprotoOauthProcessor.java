package org.sitenetsoft.quarkus.atproto.oauth.deployment;

import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.atproto.oauth.AtprotoSessions;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthBuildTimeConfig;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthRecorder;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthRecorder.Endpoint;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientIdentity;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientKeys;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientSetup;
import org.sitenetsoft.quarkus.atproto.oauth.discovery.Discovery;
import org.sitenetsoft.quarkus.atproto.oauth.flow.AuthServerClient;
import org.sitenetsoft.quarkus.atproto.oauth.flow.CallbackHandler;
import org.sitenetsoft.quarkus.atproto.oauth.flow.LoginHandler;
import org.sitenetsoft.quarkus.atproto.oauth.flow.LogoutHandler;
import org.sitenetsoft.quarkus.atproto.oauth.flow.NonceStore;
import org.sitenetsoft.quarkus.atproto.oauth.flow.ParClient;
import org.sitenetsoft.quarkus.atproto.oauth.flow.PostTokenChecks;
import org.sitenetsoft.quarkus.atproto.oauth.flow.SessionRevoker;
import org.sitenetsoft.quarkus.atproto.oauth.flow.TokenClient;
import org.sitenetsoft.quarkus.atproto.oauth.link.AccountLinks;
import org.sitenetsoft.quarkus.atproto.oauth.link.AtprotoAccountLinks;
import org.sitenetsoft.quarkus.atproto.oauth.link.InMemoryAtprotoAccountLinks;
import org.sitenetsoft.quarkus.atproto.oauth.link.ReactiveAtprotoAccountLinks;
import org.sitenetsoft.quarkus.atproto.oauth.link.UnlinkHandler;
import org.sitenetsoft.quarkus.atproto.oauth.security.AtprotoIdentityProvider;
import org.sitenetsoft.quarkus.atproto.oauth.security.AtprotoOAuthAuthenticationMechanism;
import org.sitenetsoft.quarkus.atproto.oauth.security.AtprotoRoleMapper;
import org.sitenetsoft.quarkus.atproto.oauth.security.AtprotoUser;
import org.sitenetsoft.quarkus.atproto.oauth.security.DefaultAtprotoRoleMapper;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.oauth.session.InMemoryAtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.oauth.session.InMemoryPendingAuthorizationStore;
import org.sitenetsoft.quarkus.atproto.oauth.session.PendingAuthorizationStore;
import org.sitenetsoft.quarkus.atproto.oauth.session.ReactiveAtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.oauth.session.Refresher;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionCookie;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionStores;
import org.sitenetsoft.quarkus.atproto.oauth.server.JwksHandler;
import org.sitenetsoft.quarkus.atproto.oauth.server.MetadataHandler;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.vertx.http.deployment.HttpRootPathBuildItem;
import io.quarkus.vertx.http.deployment.RouteBuildItem;

class AtprotoOauthProcessor {

    static final String FEATURE = "atproto-oauth";

    private static final Logger LOG = Logger.getLogger(AtprotoOauthProcessor.class);

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void beans(OAuthBuildTimeConfig config, BuildProducer<AdditionalBeanBuildItem> beans) {
        if (!config.enabled()) {
            return;
        }
        beans.produce(AdditionalBeanBuildItem.builder()
                .addBeanClasses(ClientSetup.class, Discovery.class, MetadataHandler.class, JwksHandler.class,
                        // login flow
                        NonceStore.class, AuthServerClient.class, ParClient.class, TokenClient.class,
                        PostTokenChecks.class, LoginHandler.class, CallbackHandler.class, LogoutHandler.class,
                        SessionRevoker.class,
                        // sessions
                        InMemoryAtprotoSessionStore.class, InMemoryPendingAuthorizationStore.class,
                        SessionStores.class, SessionCookie.class, Refresher.class, AtprotoSessions.class,
                        // link mode
                        InMemoryAtprotoAccountLinks.class, AccountLinks.class, UnlinkHandler.class,
                        // security
                        AtprotoOAuthAuthenticationMechanism.class, AtprotoIdentityProvider.class,
                        DefaultAtprotoRoleMapper.class, AtprotoUser.class)
                .setUnremovable()
                .build());
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(ClientIdentity.class, ClientKeys.class, Discovery.class,
                AtprotoSessionStore.class, ReactiveAtprotoSessionStore.class, PendingAuthorizationStore.class,
                AtprotoRoleMapper.class, AtprotoUser.class, Refresher.class, SessionStores.class,
                AtprotoSessions.class, AtprotoAccountLinks.class, ReactiveAtprotoAccountLinks.class,
                AccountLinks.class);
    }

    /**
     * The client metadata document, the JWKS and the login, callback, logout ({@code login} mode) and unlink
     * ({@code link} mode) endpoints. The mode is runtime configuration, so both are registered and each answers only
     * in its mode (the other passes the request on: 404 unless the application serves the path). The atproto
     * authentication mechanism is registered in both modes and contributes nothing in {@code link} mode. Their paths are runtime
     * configuration, so the routes are route functions resolved at runtime init; Quarkus places route functions under
     * {@code quarkus.http.root-path} (a {@code HttpRootPathBuildItem} builder needs the path at build time).
     */
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void routes(OAuthBuildTimeConfig config, HttpRootPathBuildItem rootPath, OAuthRecorder recorder,
            BuildProducer<RouteBuildItem> routes) {
        if (!config.enabled()) {
            LOG.debug("AT Protocol OAuth routes not registered: quarkus.atproto.oauth.enabled is false");
            return;
        }
        if (!"/".equals(rootPath.getRootPath())) {
            LOG.warnf("quarkus.http.root-path is '%s': AT Protocol OAuth serves its client metadata, JWKS and login "
                    + "routes under it, "
                    + "so include it in quarkus.atproto.oauth.client-base-url (the client_id is built from it)",
                    rootPath.getRootPath());
        }
        routes.produce(RouteBuildItem.builder().routeFunction(recorder.route(Endpoint.METADATA))
                .handler(recorder.handler(MetadataHandler.class.getName())).build());
        routes.produce(RouteBuildItem.builder().routeFunction(recorder.route(Endpoint.JWKS))
                .handler(recorder.handler(JwksHandler.class.getName())).build());
        routes.produce(RouteBuildItem.builder().routeFunction(recorder.route(Endpoint.LOGIN))
                .handler(recorder.handler(LoginHandler.class.getName())).build());
        routes.produce(RouteBuildItem.builder().routeFunction(recorder.route(Endpoint.CALLBACK))
                .handler(recorder.handler(CallbackHandler.class.getName())).build());
        routes.produce(RouteBuildItem.builder().routeFunction(recorder.route(Endpoint.LOGOUT))
                .handler(recorder.handler(LogoutHandler.class.getName())).build());
        routes.produce(RouteBuildItem.builder().routeFunction(recorder.route(Endpoint.UNLINK))
                .handler(recorder.handler(UnlinkHandler.class.getName())).build());
    }
}
