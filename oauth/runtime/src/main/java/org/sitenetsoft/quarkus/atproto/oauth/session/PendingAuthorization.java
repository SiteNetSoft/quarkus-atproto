package org.sitenetsoft.quarkus.atproto.oauth.session;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;

/**
 * A started login waiting for its callback, stored under its {@code state}. Holds secrets (the PKCE verifier and the
 * DPoP private key); {@link #toString()} leaves them out.
 *
 * @param issuer the authorization server the login was sent to; the callback's {@code iss} must equal it
 * @param tokenEndpoint its token endpoint
 * @param expectedDid the account the login started from (a handle or DID); empty when it started from a server
 * @param handle the handle the login started from, if any
 * @param codeVerifier the PKCE verifier
 * @param dpopPrivateJwk the DPoP key the PAR was made with (the session keeps it)
 * @param scope the scope requested
 * @param target where to send the browser after the login (a local path)
 * @param browserBinding base64url SHA-256 of the binding cookie the login set in the browser that started it; the
 *        callback must present that cookie (login CSRF protection, RFC 9700 §4.7.1)
 * @param created when the login started
 */
public record PendingAuthorization(String issuer, URI tokenEndpoint, Optional<Did> expectedDid,
        Optional<Handle> handle, String codeVerifier, Map<String, Object> dpopPrivateJwk, String scope, String target,
        String browserBinding, Instant created) {

    public PendingAuthorization {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
        expectedDid = expectedDid == null ? Optional.empty() : expectedDid;
        handle = handle == null ? Optional.empty() : handle;
        Objects.requireNonNull(codeVerifier, "codeVerifier");
        dpopPrivateJwk = Map.copyOf(Objects.requireNonNull(dpopPrivateJwk, "dpopPrivateJwk"));
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(browserBinding, "browserBinding");
        Objects.requireNonNull(created, "created");
    }

    @Override
    public String toString() {
        return "PendingAuthorization[issuer=" + issuer + ", expectedDid=" + expectedDid.map(Did::value).orElse("-")
                + ", scope=" + scope + ", target=" + target + ", created=" + created + "]";
    }
}
