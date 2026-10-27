package org.sitenetsoft.quarkus.atproto.oauth.deployment;

import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthBuildTimeConfig;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthRecorder;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthRecorder.Endpoint;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientIdentity;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientKeys;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientSetup;
import org.sitenetsoft.quarkus.atproto.oauth.discovery.Discovery;
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
                .addBeanClasses(ClientSetup.class, Discovery.class, MetadataHandler.class, JwksHandler.class)
                .setUnremovable()
                .build());
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(ClientIdentity.class, ClientKeys.class, Discovery.class);
    }

    /**
     * The client metadata document and the JWKS. Their paths ({@code metadata-path}, {@code jwks-path}) are runtime
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
            LOG.warnf("quarkus.http.root-path is '%s': AT Protocol OAuth serves its client metadata and JWKS under it, "
                    + "so include it in quarkus.atproto.oauth.client-base-url (the client_id is built from it)",
                    rootPath.getRootPath());
        }
        routes.produce(RouteBuildItem.builder().routeFunction(recorder.route(Endpoint.METADATA))
                .handler(recorder.handler(MetadataHandler.class.getName())).build());
        routes.produce(RouteBuildItem.builder().routeFunction(recorder.route(Endpoint.JWKS))
                .handler(recorder.handler(JwksHandler.class.getName())).build());
    }
}
