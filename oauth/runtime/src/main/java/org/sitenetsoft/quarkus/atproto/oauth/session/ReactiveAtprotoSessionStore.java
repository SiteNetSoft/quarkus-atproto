package org.sitenetsoft.quarkus.atproto.oauth.session;

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
}
