package org.sitenetsoft.quarkus.atproto.oauth.link;

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
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

/**
 * Both views (blocking and reactive) of the account-link store, whichever form the application provided (same rules
 * as {@code SessionStores}: an application's reactive store wins; a blocking application store runs on a worker
 * thread; the in-memory default runs inline).
 */
@ApplicationScoped
public class AccountLinks {

    private static final Duration AWAIT = Duration.ofSeconds(30);

    @Inject
    InjectableInstance<AtprotoAccountLinks> blockingStore;
    @Inject
    InjectableInstance<ReactiveAtprotoAccountLinks> reactiveStore;

    private ReactiveAtprotoAccountLinks reactive;
    private AtprotoAccountLinks blocking;
    private boolean inline;

    @PostConstruct
    void init() {
        boolean appBlocking = blockingStore.isResolvable() && !blockingStore.getHandle().getBean().isDefaultBean();
        if (reactiveStore.isResolvable()) {
            ReactiveAtprotoAccountLinks r = reactiveStore.get();
            reactive = r;
            blocking = appBlocking ? blockingStore.get() : new Awaiting(r);
        } else {
            AtprotoAccountLinks b = appBlocking ? blockingStore.get() : ClientProxy.unwrap(blockingStore.get());
            blocking = b;
            reactive = new Offloading(b, !appBlocking);
            inline = !appBlocking;
        }
    }

    /** The link store for reactive callers. */
    public ReactiveAtprotoAccountLinks links() {
        return reactive;
    }

    /** Whether the link store is the in-memory default: its blocking view never blocks (fine on the event loop). */
    public boolean inline() {
        return inline;
    }

    /** The link store for blocking callers (never on the event loop, unless it is the in-memory default). */
    public AtprotoAccountLinks blockingLinks() {
        return blocking;
    }

    private static <T> Uni<T> run(boolean inline, Supplier<T> work) {
        Uni<T> uni = Uni.createFrom().item(work);
        return inline ? uni : uni.runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private record Offloading(AtprotoAccountLinks d, boolean inline) implements ReactiveAtprotoAccountLinks {
        @Override
        public Uni<Void> link(String appPrincipal, Did did, String sessionId) {
            return run(inline, () -> {
                d.link(appPrincipal, did, sessionId);
                return null;
            });
        }

        @Override
        public Uni<List<Did>> findByPrincipal(String appPrincipal) {
            return run(inline, () -> d.findByPrincipal(appPrincipal));
        }

        @Override
        public Uni<Optional<String>> sessionFor(String appPrincipal, Did did) {
            return run(inline, () -> d.sessionFor(appPrincipal, did));
        }

        @Override
        public Uni<Void> unlink(String appPrincipal, Did did) {
            return run(inline, () -> {
                d.unlink(appPrincipal, did);
                return null;
            });
        }
    }

    private record Awaiting(ReactiveAtprotoAccountLinks d) implements AtprotoAccountLinks {
        @Override
        public void link(String appPrincipal, Did did, String sessionId) {
            d.link(appPrincipal, did, sessionId).await().atMost(AWAIT);
        }

        @Override
        public List<Did> findByPrincipal(String appPrincipal) {
            return d.findByPrincipal(appPrincipal).await().atMost(AWAIT);
        }

        @Override
        public Optional<String> sessionFor(String appPrincipal, Did did) {
            return d.sessionFor(appPrincipal, did).await().atMost(AWAIT);
        }

        @Override
        public void unlink(String appPrincipal, Did did) {
            d.unlink(appPrincipal, did).await().atMost(AWAIT);
        }
    }
}
