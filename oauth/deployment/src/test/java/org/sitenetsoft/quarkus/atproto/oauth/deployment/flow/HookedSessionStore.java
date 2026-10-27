package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.oauth.session.InMemoryAtprotoSessionStore;

/**
 * An application-provided store (so the extension uses it off the event loop) that can simulate another node: when
 * {@link #otherNodeRefreshes} is set, the next compare-and-set first stores what that node wrote.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class HookedSessionStore implements AtprotoSessionStore {

    /** Called with the stored session just before the next {@code replaceIfRefreshToken}; its result is saved. */
    static volatile UnaryOperator<AtprotoSession> otherNodeRefreshes;

    private final InMemoryAtprotoSessionStore delegate = new InMemoryAtprotoSessionStore();

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
        UnaryOperator<AtprotoSession> other = otherNodeRefreshes;
        if (other != null) {
            otherNodeRefreshes = null;
            delegate.save(other.apply(delegate.find(updated.id()).orElseThrow()));
        }
        return delegate.replaceIfRefreshToken(updated, expectedRefreshToken);
    }

    @Override
    public void delete(String id) {
        delegate.delete(id);
    }
}
