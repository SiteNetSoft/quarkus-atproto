package org.sitenetsoft.quarkus.atproto.oauth.auth;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import org.sitenetsoft.quarkus.atproto.oauth.AtprotoSessionExpired;
import org.sitenetsoft.quarkus.atproto.oauth.Origins;
import org.sitenetsoft.quarkus.atproto.oauth.flow.NonceStore;
import org.sitenetsoft.quarkus.atproto.oauth.jose.DpopProofs;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.Refresher;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionStores;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcAuth;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcRequest;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.mutiny.Uni;

/**
 * DPoP authorization of XRPC requests to a session's PDS: {@code Authorization: DPoP <access token>} and a proof with
 * {@code htm}, {@code htu}, {@code iat}, {@code jti}, {@code ath} and the <b>PDS's own</b> nonce.
 * <ul>
 * <li>The session is read from the store on every request (a refresh elsewhere is picked up); an access token about
 * to expire is refreshed first (through {@link Refresher}: one refresh per session).</li>
 * <li>Every response's {@code DPoP-Nonce} is stored for the PDS ({@link #observe}), the retried one included.</li>
 * <li>{@code use_dpop_nonce} → retry once with the new nonce; {@code invalid_token} (expired) → refresh, retry
 * once.</li>
 * <li>The token is only ever sent to the session's PDS origin: a request elsewhere fails before sending.</li>
 * <li>The PDS is the session's <b>current</b> one, read with the session on each request ({@link #pds()}), so a
 * long-lived client follows an account that moved PDS (picked up by a refresh).</li>
 * </ul>
 */
public final class DpopXrpcAuth implements XrpcAuth {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String sessionId;
    private final SessionStores stores;
    private final Refresher refresher;
    private final NonceStore nonces;
    private volatile String lastSent;
    private volatile String rejected;
    /** The PDS the last request was signed for (where {@link #observe} files the nonce). */
    private volatile URI lastPds;

    public DpopXrpcAuth(String sessionId, SessionStores stores, Refresher refresher, NonceStore nonces) {
        this.sessionId = sessionId;
        this.stores = stores;
        this.refresher = refresher;
        this.nonces = nonces;
    }

    /**
     * The session's current PDS (refreshing first when the access token is about to expire, so a PDS move found by
     * the refresh is followed): where the next request goes.
     */
    public Uni<URI> pds() {
        return Uni.createFrom().deferred(this::current).map(AtprotoSession::pds);
    }

    /** The stored session, refreshed when needed. */
    private Uni<AtprotoSession> current() {
        return stores.sessions().find(sessionId)
                .map(s -> s.orElseThrow(() -> new AtprotoSessionExpired(
                        "The AT Protocol session is gone; sign in again")))
                .chain(s -> needsRefresh(s) ? refresher.refresh(sessionId, s.accessToken())
                        : Uni.createFrom().item(s));
    }

    @Override
    public Uni<XrpcRequest> authorize(XrpcRequest request) {
        return Uni.createFrom().deferred(this::current).map(s -> {
            String pdsOrigin = Origins.of(s.pds());
            if (!Origins.of(request.url()).equals(pdsOrigin)) {
                throw new IllegalArgumentException("The session's access token is only sent to its PDS " + pdsOrigin
                        + ", not to " + Origins.of(request.url()));
            }
            return sign(request, s);
        });
    }

    private boolean needsRefresh(AtprotoSession s) {
        return s.accessExpiresWithin(Instant.now(), Refresher.EXPIRY_SKEW_SECONDS)
                || s.accessToken().equals(rejected);
    }

    private XrpcRequest sign(XrpcRequest request, AtprotoSession s) {
        String token = s.accessToken();
        URI pds = s.pds();
        Optional<String> nonce = nonces.get(pds).or(() -> Optional.ofNullable(s.nonces().get(Origins.of(pds))));
        String proof = DpopProofs.proof(s.dpopKey(), request.method(), request.url(), nonce, Optional.of(token),
                Instant.now());
        lastSent = token;
        lastPds = pds;
        return request.withHeader("Authorization", "DPoP " + token).withHeader("DPoP", proof);
    }

    @Override
    public void observe(XrpcResponse response) {
        URI pds = lastPds;
        if (pds != null) {
            response.header("DPoP-Nonce").ifPresent(n -> nonces.put(pds, n));
        }
    }

    @Override
    public boolean shouldRetry(XrpcResponse response) {
        String www = response.header("WWW-Authenticate").orElse("");
        String error = error(response);
        if (www.contains("error=\"use_dpop_nonce\"") || "use_dpop_nonce".equals(error)) {
            return response.header("DPoP-Nonce").isPresent();
        }
        if (response.status() == 401 && (www.contains("error=\"invalid_token\"") || "invalid_token".equals(error)
                || "ExpiredToken".equals(error))) {
            rejected = lastSent; // the next authorize refreshes this token
            return true;
        }
        return false;
    }

    private static String error(XrpcResponse response) {
        try {
            JsonNode n = response.body().length == 0 ? null : JSON.readTree(response.body());
            return n != null && n.path("error").isTextual() ? n.get("error").asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        URI pds = lastPds;
        return "DpopXrpcAuth[pds=" + (pds == null ? "?" : Origins.of(pds)) + "]";
    }
}
