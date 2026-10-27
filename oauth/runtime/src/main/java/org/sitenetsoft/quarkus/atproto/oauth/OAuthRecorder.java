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
        JWKS,
        LOGIN,
        CALLBACK,
        LOGOUT,
        UNLINK
    }

    /** A route handler that looks up {@code beanClassName} lazily. */
    public Handler<RoutingContext> handler(String beanClassName) {
        return new BeanHandler(beanClassName);
    }

    /**
     * A route at the path configured at runtime for {@code endpoint}: {@code GET} (metadata, JWKS, callback),
     * {@code GET} and {@code POST} (login: the handler answers {@code GET} with 405), {@code POST} (logout, unlink).
     */
    public Function<Router, Route> route(Endpoint endpoint) {
        return router -> {
            OAuthConfig config = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class)
                    .getConfigMapping(OAuthConfig.class);
            String path = switch (endpoint) {
                case METADATA -> config.metadataPath();
                case JWKS -> config.jwksPath();
                case LOGIN -> config.loginPath();
                case CALLBACK -> config.callbackPath();
                case LOGOUT -> config.logoutPath();
                case UNLINK -> config.unlinkPath();
            };
            String p = path.startsWith("/") ? path : "/" + path;
            return switch (endpoint) {
                // the login form's body (small; no uploads), read before the login handler runs
                case LOGIN -> router.route(p).method(HttpMethod.GET).method(HttpMethod.POST)
                        .handler(io.vertx.ext.web.handler.BodyHandler.create().setBodyLimit(8 * 1024)
                                .setHandleFileUploads(false));
                case LOGOUT -> router.route(HttpMethod.POST, p);
                // the unlink form's body (did), small
                case UNLINK -> router.route(HttpMethod.POST, p).handler(io.vertx.ext.web.handler.BodyHandler.create()
                        .setBodyLimit(8 * 1024).setHandleFileUploads(false));
                default -> router.route(HttpMethod.GET, p);
            };
        };
    }
}
