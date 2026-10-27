package org.sitenetsoft.quarkus.atproto.oauth;

import java.util.function.Function;

import org.eclipse.microprofile.config.ConfigProvider;
import org.sitenetsoft.quarkus.atproto.oauth.server.BeanHandler;

import io.quarkus.runtime.annotations.Recorder;
import io.smallrye.config.SmallRyeConfig;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

@Recorder
public class OAuthRecorder {

    /** Which configured path a route serves. */
    public enum Endpoint {
        METADATA,
        JWKS
    }

    /** A route handler that looks up {@code beanClassName} lazily. */
    public Handler<RoutingContext> handler(String beanClassName) {
        return new BeanHandler(beanClassName);
    }

    /** A {@code GET} route at the path configured at runtime for {@code endpoint}. */
    public Function<Router, Route> route(Endpoint endpoint) {
        return router -> {
            OAuthConfig config = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class)
                    .getConfigMapping(OAuthConfig.class);
            String path = switch (endpoint) {
                case METADATA -> config.metadataPath();
                case JWKS -> config.jwksPath();
            };
            return router.route(HttpMethod.GET, path.startsWith("/") ? path : "/" + path);
        };
    }
}
