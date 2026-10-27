package org.sitenetsoft.quarkus.atproto.identity;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.sitenetsoft.atproto.syntax.AtIdentifier;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.quarkus.did.DidDocument;
import org.sitenetsoft.quarkus.did.DidException;
import org.sitenetsoft.quarkus.did.ReactiveDidResolver;
import org.sitenetsoft.quarkus.did.Service;
import org.sitenetsoft.quarkus.did.VerificationMethod;
import org.sitenetsoft.quarkus.safefetch.FetchOptions;
import org.sitenetsoft.quarkus.safefetch.FetchResponse;
import org.sitenetsoft.quarkus.safefetch.ReactiveSafeFetchClient;
import org.sitenetsoft.quarkus.safefetch.SafeFetchConfig;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import io.smallrye.mutiny.subscription.UniEmitter;

/**
 * The resolver (reactive). Handle → DID races DNS and HTTPS; DID → identity resolves the document through
 * {@code quarkus-did}, extracts the PDS and signing key and runs the bidirectional handle check. Both directions are
 * cached for {@code quarkus.atproto.identity.cache-ttl}. {@link BlockingIdentityResolver} wraps it.
 */
@ApplicationScoped
public class IdentityResolverImpl implements ReactiveIdentityResolver {

    private static final Logger LOG = Logger.getLogger("org.sitenetsoft.quarkus.atproto.identity");
    static final String WELL_KNOWN = "/.well-known/atproto-did";
    private static final String PDS_SERVICE = "#atproto_pds";
    private static final String PDS_TYPE = "AtprotoPersonalDataServer";
    private static final String SIGNING_KEY = "#atproto";
    private static final String DNS_PREFIX = "did=";
    /** A DID is at most 2048 characters; leave room for whitespace. */
    private static final long MAX_WELL_KNOWN_SIZE = 4096;
    private static final int MAX_CACHE_ENTRIES = 10_000;

    private final TxtResolver dns;
    private final ReactiveDidResolver dids;
    private final ReactiveSafeFetchClient fetch;
    private final Map<String, URI> wellKnownOverride;
    private final boolean httpAllowed;
    private final Cache<Handle, Did> handles;
    private final Cache<Did, AtprotoIdentity> identities;

    @Inject
    public IdentityResolverImpl(TxtResolver dns, ReactiveDidResolver dids, ReactiveSafeFetchClient fetch,
            IdentityConfig config, SafeFetchConfig safeFetch) {
        this.dns = dns;
        this.dids = dids;
        this.fetch = fetch;
        this.wellKnownOverride = Map.copyOf(config.httpsWellKnownOverride());
        // mirrors quarkus-did's did:web rule: http endpoints only when safe-fetch is relaxed for tests
        this.httpAllowed = !safeFetch.httpsOnly();
        boolean caching = !config.cacheTtl().isZero();
        this.handles = caching ? Caffeine.newBuilder().maximumSize(MAX_CACHE_ENTRIES)
                .expireAfterWrite(config.cacheTtl()).build() : null;
        this.identities = caching ? Caffeine.newBuilder().maximumSize(MAX_CACHE_ENTRIES)
                .expireAfterWrite(config.cacheTtl()).build() : null;
        if (!wellKnownOverride.isEmpty()) {
            LOG.infof("Handle well-known overrides active (test/dev only): %s", wellKnownOverride.keySet());
        }
    }

    // ---- handle → DID ----

    @Override
    public Uni<Did> resolveHandle(Handle handle) {
        Objects.requireNonNull(handle, "handle");
        if (Handle.INVALID.equals(handle)) {
            return Uni.createFrom().failure(new HandleNotFound(handle.value(), "handle.invalid never resolves"));
        }
        Did cached = handles == null ? null : handles.getIfPresent(handle);
        if (cached != null) {
            return Uni.createFrom().item(cached);
        }
        Uni<Lookup> viaDns = dnsLookup(handle);
        Uni<Lookup> viaHttps = wellKnownLookup(handle);
        return Uni.createFrom().<Did> emitter(em -> new Race(handle, em).start(viaDns, viaHttps))
                .invoke(did -> {
                    if (handles != null) {
                        handles.put(handle, did);
                    }
                    LOG.debugf("Handle %s resolves to %s", handle, did);
                });
    }

    /** Result of one lookup method; lookups never fail, they report {@link Missing} instead. */
    private sealed interface Lookup permits Found, Missing, Conflict {
    }

    private record Found(Did did) implements Lookup {
    }

    private record Missing(String reason) implements Lookup {
    }

    private record Conflict(String reason) implements Lookup {
    }

