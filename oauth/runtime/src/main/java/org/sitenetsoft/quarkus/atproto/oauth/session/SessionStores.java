package org.sitenetsoft.quarkus.atproto.oauth.session;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.sitenetsoft.atproto.syntax.Did;

import io.quarkus.arc.ClientProxy;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

/**
 * Both views (blocking and reactive) of the session store and the pending-authorization store, whichever form the
 * application provided. An application's {@link ReactiveAtprotoSessionStore} wins for reactive callers (the blocking
 * view awaits it); otherwise the blocking store runs on a worker thread, except the in-memory default, which never
 * blocks and runs inline. {@code @Startup} so the in-memory defaults log their production WARN at boot.
 */
@ApplicationScoped
@Startup
public class SessionStores {

    private static final Duration AWAIT = Duration.ofSeconds(30);

    @Inject
    InjectableInstance<AtprotoSessionStore> blockingStore;
    @Inject
    InjectableInstance<ReactiveAtprotoSessionStore> reactiveStore;
    @Inject
    InjectableInstance<PendingAuthorizationStore> pendingStore;

    private ReactiveAtprotoSessionStore sessions;
    private AtprotoSessionStore blockingSessions;
    private PendingAuthorizationStore pending;
    private boolean pendingInline;

    @PostConstruct
    void init() {
        boolean appBlocking = blockingStore.isResolvable() && !blockingStore.getHandle().getBean().isDefaultBean();
        if (reactiveStore.isResolvable()) {
            ReactiveAtprotoSessionStore r = reactiveStore.get();
            sessions = r;
            blockingSessions = appBlocking ? blockingStore.get() : new AwaitingStore(r);
        } else {
            AtprotoSessionStore b = appBlocking ? blockingStore.get() : ClientProxy.unwrap(blockingStore.get());
            blockingSessions = b;
            sessions = new OffloadingStore(b, !appBlocking);
        }
        pendingInline = pendingStore.getHandle().getBean().isDefaultBean();
        pending = pendingInline ? ClientProxy.unwrap(pendingStore.get()) : pendingStore.get();
    }

    /** The session store for reactive callers. */
    public ReactiveAtprotoSessionStore sessions() {
        return sessions;
    }

    /** The session store for blocking callers (never on the event loop). */
    public AtprotoSessionStore blockingSessions() {
        return blockingSessions;
    }

    /**
     * Stores a started login under its {@code state} and its browser binding: only the browser holding the binding
     * cookie can find (and so consume) it.
     */
    public Uni<Void> putPending(String state, PendingAuthorization entry, Duration ttl) {
        return run(pendingInline, () -> {
            pending.put(pendingKey(state, entry.browserBinding()), entry, ttl);
            return null;
        });
    }

    /** Takes the started login for {@code state} and the hash of the request's binding cookie. */
    public Uni<Optional<PendingAuthorization>> takePending(String state, String bindingHash) {
        return run(pendingInline, () -> pending.take(pendingKey(state, bindingHash)));
    }

    private static String pendingKey(String state, String bindingHash) {
        return state + "." + bindingHash;
    }

    static <T> Uni<T> run(boolean inline, Supplier<T> work) {
        Uni<T> uni = Uni.createFrom().item(work);
        return inline ? uni : uni.runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /** A blocking store seen reactively: inline for the in-memory default, on a worker thread otherwise. */
    private record OffloadingStore(AtprotoSessionStore d, boolean inline) implements ReactiveAtprotoSessionStore {
        @Override
        public Uni<Void> save(AtprotoSession session) {
            return run(inline, () -> {
                d.save(session);
                return null;
            });
        }

        @Override
        public Uni<Optional<AtprotoSession>> find(String id) {
            return run(inline, () -> d.find(id));
        }

        @Override
        public Uni<List<AtprotoSession>> findByDid(Did did) {
            return run(inline, () -> d.findByDid(did));
        }

        @Override
        public Uni<Boolean> replaceIfRefreshToken(AtprotoSession updated, String expectedRefreshToken) {
            return run(inline, () -> d.replaceIfRefreshToken(updated, expectedRefreshToken));
        }

        @Override
        public Uni<Void> delete(String id) {
            return run(inline, () -> {
                d.delete(id);
                return null;
            });
        }
    }

    /** A reactive store seen blocking. */
    private record AwaitingStore(ReactiveAtprotoSessionStore d) implements AtprotoSessionStore {
        @Override
        public void save(AtprotoSession session) {
            d.save(session).await().atMost(AWAIT);
        }

        @Override
        public Optional<AtprotoSession> find(String id) {
            return d.find(id).await().atMost(AWAIT);
        }

        @Override
        public List<AtprotoSession> findByDid(Did did) {
            return d.findByDid(did).await().atMost(AWAIT);
        }

        @Override
        public boolean replaceIfRefreshToken(AtprotoSession updated, String expectedRefreshToken) {
            return d.replaceIfRefreshToken(updated, expectedRefreshToken).await().atMost(AWAIT);
        }

        @Override
        public void delete(String id) {
            d.delete(id).await().atMost(AWAIT);
        }
    }
}
