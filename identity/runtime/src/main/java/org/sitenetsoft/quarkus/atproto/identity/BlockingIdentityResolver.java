package org.sitenetsoft.quarkus.atproto.identity;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.sitenetsoft.atproto.syntax.AtIdentifier;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.quarkus.safefetch.SafeFetchConfig;

import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;

/**
 * Blocking facade over {@link IdentityResolverImpl}. Never call it on the event loop.
 */
@ApplicationScoped
public class BlockingIdentityResolver implements IdentityResolver {

    private final IdentityResolverImpl delegate;
    private final Duration limit;

    @Inject
    public BlockingIdentityResolver(IdentityResolverImpl delegate, SafeFetchConfig safeFetch) {
        this.delegate = delegate;
        // at most three fetches in sequence (handle well-known, DID document, reverse handle check) plus DNS slack
        this.limit = safeFetch.timeout().multipliedBy(3).plusSeconds(10);
    }

    @Override
    public Did resolveHandle(Handle handle) {
        return await(handle == null ? null : handle.value(), delegate.resolveHandle(handle));
    }

    @Override
    public AtprotoIdentity resolve(AtIdentifier identifier) {
        return await(identifier == null ? null : identifier.value(), delegate.resolve(identifier));
    }

    @Override
    public AtprotoIdentity resolveDid(Did did) {
        return await(did == null ? null : did.value(), delegate.resolveDid(did));
    }

    private <T> T await(String identifier, Uni<T> uni) {
        try {
            return uni.await().atMost(limit);
        } catch (TimeoutException e) {
            throw new IdentityResolutionFailed(identifier, "Resolving " + identifier + " timed out after " + limit, e);
        }
    }
}