    private Uni<Lookup> dnsLookup(Handle handle) {
        String name = "_atproto." + handle.value();
        return Uni.createFrom().deferred(() -> dns.txt(name))
                .map(records -> parseTxt(name, records))
                .onFailure().recoverWithItem(t -> new Missing("DNS " + name + ": " + t.getMessage()));
    }

    static Lookup parseTxt(String name, List<String> records) {
        Set<String> values = new LinkedHashSet<>();
        for (String r : records == null ? List.<String> of() : records) {
            if (r != null && r.startsWith(DNS_PREFIX)) {
                values.add(r.substring(DNS_PREFIX.length()).strip());
            }
        }
        if (values.isEmpty()) {
            return new Missing("DNS " + name + ": no did= TXT record");
        }
        if (values.size() > 1) {
            return new Conflict("DNS " + name + " names several DIDs: " + values);
        }
        String value = values.iterator().next();
        return Did.isValid(value) ? new Found(Did.parse(value))
                : new Missing("DNS " + name + ": not a DID: " + abbreviate(value));
    }

    private Uni<Lookup> wellKnownLookup(Handle handle) {
        URI uri = wellKnownUri(handle);
        FetchOptions options = FetchOptions.defaults().withMaxResponseSize(MAX_WELL_KNOWN_SIZE)
                .withHeader("Accept", "text/plain");
        return Uni.createFrom().deferred(() -> fetch.get(uri, options))
                .map(response -> parseWellKnown(uri, response))
                .onFailure().recoverWithItem(t -> new Missing("HTTPS " + uri + ": " + t.getMessage()));
    }

    private URI wellKnownUri(Handle handle) {
        URI base = wellKnownOverride.get(handle.value());
        if (base == null) {
            return URI.create("https://" + handle.value() + WELL_KNOWN);
        }
        String s = base.toString();
        return URI.create((s.endsWith("/") ? s.substring(0, s.length() - 1) : s) + WELL_KNOWN);
    }

    private static Lookup parseWellKnown(URI uri, FetchResponse response) {
        if (!response.isSuccess()) {
            return new Missing("HTTPS " + uri + ": status " + response.status());
        }
        String value = response.bodyAsString().strip();
        return Did.isValid(value) ? new Found(Did.parse(value))
                : new Missing("HTTPS " + uri + ": body is not a DID: " + abbreviate(value));
    }

    /**
     * Both lookups run at once. A DNS answer settles it immediately (DNS is preferred); an HTTPS answer waits for DNS
     * to report nothing. Conflicting DNS records fail the handle.
     */
    private static final class Race {
        private final Handle handle;
        private final UniEmitter<? super Did> emitter;
        private Lookup dns;
        private Lookup https;
        private boolean done;
        private Cancellable dnsSub;
        private Cancellable httpsSub;

        Race(Handle handle, UniEmitter<? super Did> emitter) {
            this.handle = handle;
            this.emitter = emitter;
        }

        void start(Uni<Lookup> viaDns, Uni<Lookup> viaHttps) {
            emitter.onTermination(this::cancelAll);
            Cancellable d = viaDns.subscribe().with(r -> settle(r, true));
            synchronized (this) {
                dnsSub = d;
                if (done) {
                    return; // DNS answered synchronously; no need to fetch the well-known
                }
            }
            Cancellable h = viaHttps.subscribe().with(r -> settle(r, false));
            boolean finished;
            synchronized (this) {
                httpsSub = h;
                finished = done;
            }
            if (finished) {
                cancelAll();
            }
        }

        private void cancelAll() {
            Cancellable d;
            Cancellable h;
            synchronized (this) {
                d = dnsSub;
                h = httpsSub;
            }
            if (d != null) {
                d.cancel();
            }
            if (h != null) {
                h.cancel();
            }
        }

        private void settle(Lookup result, boolean fromDns) {
            Did success = null;
            RuntimeException failure = null;
            synchronized (this) {
                if (done) {
                    return;
                }
                if (fromDns) {
                    dns = result;
                } else {
                    https = result;
                }
                if (dns instanceof Found f) {
                    success = f.did();
                    if (https instanceof Found other && !other.did().equals(f.did())) {
                        LOG.debugf("Handle %s: DNS says %s, HTTPS says %s; DNS wins", handle, f.did(), other.did());
                    }
                } else if (dns instanceof Conflict c) {
                    failure = new IdentityResolutionFailed(handle.value(), c.reason());
                } else if (dns != null && https != null) {
                    if (https instanceof Found f) {
                        success = f.did();
                    } else {
                        failure = new HandleNotFound(handle.value(), "Handle " + handle + " does not resolve ("
                                + ((Missing) dns).reason() + "; " + reason(https) + ")");
                    }
                } else {
                    return;
                }
                done = true;
            }
            if (success != null) {
                emitter.complete(success);
            } else {
                emitter.fail(failure);
            }
        }

