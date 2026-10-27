package org.sitenetsoft.quarkus.atproto.oauth.session;

import java.time.Duration;
import java.util.Optional;

/**
 * Started logins by {@code state}, between the login endpoint and the callback. The default keeps them in memory
 * (one node only: with several nodes behind a load balancer, provide a shared implementation). Implementations must
 * encrypt the entries at rest and be callable from worker threads.
 */
public interface PendingAuthorizationStore {

    void put(String state, PendingAuthorization entry, Duration ttl);

    /** Removes and returns the entry; a second {@code take} of the same state, or an expired entry, is empty. */
    Optional<PendingAuthorization> take(String state);
}
