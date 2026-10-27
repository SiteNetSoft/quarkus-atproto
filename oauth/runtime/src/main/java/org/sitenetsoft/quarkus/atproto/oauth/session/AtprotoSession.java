package org.sitenetsoft.quarkus.atproto.oauth.session;

import java.net.URI;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.multikey.Jwk;

/**
 * A signed-in AT Protocol account. Holds secrets (tokens, the DPoP private key): stores must encrypt them at rest and
 * nothing may log them; {@link #toString()} leaves them out.
 *
 * @param id the session id (random; the session cookie carries it encrypted)
 * @param did the account, from the token response's {@code sub} (checked)
 * @param handle the handle the DID document claims, verified both ways ({@link Handle#INVALID} otherwise)
 * @param pds the account's PDS, from {@code sub}'s DID document (never an entryway)
 * @param issuer the authorization server that issued the tokens
 * @param grantedScope the scope the token response granted
 * @param accessToken the DPoP-bound access token
 * @param refreshToken the single-use refresh token
 * @param accessExpiry when the access token expires
 * @param dpopPrivateJwk the session's P-256 DPoP key as a private JWK ({@code kty}, {@code crv}, {@code x},
 *        {@code y}, {@code d})
 * @param nonces the last DPoP nonce per server origin (authorization server and PDS)
 * @param created when the user signed in (the session's maximum age counts from here)
 */
public record AtprotoSession(String id, Did did, Handle handle, URI pds, String issuer, String grantedScope,
        String accessToken, String refreshToken, Instant accessExpiry, Map<String, Object> dpopPrivateJwk,
        Map<String, String> nonces, Instant created) {

    public AtprotoSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(did, "did");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(pds, "pds");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(grantedScope, "grantedScope");
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(refreshToken, "refreshToken");
        Objects.requireNonNull(accessExpiry, "accessExpiry");
        dpopPrivateJwk = Map.copyOf(Objects.requireNonNull(dpopPrivateJwk, "dpopPrivateJwk"));
        nonces = nonces == null ? Map.of() : Map.copyOf(nonces);
        Objects.requireNonNull(created, "created");
    }

    /** The session's DPoP key pair. */
    public KeyPair dpopKey() {
        return new KeyPair(Jwk.publicKey(dpopPrivateJwk), Jwk.privateKey(dpopPrivateJwk));
    }

    /** This session after a refresh. */
    public AtprotoSession withTokens(String newAccessToken, String newRefreshToken, Instant newExpiry,
            String newScope, Map<String, String> newNonces) {
        return new AtprotoSession(id, did, handle, pds, issuer, newScope, newAccessToken, newRefreshToken, newExpiry,
                dpopPrivateJwk, newNonces, created);
    }

    /** Whether the access token is expired or expires within {@code skewSeconds}. */
    public boolean accessExpiresWithin(Instant now, long skewSeconds) {
        return !accessExpiry.isAfter(now.plusSeconds(skewSeconds));
    }

    @Override
    public String toString() {
        return "AtprotoSession[did=" + did + ", handle=" + handle + ", pds=" + pds + ", issuer=" + issuer + ", scope="
                + grantedScope + ", accessExpiry=" + accessExpiry + ", created=" + created + "]";
    }
}
