package org.sitenetsoft.quarkus.atproto.oauth.session;

import java.time.Duration;
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
     * {@code expectedRefreshToken} (compare-and-set). Atomic. This decides whose tokens are <b>kept</b>; it does not
     * stop two nodes from both <b>spending</b> the refresh token: that is {@link #tryAcquireRefreshLease}'s job.
     *
     * @return whether it was replaced; {@code false} also when the session is gone
     */
    boolean replaceIfRefreshToken(AtprotoSession updated, String expectedRefreshToken);

    void delete(String id);

    /**
     * Takes the lease to refresh session {@code sessionId} whose current refresh token hashes to
     * {@code refreshTokenHash} (base64url SHA-256; the token itself is never passed), for at most {@code ttl}.
     * Atomic across every node sharing the store: only the holder spends the refresh token, the others wait for its
     * result. A store shared by several nodes <b>must</b> implement it (a row lock or lease column, Redis
     * {@code SET key NX PX ttl}, ...): otherwise two nodes may spend the same single-use refresh token and the
     * authorization server revokes the whole grant (the user signs in again).
     * <p>
     * The default always grants the lease: right for a store used by one node (the extension already runs one
     * refresh per session at a time within a process).
     *
     * @return whether this node holds the lease now
     */
    default boolean tryAcquireRefreshLease(String sessionId, String refreshTokenHash, Duration ttl) {
        return true;
    }

    /** Gives up a lease taken with {@link #tryAcquireRefreshLease} (it also lapses after its ttl). Default: nothing. */
    default void releaseRefreshLease(String sessionId, String refreshTokenHash) {
    }
}
