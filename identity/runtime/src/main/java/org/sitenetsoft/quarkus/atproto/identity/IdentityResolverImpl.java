package org.sitenetsoft.quarkus.atproto.identity;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
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
import org.sitenetsoft.quarkus.did.DidConfig;
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

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import io.smallrye.mutiny.subscription.UniEmitter;

/**
 * The resolver (reactive). Handle → DID races DNS and HTTPS; DID → identity resolves the document through
 * {@code quarkus-did}, extracts the PDS and signing key and runs the bidirectional handle check. Both directions are
 * cached for {@code quarkus.atproto.identity.cache-ttl}. {@link BlockingIdentityResolver} wraps it.
 */
@ApplicationScoped
@Startup
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
            IdentityConfig config, SafeFetchConfig safeFetch, DidConfig didConfig) {
        cacheTtlWarning(didConfig.cacheTtl(), config.cacheTtl()).ifPresent(LOG::warn);
        this.dns = dns;
        this.dids = dids;
        this.fetch = fetch;
        this.wellKnownOverride = effectiveOverrides(config.httpsWellKnownOverride(), LaunchMode.current());
        // mirrors quarkus-did's did:web rule: http endpoints only when safe-fetch is relaxed for tests
        this.httpAllowed = !safeFetch.httpsOnly();
        boolean caching = !config.cacheTtl().isZero();
        this.handles = caching ? Caffeine.newBuilder().maximumSize(MAX_CACHE_ENTRIES)
                .expireAfterWrite(config.cacheTtl()).build() : null;
        this.identities = caching ? Caffeine.newBuilder().maximumSize(MAX_CACHE_ENTRIES)
                .expireAfterWrite(config.cacheTtl()).build() : null;
    }

    /** AT Protocol OAuth's ceiling on the age of identity facts (DID document, PDS, handle). */
    static final java.time.Duration MAX_IDENTITY_AGE = java.time.Duration.ofMinutes(10);

    /**
     * The identity cache holds identities built from documents the DID cache served, so an identity can be as old as
     * both TTLs together: a WARN when {@code quarkus.did.cache-ttl + quarkus.atproto.identity.cache-ttl} exceeds 10
     * minutes, else empty.
     */
    static Optional<String> cacheTtlWarning(java.time.Duration didTtl, java.time.Duration identityTtl) {
        java.time.Duration sum = didTtl.plus(identityTtl);
        if (sum.compareTo(MAX_IDENTITY_AGE) <= 0) {
            return Optional.empty();
        }
        return Optional.of("quarkus.did.cache-ttl (" + didTtl + ") + quarkus.atproto.identity.cache-ttl (" + identityTtl
                + ") = " + sum + ": resolved identities can be older than the 10 minutes AT Protocol OAuth allows; keep "
                + "the two together at 10 minutes or less");
    }

    /**
     * The well-known overrides to apply: none in production ({@link LaunchMode#NORMAL}), where the setting could
     * redirect a handle's ownership proof to any URL (one WARN says it was ignored); otherwise the configured map with
     * lowercase keys, matching {@link Handle#value()}.
     */
    static Map<String, URI> effectiveOverrides(Map<String, URI> configured, LaunchMode mode) {
        if (configured == null || configured.isEmpty()) {
            return Map.of();
        }
        if (mode == LaunchMode.NORMAL) {
            LOG.warnf("quarkus.atproto.identity.https-well-known-override is set but ignored in production "
                    + "(test/dev only): %s", configured.keySet());
            return Map.of();
        }
        Map<String, URI> lower = new HashMap<>();
        configured.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
        LOG.infof("Handle well-known overrides active (test/dev only): %s", lower.keySet());
        return Map.copyOf(lower);
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

    /**
     * Result of one lookup method; lookups never fail. {@link Missing} means the method cleanly found nothing (no
     * record, 404, a body that is not a DID); {@link Errored} means it could not tell (DNS error or timeout, transport
     * failure, HTTP 5xx or 429).
     */
    private sealed interface Lookup permits Found, Missing, Conflict, Errored {
    }

    private record Errored(String reason, Throwable cause) implements Lookup {
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
                .onFailure().recoverWithItem(t -> new Errored("DNS " + name + ": " + t.getMessage(), t));
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
                .onFailure().recoverWithItem(t -> new Errored("HTTPS " + uri + ": " + t.getMessage(), t));
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
        if (response.status() >= 500 || response.status() == 429) {
            return new Errored("HTTPS " + uri + ": status " + response.status(), null);
        }
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
                    } else if (dns instanceof Errored || https instanceof Errored) {
                        // could not tell: "try again", not "no such handle"
                        IdentityResolutionFailed e = new IdentityResolutionFailed(handle.value(), "Handle " + handle
                                + " could not be resolved (" + reason(dns) + "; " + reason(https) + ")",
                                dns instanceof Errored d ? d.cause() : ((Errored) https).cause());
                        if (dns instanceof Errored && https instanceof Errored h && h.cause() != null) {
                            e.addSuppressed(h.cause());
                        }
                        failure = e;
                    } else {
                        failure = new HandleNotFound(handle.value(), "Handle " + handle + " does not resolve ("
                                + reason(dns) + "; " + reason(https) + ")");
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
            return switch (l) {
                case Missing m -> m.reason();
                case Conflict c -> c.reason();
                case Errored e -> e.reason();
                case Found f -> "found " + f.did();
            };
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
        Optional<String> webProblem = unsupportedDidWeb(did);
        if (webProblem.isPresent()) {
            return Uni.createFrom().failure(new IdentityResolutionFailed(did.value(), webProblem.get()));
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
                    return verifiedHandle(did, doc).map(v -> {
                        AtprotoIdentity identity = new AtprotoIdentity(did, v.handle(), pds, key, !v.cacheable());
                        if (identities != null && v.cacheable()) {
                            identities.put(did, identity);
                        }
                        LOG.debugf("Resolved %s: handle %s, pds %s", did, identity.handle(), identity.pds());
                        return identity;
                    });
                });
    }

    /**
     * AT Protocol supports hostname-level {@code did:web} only: no path ({@code :} after the host) and no port
     * ({@code %3A}) except on {@code localhost} (development). Why {@code did} is refused, or empty.
     */
    static Optional<String> unsupportedDidWeb(Did did) {
        String prefix = "did:web:";
        if (!did.value().startsWith(prefix)) {
            return Optional.empty();
        }
        String id = did.value().substring(prefix.length());
        if (id.indexOf(':') >= 0) {
            return Optional.of("AT Protocol supports did:web at a hostname only, not with a path: " + abbreviate(id));
        }
        int port = id.toLowerCase(Locale.ROOT).indexOf("%3a");
        if (port >= 0 && !id.substring(0, port).equalsIgnoreCase("localhost")) {
            return Optional.of("AT Protocol supports did:web with a port on localhost only: " + abbreviate(id));
        }
        return Optional.empty();
    }

    /** Outcome of the reverse check; not cacheable when the handle could not be resolved for a transient reason. */
    private record Verified(Handle handle, boolean cacheable) {
    }

    /**
     * The document's first valid {@code at://} handle if it resolves back to {@code did}; else handle.invalid. A
     * handle that cleanly does not resolve, or resolves elsewhere, gives a cacheable handle.invalid; a resolution
     * error ({@link IdentityResolutionFailed}) gives handle.invalid for this call only, so the next call retries.
     */
    private Uni<Verified> verifiedHandle(Did did, DidDocument doc) {
        Optional<Handle> claimed = claimedHandle(doc);
        if (claimed.isEmpty()) {
            return Uni.createFrom().item(new Verified(Handle.INVALID, true));
        }
        Handle handle = claimed.get();
        return resolveHandle(handle)
                .map(back -> {
                    if (back.equals(did)) {
                        return new Verified(handle, true);
                    }
                    LOG.debugf("%s claims %s, which resolves to %s: handle invalid", did, handle, back);
                    return new Verified(Handle.INVALID, true);
                })
                .onFailure(HandleNotFound.class).recoverWithItem(t -> {
                    LOG.debugf("%s claims %s, which does not resolve (%s): handle invalid", did, handle,
                            t.getMessage());
                    return new Verified(Handle.INVALID, true);
                })
                .onFailure(IdentityResolutionFailed.class).recoverWithItem(t -> {
                    LOG.debugf("%s claims %s, which could not be checked now (%s): handle invalid, not cached", did,
                            handle, t.getMessage());
                    return new Verified(Handle.INVALID, false);
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
