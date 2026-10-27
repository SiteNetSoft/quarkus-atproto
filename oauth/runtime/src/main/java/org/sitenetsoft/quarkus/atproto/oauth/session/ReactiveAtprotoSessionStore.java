package org.sitenetsoft.quarkus.atproto.oauth.session;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.sitenetsoft.atproto.syntax.Did;

import io.smallrye.mutiny.Uni;

/** Non-blocking form of {@link AtprotoSessionStore} (same contract); when provided it is preferred. */
public interface ReactiveAtprotoSessionStore {

    Uni<Void> save(AtprotoSession session);

    Uni<Optional<AtprotoSession>> find(String id);

    Uni<List<AtprotoSession>> findByDid(Did did);

    Uni<Boolean> replaceIfRefreshToken(AtprotoSession updated, String expectedRefreshToken);

    Uni<Void> delete(String id);

    /** {@link AtprotoSessionStore#tryAcquireRefreshLease}; a store shared by several nodes must implement it. */
    default Uni<Boolean> tryAcquireRefreshLease(String sessionId, String refreshTokenHash, Duration ttl) {
        return Uni.createFrom().item(Boolean.TRUE);
    }

    /** {@link AtprotoSessionStore#releaseRefreshLease}. */
    default Uni<Void> releaseRefreshLease(String sessionId, String refreshTokenHash) {
        return Uni.createFrom().voidItem();
    }
}
