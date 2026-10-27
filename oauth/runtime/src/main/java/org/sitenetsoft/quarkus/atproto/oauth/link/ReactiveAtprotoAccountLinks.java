package org.sitenetsoft.quarkus.atproto.oauth.link;

import java.util.List;
import java.util.Optional;

import org.sitenetsoft.atproto.syntax.Did;

import io.smallrye.mutiny.Uni;

/** Non-blocking form of {@link AtprotoAccountLinks} (same contract); when provided it is preferred. */
public interface ReactiveAtprotoAccountLinks {

    Uni<Void> link(String appPrincipal, Did did, String sessionId);

    Uni<List<Did>> findByPrincipal(String appPrincipal);

    Uni<Optional<String>> sessionFor(String appPrincipal, Did did);

    Uni<Void> unlink(String appPrincipal, Did did);
}
