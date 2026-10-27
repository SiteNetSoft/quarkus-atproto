package org.sitenetsoft.quarkus.atproto.oauth.server;

import io.quarkus.arc.Arc;
import io.quarkus.runtime.annotations.RecordableConstructor;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * Looks up a {@link Handler} bean lazily on first request, so recorders never touch the container at static init.
 */
public final class BeanHandler implements Handler<RoutingContext> {

    private final String beanClassName;
    private volatile Handler<RoutingContext> delegate;

    @RecordableConstructor
    public BeanHandler(String beanClassName) {
        this.beanClassName = beanClassName;
    }

    public String getBeanClassName() {
        return beanClassName;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void handle(RoutingContext ctx) {
        Handler<RoutingContext> h = delegate;
        if (h == null) {
            try {
                Class<?> type = Thread.currentThread().getContextClassLoader().loadClass(beanClassName);
                h = (Handler<RoutingContext>) Arc.container().instance(type).get();
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            delegate = h;
        }
        h.handle(ctx);
    }
}
