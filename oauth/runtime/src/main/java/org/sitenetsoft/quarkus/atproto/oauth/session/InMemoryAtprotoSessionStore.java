package org.sitenetsoft.quarkus.atproto.oauth.session;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.atproto.syntax.Did;

import io.quarkus.arc.DefaultBean;

/** Default {@link AtprotoSessionStore}: process memory, lost on restart, one node only. WARNs once in production. */
@DefaultBean
@ApplicationScoped
public class InMemoryAtprotoSessionStore implements AtprotoSessionStore {

    private final Map<String, AtprotoSession> sessions = new ConcurrentHashMap<>();

    @PostConstruct
    void warnIfProduction() {
        ProductionWarning.warnIfProduction("AtprotoSessionStore");
    }

    @Override
    public void save(AtprotoSession session) {
        sessions.put(session.id(), session);
    }

    @Override
    public Optional<AtprotoSession> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(sessions.get(id));
    }

    @Override
    public List<AtprotoSession> findByDid(Did did) {
        return sessions.values().stream().filter(s -> s.did().equals(did)).toList();
    }

    @Override
    public boolean replaceIfRefreshToken(AtprotoSession updated, String expectedRefreshToken) {
        boolean[] replaced = {false};
        sessions.computeIfPresent(updated.id(), (id, current) -> {
            if (current.refreshToken().equals(expectedRefreshToken)) {
                replaced[0] = true;
                return updated;
            }
            return current;
        });
        return replaced[0];
    }

    @Override
    public void delete(String id) {
        sessions.remove(id);
    }
}
