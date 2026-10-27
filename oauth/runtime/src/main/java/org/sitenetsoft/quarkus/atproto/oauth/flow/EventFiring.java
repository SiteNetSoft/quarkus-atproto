package org.sitenetsoft.quarkus.atproto.oauth.flow;

import jakarta.enterprise.event.Event;

import org.jboss.logging.Logger;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

/** Fires the extension's CDI events: observers may block, so on a worker thread; an observer's failure is logged. */
public final class EventFiring {

    private static final Logger LOG = Logger.getLogger("org.sitenetsoft.quarkus.atproto.oauth.events");

    private EventFiring() {
    }

    public static <T> Uni<Void> fire(Event<T> event, T payload) {
        return Uni.createFrom().item(() -> {
            try {
                event.fire(payload);
            } catch (RuntimeException e) {
                LOG.warnf("An observer of %s failed: %s", payload, e.toString());
            }
            return (Void) null;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
