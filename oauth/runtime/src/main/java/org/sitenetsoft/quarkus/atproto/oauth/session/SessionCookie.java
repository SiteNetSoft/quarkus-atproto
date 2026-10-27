package org.sitenetsoft.quarkus.atproto.oauth.session;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;

/**
 * The session cookie ({@code login} mode): the session id sealed with AES-256-GCM (random 96-bit IV, the cookie name
 * as associated data), {@code HttpOnly}, {@code SameSite=Lax}, {@code Path=/}, {@code Max-Age} = the session's
 * maximum age, {@code Secure} outside dev and test mode. The key is SHA-256 of
 * {@code quarkus.atproto.oauth.session.encryption-key} (at least 32 characters); in dev and test mode a random key is
 * generated per run when it is unset, in production it is required.
 * <p>
 * Also the login's <b>browser-binding cookie</b> ({@code <cookie-name>-binding}): a random value set by the login
 * endpoint, {@code Path} = the callback path, {@code Max-Age} = {@code pending.ttl}; the pending login stores its
 * SHA-256 and the callback requires it, so a callback URL completed in another browser (login CSRF) fails.
 */
@Singleton
public class SessionCookie {

    /** Property name for error messages. */
    public static final String ENCRYPTION_KEY = "quarkus.atproto.oauth.session.encryption-key";
    static final int MIN_KEY_LENGTH = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_COOKIE_LENGTH = 512;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger LOG = Logger.getLogger(SessionCookie.class);

    private final OAuthConfig config;
    private final String name;
    private final boolean secure;
    private volatile byte[] key;

    public SessionCookie(OAuthConfig config) {
        this.config = config;
        this.name = config.session().cookieName();
        LaunchMode mode = LaunchMode.current();
        this.secure = mode != LaunchMode.DEVELOPMENT && mode != LaunchMode.TEST;
    }

    void onStart(@Observes StartupEvent event) {
        if (config.mode() == OAuthConfig.Mode.LOGIN) {
            key(); // fails startup in production without a key
        }
    }

    /** The cookie name. */
    public String name() {
        return name;
    }

    /** The browser-binding cookie's name. */
    public String bindingName() {
        return name + "-binding";
    }

    /** Sets a fresh browser-binding cookie for a login starting now; returns the value's hash for the pending entry. */
    public String setBinding(RoutingContext ctx) {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        ctx.response().addCookie(Cookie.cookie(bindingName(), value).setPath(callbackPath()).setHttpOnly(true)
                .setSecure(secure).setSameSite(CookieSameSite.LAX).setMaxAge(config.pending().ttl().toSeconds()));
        return bindingHash(value);
    }

    /** Clears the browser-binding cookie. */
    public void clearBinding(RoutingContext ctx) {
        ctx.response().addCookie(Cookie.cookie(bindingName(), "").setPath(callbackPath()).setHttpOnly(true)
                .setSecure(secure).setSameSite(CookieSameSite.LAX).setMaxAge(0));
    }

    /** The hash of the request's browser-binding cookie, if it has a plausible one. */
    public Optional<String> bindingHash(HttpServerRequest request) {
        Cookie c = request.getCookie(bindingName());
        if (c == null || c.getValue() == null || c.getValue().isEmpty() || c.getValue().length() > 128) {
            return Optional.empty();
        }
        return Optional.of(bindingHash(c.getValue()));
    }

    /** base64url SHA-256 of a binding value. */
    static String bindingHash(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The callback's path as the browser sees it (under {@code quarkus.http.root-path}). */
    private String callbackPath() {
        String root = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("quarkus.http.root-path", String.class).orElse("/");
        String cb = config.callbackPath().startsWith("/") ? config.callbackPath() : "/" + config.callbackPath();
        return (root.endsWith("/") ? root.substring(0, root.length() - 1) : root) + cb;
    }

    /** Sets the session cookie for {@code sessionId}. */
    public void set(RoutingContext ctx, String sessionId) {
        ctx.response().addCookie(Cookie.cookie(name, seal(key(), name, sessionId)).setPath("/").setHttpOnly(true)
                .setSecure(secure).setSameSite(CookieSameSite.LAX).setMaxAge(config.session().maxAge().toSeconds()));
    }

    /** Clears the session cookie in the browser. */
    public void clear(RoutingContext ctx) {
        ctx.response().addCookie(Cookie.cookie(name, "").setPath("/").setHttpOnly(true).setSecure(secure)
                .setSameSite(CookieSameSite.LAX).setMaxAge(0));
    }

    /** The session id in the request's cookie; empty when there is none or it does not open (tampered, old key). */
    public Optional<String> sessionId(HttpServerRequest request) {
        Cookie c = request.getCookie(name);
        if (c == null || c.getValue() == null || c.getValue().isEmpty()) {
            return Optional.empty();
        }
        return open(key(), name, c.getValue());
    }

    private byte[] key() {
        byte[] k = key;
        if (k == null) {
            synchronized (this) {
                k = key;
                if (k == null) {
                    k = loadKey();
                    key = k;
                }
            }
        }
        return k;
    }

    private byte[] loadKey() {
        Optional<String> configured = config.session().encryptionKey().map(String::strip).filter(s -> !s.isEmpty());
        if (configured.isPresent()) {
            if (configured.get().length() < MIN_KEY_LENGTH) {
                throw new IllegalStateException(ENCRYPTION_KEY + " must be at least " + MIN_KEY_LENGTH + " characters");
            }
            return deriveKey(configured.get());
        }
        LaunchMode mode = LaunchMode.current();
        if (mode != LaunchMode.DEVELOPMENT && mode != LaunchMode.TEST) {
            throw new IllegalStateException("Set " + ENCRYPTION_KEY + " (at least " + MIN_KEY_LENGTH
                    + " random characters): it encrypts the AT Protocol session cookie and must be the same on every "
                    + "node and across restarts");
        }
        LOG.debugf("%s is unset: using a key generated for this run (sessions end at restart)", ENCRYPTION_KEY);
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        return k;
    }

    /** The AES-256 key for a configured secret: its SHA-256. */
    static byte[] deriveKey(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** base64url(IV || AES-GCM(id)), associated data = the cookie name. */
    static String seal(byte[] key, String cookieName, String sessionId) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(cookieName.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(sessionId.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
    }

    /** The session id sealed in {@code value}; empty for anything that does not decrypt and authenticate. */
    static Optional<String> open(byte[] key, String cookieName, String value) {
        if (value == null || value.length() > MAX_COOKIE_LENGTH) {
            return Optional.empty();
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(value);
            if (raw.length <= IV_BYTES + TAG_BITS / 8) {
                return Optional.empty();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            cipher.updateAAD(cookieName.getBytes(StandardCharsets.UTF_8));
            byte[] id = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return Optional.of(new String(id, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return Optional.empty();
        }
    }
}
