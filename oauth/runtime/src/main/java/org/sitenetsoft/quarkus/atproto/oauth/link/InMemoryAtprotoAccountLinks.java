package org.sitenetsoft.quarkus.atproto.oauth.link;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.quarkus.atproto.oauth.session.ProductionWarning;

import io.quarkus.arc.DefaultBean;

/** Default {@link AtprotoAccountLinks}: process memory, lost on restart, one node only. WARNs once in production. */
@DefaultBean
@ApplicationScoped
public class InMemoryAtprotoAccountLinks implements AtprotoAccountLinks {

    /** Per principal: DID → session id, in link order (each inner map is only touched inside {@code compute}). */
    private final Map<String, Map<Did, String>> links = new ConcurrentHashMap<>();

    @PostConstruct
    void warnIfProduction() {
        ProductionWarning.warnIfProduction("AtprotoAccountLinks");
    }

    @Override
    public void link(String appPrincipal, Did did, String sessionId) {
        links.compute(appPrincipal, (p, m) -> {
            Map<Did, String> copy = m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m);
            copy.put(did, sessionId);
            return copy;
        });
    }

    @Override
    public List<Did> findByPrincipal(String appPrincipal) {
        Map<Did, String> m = appPrincipal == null ? null : links.get(appPrincipal);
        return m == null ? List.of() : List.copyOf(m.keySet());
    }

    @Override
    public Optional<String> sessionFor(String appPrincipal, Did did) {
        Map<Did, String> m = appPrincipal == null ? null : links.get(appPrincipal);
        return m == null ? Optional.empty() : Optional.ofNullable(m.get(did));
    }

    @Override
    public void unlink(String appPrincipal, Did did) {
        links.computeIfPresent(appPrincipal, (p, m) -> {
            Map<Did, String> copy = new LinkedHashMap<>(m);
            copy.remove(did);
            return copy.isEmpty() ? null : copy;
        });
    }
}
