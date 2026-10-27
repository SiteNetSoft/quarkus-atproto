package org.sitenetsoft.quarkus.atproto.oauth;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.atproto.oauth")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface OAuthConfig {

    /** Property names used in error messages. */
    String CLIENT_BASE_URL = "quarkus.atproto.oauth.client-base-url";
    String SIGNING_KEY_LOCATION = "quarkus.atproto.oauth.signing-key.location";

    /**
     * {@code login}: AT Protocol OAuth is the application's sign-in. {@code link}: the user is already signed in
     * through the application's own mechanism and links a Bluesky account to that principal.
     */
    @WithDefault("login")
    Mode mode();

    /** Path of the unlink endpoint ({@code link} mode). */
    @WithDefault("/atproto/unlink")
    String unlinkPath();

    /**
     * Scope to retry the pushed authorization request with once when the authorization server answers
     * {@code invalid_scope} (for example {@code atproto transition:generic}). None by default.
     */
    Optional<String> fallbackScope();

    /**
     * Public base URL of this application (scheme, host, optional port and path), for example
     * {@code https://blog.example.com}. Makes this a confidential client whose {@code client_id} is
     * {@code <client-base-url><metadata-path>}. Required in production; when unset (or a loopback URL) in dev and
     * test mode the AT Protocol localhost development client is used instead.
     */
    Optional<String> clientBaseUrl();

    /** {@code client_name} in the client metadata document. */
    Optional<String> clientName();

    /** {@code client_uri} in the client metadata document; defaults to {@code client-base-url}. */
    Optional<String> clientUri();

    /** {@code logo_uri} in the client metadata document. */
    Optional<String> logoUri();

    /** {@code tos_uri} in the client metadata document. */
    Optional<String> tosUri();

    /** {@code policy_uri} in the client metadata document. */
    Optional<String> policyUri();

    /** Scopes requested, space separated. Least privilege by default: add what the application needs. */
    @WithDefault("atproto")
    String scope();

    /** Path of the login endpoint. */
    @WithDefault("/atproto/login")
    String loginPath();

    /** Path of the OAuth callback (the redirect URI). */
    @WithDefault("/atproto/oauth/callback")
    String callbackPath();

    /** Path of the logout endpoint ({@code login} mode). */
    @WithDefault("/atproto/logout")
    String logoutPath();

    /** Path the client metadata document is served at; part of the {@code client_id}. */
    @WithDefault("/oauth-client-metadata.json")
    String metadataPath();

    /** Path the client's public JWKS is served at ({@code jwks_uri}). */
    @WithDefault("/oauth/jwks.json")
    String jwksPath();

    /** Client signing keys. */
    SigningKey signingKey();

    /** Session cookie and lifetime. */
    Session session();

    /** Pending authorizations (between login and callback). */
    Pending pending();

    /** Where to send the browser after a successful login when no target was given. */
    @WithDefault("/")
    String landingPage();

    /** Where to send the browser when a login fails; when unset the callback answers 401 with the reason. */
    Optional<String> errorPage();

    enum Mode {
        LOGIN,
        LINK
    }

    interface SigningKey {
        /**
         * ES256 (P-256) private keys as PEM ({@code PRIVATE KEY}, PKCS#8) or JWK files, on the file system or the
         * class path. The first key signs; every key is published in the JWKS, so keys can be rotated by adding the
         * new key first and removing the old one later (removing a key revokes the sessions it authenticated).
         * Required for a confidential client.
         */
        Optional<List<String>> location();
    }

    interface Session {
        /** Name of the session cookie. */
        @WithDefault("atproto-session")
        String cookieName();

        /** Key encrypting the session cookie. Generated per run in dev mode, required in production. */
        Optional<String> encryptionKey();

        /** How long a session lasts. */
        @WithDefault("14d")
        Duration maxAge();

        /**
         * Several nodes sharing a session store: how long a node's refresh lease lasts at most
         * ({@code AtprotoSessionStore.tryAcquireRefreshLease}), and how long another node needing the same session
         * waits for that refresh's tokens before failing with {@code AtprotoTemporarilyUnavailable}.
         */
        @WithDefault("30s")
        Duration refreshLease();
    }

    interface Pending {
        /** How long a started login waits for its callback. */
        @WithDefault("10m")
        Duration ttl();
    }
}