        private static String reason(Lookup l) {
            return l instanceof Missing m ? m.reason() : l instanceof Conflict c ? c.reason() : String.valueOf(l);
        }
    }

    // ---- DID → identity ----

    @Override
    public Uni<AtprotoIdentity> resolve(AtIdentifier identifier) {
        Objects.requireNonNull(identifier, "identifier");
        return switch (identifier) {
            case Did did -> resolveDid(did);
            case Handle handle -> resolveHandle(handle).flatMap(this::resolveDid)
                    // the document must claim the handle we started from
                    .map(identity -> identity.handle().equals(handle) ? identity : identity.withHandle(Handle.INVALID));
        };
    }

    @Override
    public Uni<AtprotoIdentity> resolveDid(Did did) {
        Objects.requireNonNull(did, "did");
        if (!did.isBlessed()) {
            return Uni.createFrom().failure(new IdentityResolutionFailed(did.value(),
                    "AT Protocol accounts use did:plc or did:web, not did:" + did.method()));
        }
        AtprotoIdentity cached = identities == null ? null : identities.getIfPresent(did);
        if (cached != null) {
            return Uni.createFrom().item(cached);
        }
        return Uni.createFrom().deferred(() -> dids.resolve(did.value()))
                .onFailure(DidException.class).transform(t -> new IdentityResolutionFailed(did.value(),
                        "Resolving " + did + " failed: " + t.getMessage(), t))
                .flatMap(doc -> {
                    URI pds = pds(did, doc);
                    Optional<String> key = doc.verificationMethod(SIGNING_KEY)
                            .map(VerificationMethod::publicKeyMultibase);
                    return verifiedHandle(did, doc).map(handle -> new AtprotoIdentity(did, handle, pds, key));
                })
                .invoke(identity -> {
                    if (identities != null) {
                        identities.put(did, identity);
                    }
                    LOG.debugf("Resolved %s: handle %s, pds %s", did, identity.handle(), identity.pds());
                });
    }

    /** The document's first valid {@code at://} handle if it resolves back to {@code did}; else handle.invalid. */
    private Uni<Handle> verifiedHandle(Did did, DidDocument doc) {
        Optional<Handle> claimed = claimedHandle(doc);
        if (claimed.isEmpty()) {
            return Uni.createFrom().item(Handle.INVALID);
        }
        Handle handle = claimed.get();
        return resolveHandle(handle)
                .map(back -> {
                    if (back.equals(did)) {
                        return handle;
                    }
                    LOG.debugf("%s claims %s, which resolves to %s: handle invalid", did, handle, back);
                    return Handle.INVALID;
                })
                .onFailure(IdentityException.class).recoverWithItem(t -> {
                    LOG.debugf("%s claims %s, which does not resolve (%s): handle invalid", did, handle,
                            t.getMessage());
                    return Handle.INVALID;
                });
    }

    static Optional<Handle> claimedHandle(DidDocument doc) {
        for (String aka : doc.alsoKnownAs()) {
            if (aka != null && aka.startsWith("at://")) {
                String candidate = aka.substring("at://".length());
                if (Handle.isValid(candidate)) {
                    return Optional.of(Handle.parse(candidate));
                }
            }
        }
        return Optional.empty();
    }

    /** {@code #atproto_pds} of type AtprotoPersonalDataServer, reduced to scheme, host and port. */
    URI pds(Did did, DidDocument doc) {
        Service service = doc.service(PDS_SERVICE)
                .orElseThrow(() -> new NoPds(did.value(), did + " has no " + PDS_SERVICE + " service"));
        if (!PDS_TYPE.equals(service.type())) {
            throw new NoPds(did.value(), did + ": " + PDS_SERVICE + " has type " + service.type() + ", not " + PDS_TYPE);
        }
        URI endpoint = service.endpointUri()
                .orElseThrow(() -> new NoPds(did.value(), did + ": " + PDS_SERVICE + " endpoint is not a URL"));
        String scheme = endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
        boolean schemeOk = scheme.equals("https") || (httpAllowed && scheme.equals("http"));
        String path = endpoint.getRawPath();
        if (!schemeOk || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
                || (path != null && !path.isEmpty() && !path.equals("/"))
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new NoPds(did.value(), did + ": " + PDS_SERVICE + " endpoint must be https://host[:port] only, got "
                    + abbreviate(endpoint.toString()));
        }
        try {
            return new URI(scheme, null, endpoint.getHost(), endpoint.getPort(), null, null, null);
        } catch (URISyntaxException e) {
            throw new NoPds(did.value(), did + ": " + PDS_SERVICE + " endpoint is not a valid URL");
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }
}
