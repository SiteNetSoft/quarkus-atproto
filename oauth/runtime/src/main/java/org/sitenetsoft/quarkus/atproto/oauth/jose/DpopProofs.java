package org.sitenetsoft.quarkus.atproto.oauth.jose;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.sitenetsoft.multikey.Jwk;
import org.sitenetsoft.quarkus.atproto.oauth.Origins;

/** DPoP proofs (RFC 9449 §4): {@code dpop+jwt}, ES256, the public key in the {@code jwk} header. */
public final class DpopProofs {

    private static final SecureRandom RANDOM = new SecureRandom();

    private DpopProofs() {
    }

    /**
     * A fresh proof for one request.
     *
     * @param dpopKey the session's P-256 DPoP key pair
     * @param htm the HTTP method, as sent
     * @param htu the request URL; query and fragment are stripped (RFC 9449 §4.2)
     * @param nonce the server's last {@code DPoP-Nonce}, if any
     * @param accessToken the access token sent with the request (resource requests): its hash becomes {@code ath}
     * @param now the issue time ({@code iat}, seconds)
     */
    public static String proof(KeyPair dpopKey, String htm, URI htu, Optional<String> nonce,
            Optional<String> accessToken, Instant now) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("typ", "dpop+jwt");
        header.put("jwk", Jwk.of(dpopKey.getPublic()));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("jti", jti());
        claims.put("htm", htm);
        claims.put("htu", htu(htu));
        claims.put("iat", now.getEpochSecond());
        nonce.ifPresent(n -> claims.put("nonce", n));
        accessToken.ifPresent(t -> claims.put("ath", ath(t)));
        return Jws.sign(header, claims, dpopKey.getPrivate());
    }

    /** {@code ath}: base64url SHA-256 of the ASCII access token. */
    public static String ath(String accessToken) {
        return Jws.b64(sha256(accessToken.getBytes(StandardCharsets.US_ASCII)));
    }

    /** {@code uri} without user info, query and fragment; scheme and host lower case; default port omitted. */
    public static String htu(URI uri) {
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        // origin form: no user info, lower-case scheme and host, default port dropped (RFC 9449 §4.3 normalisation)
        return Origins.of(uri) + path;
    }

    /** 16 random bytes, base64url: a JWT id. */
    static String jti() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Jws.b64(b);
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
