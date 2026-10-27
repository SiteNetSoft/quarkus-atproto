package org.sitenetsoft.quarkus.atproto.oauth.flow;

import java.util.Arrays;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.quarkus.atproto.identity.AtprotoIdentity;
import org.sitenetsoft.quarkus.atproto.identity.ReactiveIdentityResolver;
import org.sitenetsoft.quarkus.atproto.oauth.discovery.Discovery;

import io.smallrye.mutiny.Uni;

/**
 * The mandatory checks after a token response (https://atproto.com/specs/oauth), on every login path:
 * <ol>
 * <li>{@code scope} is present and contains {@code atproto};</li>
 * <li>{@code sub} is a DID;</li>
 * <li>when the login started from an account (handle or DID), {@code sub} is that account;</li>
 * <li>always (also for logins started from a PDS or entryway host): {@code sub} → its DID document → its PDS → that
 * PDS's protected-resource metadata → its authorization server, whose issuer must be the one that issued the token.
 * The session's PDS is this one, never the host the login started from.</li>
 * </ol>
 * Any failure is a {@link LoginFailure} (401) naming the check.
 */
@ApplicationScoped
public class PostTokenChecks {

    private final ReactiveIdentityResolver identities;
    private final Discovery discovery;

    public PostTokenChecks(ReactiveIdentityResolver identities, Discovery discovery) {
        this.identities = identities;
        this.discovery = discovery;
    }

    /** The checked account (DID, handle, PDS). */
    public Uni<AtprotoIdentity> check(TokenResponse tokens, String issuer, Optional<Did> expectedDid) {
        return Uni.createFrom().deferred(() -> {
            Did sub = checkTokenClaims(tokens, expectedDid);
            return authority(sub, issuer).onFailure(e -> !(e instanceof IssuerMismatch)).transform(e -> refused(
                    "the authorization server of " + sub + "'s PDS could not be checked (" + e.getMessage() + ")", e))
                    .onFailure(IssuerMismatch.class).transform(e -> refused(e.getMessage(), null));
        });
    }

    /**
     * Check 4 on its own (it does not depend on a token response): {@code did} → DID document → PDS → its
     * authorization server, whose issuer must be {@code issuer}. The account, with its current PDS and handle.
     * Failures are passed through unchanged (identity, DID and discovery exceptions), except a different issuer:
     * {@link IssuerMismatch}.
     */
    public Uni<AtprotoIdentity> authority(Did did, String issuer) {
        return identities.resolveDid(did).chain(identity -> discovery.authorizationServerOf(identity.pds()).map(as -> {
            if (!as.issuer().equals(issuer)) {
                throw new IssuerMismatch("issuer mismatch: " + did + "'s PDS " + identity.pds() + " is protected by "
                        + as.issuer() + ", but the tokens are issued by " + issuer);
            }
            return identity;
        }));
    }

    /** The account's PDS is protected by another authorization server than the one that issues its tokens. */
    public static final class IssuerMismatch extends RuntimeException {
        private static final long serialVersionUID = 1L;

        IssuerMismatch(String message) {
            super(message);
        }
    }

    /** Checks 1 to 3; the DID. */
    static Did checkTokenClaims(TokenResponse tokens, Optional<Did> expectedDid) {
        String scope = tokens.scope().orElse("");
        if (Arrays.stream(scope.trim().split("\\s+")).noneMatch("atproto"::equals)) {
            throw refused("the granted scope does not contain atproto", null);
        }
        String sub = tokens.sub().orElse(null);
        if (sub == null || !Did.isValid(sub)) {
            throw refused("the token response's sub is " + (sub == null ? "missing" : "not a DID"), null);
        }
        Did did = Did.parse(sub);
        if (expectedDid.isPresent() && !expectedDid.get().equals(did)) {
            throw refused("sub mismatch: the token is for " + did + ", the login started for " + expectedDid.get(),
                    null);
        }
        return did;
    }

    private static LoginFailure refused(String reason, Throwable cause) {
        return new LoginFailure(401, "token_check_failed", "Login refused: " + reason, cause);
    }
}
