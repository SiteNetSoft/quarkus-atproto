package org.sitenetsoft.quarkus.atproto.oauth.session;

import java.util.List;
import java.util.Optional;

import org.sitenetsoft.atproto.syntax.Did;

/**
 * Where sessions live. The default keeps them in memory (lost on restart, one node only); provide an
 * {@code @ApplicationScoped} bean of this type (or of {@link ReactiveAtprotoSessionStore}) to persist them.
 * Implementations must encrypt the access and refresh tokens and the DPoP private key at rest. Blocking
 * implementations are called on worker threads.
 */
public interface AtprotoSessionStore {

    /** Inserts or replaces the session with this id. */
    void save(AtprotoSession session);

    Optional<AtprotoSession> find(String id);

    /** Every session of the account (it may be signed in from several browsers). */
    List<AtprotoSession> findByDid(Did did);

    /**
     * Replaces the stored session with {@code updated} only if the stored one still has refresh token
     * {@code expectedRefreshToken} (compare-and-set: of two nodes refreshing the same session, one wins). Atomic.
     *
     * @return whether it was replaced; {@code false} also when the session is gone
     */
    boolean replaceIfRefreshToken(AtprotoSession updated, String expectedRefreshToken);

    void delete(String id);
}
