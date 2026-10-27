package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.sitenetsoft.quarkus.atproto.oauth.events.AtprotoLoggedIn;
import org.sitenetsoft.quarkus.atproto.oauth.events.AtprotoLoggedOut;
import org.sitenetsoft.quarkus.atproto.oauth.events.AtprotoSessionRevoked;

/** Records the extension's CDI events, in order. */
@ApplicationScoped
public class EventLog {

    static final List<Object> EVENTS = new CopyOnWriteArrayList<>();

    void in(@Observes AtprotoLoggedIn e) {
        EVENTS.add(e);
    }

    void out(@Observes AtprotoLoggedOut e) {
        EVENTS.add(e);
    }

    void revoked(@Observes AtprotoSessionRevoked e) {
        EVENTS.add(e);
    }

    static <T> List<T> of(Class<T> type) {
        return EVENTS.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
