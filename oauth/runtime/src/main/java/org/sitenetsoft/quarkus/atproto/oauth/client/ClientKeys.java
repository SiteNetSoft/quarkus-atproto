package org.sitenetsoft.quarkus.atproto.oauth.client;

import static org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig.SIGNING_KEY_LOCATION;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sitenetsoft.multikey.Algorithm;
import org.sitenetsoft.multikey.Jwk;
import org.sitenetsoft.multikey.JwkThumbprint;
import org.sitenetsoft.multikey.MultikeyException;
import org.sitenetsoft.multikey.Pem;
import org.sitenetsoft.multikey.Signatures;

/**
 * The confidential client's signing keys, loaded from {@code quarkus.atproto.oauth.signing-key.location}: the first
 * signs, all are published. ES256 (P-256) only.
 */
public final class ClientKeys {

    private static final BigInteger P = new BigInteger(
            "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16);
    private static final BigInteger A = P.subtract(BigInteger.valueOf(3));
    private static final BigInteger GX = new BigInteger(
            "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16);
    private static final BigInteger GY = new BigInteger(
            "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16);

    private final List<ClientKey> keys;

    private ClientKeys(List<ClientKey> keys) {
        this.keys = List.copyOf(keys);
    }

    /** No keys (a public client). */
    public static ClientKeys none() {
        return new ClientKeys(List.of());
    }

    /** Loads each location (file path, else class path resource). Failures name the property and the location. */
    public static ClientKeys load(List<String> locations) {
        List<ClientKey> keys = new ArrayList<>();
        for (String location : locations) {
            String text = read(location);
            ClientKey key;
            try {
                key = text.stripLeading().startsWith("{") ? fromJwk(text, location) : fromPem(text, location);
            } catch (MultikeyException e) {
                throw fail(location, e.getMessage());
            }
            if (keys.stream().anyMatch(k -> k.kid().equals(key.kid()))) {
                throw fail(location, "the same key is listed twice");
            }
            keys.add(key);
        }
        return new ClientKeys(keys);
    }

    /** All keys, signing key first. */
    public List<ClientKey> keys() {
        return keys;
    }

    /** The key that signs client assertions (the first configured). */
    public Optional<ClientKey> signing() {
        return keys.isEmpty() ? Optional.empty() : Optional.of(keys.get(0));
    }

    /** {@code {"keys": [...]}} with public members only. */
    public Map<String, Object> jwks() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("keys", keys.stream().map(ClientKey::publicJwk).toList());
        return doc;
    }

    private static ClientKey fromJwk(String text, String location) {
        Map<String, Object> jwk = Jwk.parse(text);
        if (jwk.get("keys") instanceof List<?>) {
            throw fail(location, "give one JWK per file, not a JWK set");
        }
        if (!"EC".equals(jwk.get("kty")) || !"P-256".equals(jwk.get("crv"))) {
            throw fail(location, "only ES256 (P-256) keys are supported, not kty=" + jwk.get("kty") + " crv="
                    + jwk.get("crv"));
        }
        if (!jwk.containsKey("d")) {
            throw fail(location, "the JWK has no private key ('d')");
        }
        return checked(Jwk.privateKey(jwk), Jwk.publicKey(jwk), location);
    }

    private static ClientKey fromPem(String text, String location) {
        List<Key> blocks = Pem.parseAll(text);
        PrivateKey priv = null;
        PublicKey pub = null;
        for (Key k : blocks) {
            if (k instanceof PrivateKey p) {
                if (priv != null) {
                    throw fail(location, "more than one PRIVATE KEY block; use one file per key");
                }
                priv = p;
            } else if (k instanceof PublicKey p) {
                pub = p;
            }
        }
        if (priv == null) {
            throw fail(location, "no PEM PRIVATE KEY (PKCS#8) block found");
        }
        if (!(priv instanceof ECPrivateKey ec) || !isP256(ec.getParams())) {
            throw fail(location, "only ES256 (P-256) keys are supported, not " + priv.getAlgorithm());
        }
        PublicKey derived = derivePublic(ec);
        if (pub != null && !pub.equals(derived)) {
            throw fail(location, "the PUBLIC KEY block does not match the private key");
        }
        return checked(priv, derived, location);
    }

    private static ClientKey checked(PrivateKey priv, PublicKey pub, String location) {
        byte[] probe = "quarkus-atproto-oauth key check".getBytes(StandardCharsets.US_ASCII);
        if (!Signatures.verify(Algorithm.ES256, pub, probe, Signatures.sign(Algorithm.ES256, priv, probe))) {
            throw fail(location, "the private and public halves do not match");
        }
        return new ClientKey(JwkThumbprint.sha256(Jwk.of(pub)), priv, pub);
    }

    private static boolean isP256(ECParameterSpec params) {
        return params != null && params.getCurve().getField().getFieldSize() == 256
                && params.getCurve().getA().equals(A) && params.getGenerator().getAffineX().equals(GX);
    }

    /** {@code d·G} on P-256 (affine double-and-add; runs once per key at startup). */
    static PublicKey derivePublic(ECPrivateKey key) {
        BigInteger[] q = null;
        BigInteger[] g = {GX, GY};
        BigInteger d = key.getS();
        for (int i = d.bitLength() - 1; i >= 0; i--) {
            q = q == null ? null : add(q, q);
            if (d.testBit(i)) {
                q = q == null ? g : add(q, g);
            }
        }
        if (q == null) {
            throw new MultikeyException("private key is zero");
        }
        try {
            return KeyFactory.getInstance("EC").generatePublic(
                    new ECPublicKeySpec(new ECPoint(q[0], q[1]), key.getParams()));
        } catch (java.security.GeneralSecurityException e) {
            throw new MultikeyException("cannot build the public key");
        }
    }

    private static BigInteger[] add(BigInteger[] p1, BigInteger[] p2) {
        if (p1 == null) {
            return p2;
        }
        BigInteger lambda;
        if (p1[0].equals(p2[0])) {
            if (!p1[1].equals(p2[1]) || p1[1].signum() == 0) {
                return null; // point at infinity
            }
            BigInteger num = p1[0].pow(2).multiply(BigInteger.valueOf(3)).add(A);
            lambda = num.multiply(p1[1].shiftLeft(1).modInverse(P)).mod(P);
        } else {
            lambda = p2[1].subtract(p1[1]).multiply(p2[0].subtract(p1[0]).modInverse(P)).mod(P);
        }
        BigInteger x = lambda.pow(2).subtract(p1[0]).subtract(p2[0]).mod(P);
        BigInteger y = lambda.multiply(p1[0].subtract(x)).subtract(p1[1]).mod(P);
        return new BigInteger[] {x, y};
    }

    private static String read(String location) {
        Path path = Path.of(location);
        try {
            if (Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
            String resource = location.startsWith("/") ? location.substring(1) : location;
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            try (InputStream in = cl == null ? null : cl.getResourceAsStream(resource)) {
                if (in == null) {
                    throw fail(location, "no such file or class path resource");
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw fail(location, "cannot be read (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static IllegalStateException fail(String location, String reason) {
        return new IllegalStateException(SIGNING_KEY_LOCATION + " '" + location + "': " + reason);
    }

    @Override
    public String toString() {
        return "ClientKeys" + keys;
    }
}
