package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.quarkus.atproto.oauth.link.InMemoryAtprotoAccountLinks;
import org.sitenetsoft.quarkus.atproto.oauth.link.ReactiveAtprotoAccountLinks;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.InMemoryAtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.oauth.session.ReactiveAtprotoSessionStore;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

/**
 * Application-provided reactive stores (as a database client would be): every answer arrives on another thread, and
 * awaiting them on the event loop fails ("The current thread cannot be blocked").
 */
final class AsyncStores {

    private AsyncStores() {
    }

    static <T> Uni<T> later(Supplier<T> work) {
        return Uni.createFrom().item(work).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @ApplicationScoped
    public static class Links implements ReactiveAtprotoAccountLinks {
        final InMemoryAtprotoAccountLinks d = new InMemoryAtprotoAccountLinks();

        @Override
        public Uni<Void> link(String appPrincipal, Did did, String sessionId) {
            return later(() -> {
                d.link(appPrincipal, did, sessionId);
                return null;
            });
        }

        @Override
        public Uni<List<Did>> findByPrincipal(String appPrincipal) {
            return later(() -> d.findByPrincipal(appPrincipal));
        }

        @Override
        public Uni<Optional<String>> sessionFor(String appPrincipal, Did did) {
            return later(() -> d.sessionFor(appPrincipal, did));
        }

        @Override
        public Uni<Void> unlink(String appPrincipal, Did did) {
            return later(() -> {
                d.unlink(appPrincipal, did);
                return null;
            });
        }
    }

    @ApplicationScoped
    public static class Sessions implements ReactiveAtprotoSessionStore {
        final InMemoryAtprotoSessionStore d = new InMemoryAtprotoSessionStore();

        @Override
        public Uni<Void> save(AtprotoSession session) {
            return later(() -> {
                d.save(session);
                return null;
            });
        }

        @Override
        public Uni<Optional<AtprotoSession>> find(String id) {
            return later(() -> d.find(id));
        }

        @Override
        public Uni<List<AtprotoSession>> findByDid(Did did) {
            return later(() -> d.findByDid(did));
        }

        @Override
        public Uni<Boolean> replaceIfRefreshToken(AtprotoSession updated, String expectedRefreshToken) {
            return later(() -> d.replaceIfRefreshToken(updated, expectedRefreshToken));
        }

        @Override
        public Uni<Void> delete(String id) {
            return later(() -> {
                d.delete(id);
                return null;
            });
        }
    }
}
