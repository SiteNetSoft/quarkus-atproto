package org.sitenetsoft.quarkus.atproto.oauth.link;

import java.util.List;
import java.util.Optional;

import org.sitenetsoft.atproto.syntax.Did;

/**
 * {@code link} mode: which AT Protocol accounts each application user linked, and the session of each link. The
 * default keeps links in memory (lost on restart, one node only); provide an {@code @ApplicationScoped} bean of this
 * type (or of {@link ReactiveAtprotoAccountLinks}) to persist them. A link outlives its session: when the session ends
 * (revoked, refused refresh) the link still names the account, and using it fails with
 * {@code AtprotoSessionExpired} until the user links the account again. Blocking implementations are called on
 * worker threads.
 */
public interface AtprotoAccountLinks {

    /** Links {@code did} to {@code appPrincipal} with session {@code sessionId}, replacing an earlier link's session. */
    void link(String appPrincipal, Did did, String sessionId);

    /** The accounts linked to {@code appPrincipal}, in the order they were first linked. */
    List<Did> findByPrincipal(String appPrincipal);

    /** The session id of the link between {@code appPrincipal} and {@code did}. */
    Optional<String> sessionFor(String appPrincipal, Did did);

    /** Removes the link (nothing when there is none). */
    void unlink(String appPrincipal, Did did);
}
