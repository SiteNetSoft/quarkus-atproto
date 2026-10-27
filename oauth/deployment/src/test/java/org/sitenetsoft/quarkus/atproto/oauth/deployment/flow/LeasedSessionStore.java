package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.oauth.session.InMemoryAtprotoSessionStore;

/**
 * A store shared with "another node": while {@link #heldElsewhere} is set, that node holds every refresh lease. Records
 * the lease keys taken and given back.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class LeasedSessionStore implements AtprotoSessionStore {

    static volatile boolean heldElsewhere;
    static final List<String> acquired = new CopyOnWriteArrayList<>();
    static final List<String> released = new CopyOnWriteArrayList<>();

    private final InMemoryAtprotoSessionStore delegate = new InMemoryAtprotoSessionStore();

    static void reset() {
        heldElsewhere = false;
        acquired.clear();
        released.clear();
    }

    @Override
    public boolean tryAcquireRefreshLease(String sessionId, String refreshTokenHash, Duration ttl) {
        if (heldElsewhere) {
            return false;
        }
        acquired.add(refreshTokenHash);
        return true;
    }

    @Override
    public void releaseRefreshLease(String sessionId, String refreshTokenHash) {
        released.add(refreshTokenHash);
    }

    @Override
    public void save(AtprotoSession session) {
        delegate.save(session);
    }

    @Override
    public Optional<AtprotoSession> find(String id) {
        return delegate.find(id);
    }

    @Override
    public List<AtprotoSession> findByDid(Did did) {
        return delegate.findByDid(did);
    }

    @Override
    public boolean replaceIfRefreshToken(AtprotoSession updated, String expectedRefreshToken) {
        return delegate.replaceIfRefreshToken(updated, expectedRefreshToken);
    }

    @Override
    public void delete(String id) {
        delegate.delete(id);
    }
}
