package org.sitenetsoft.quarkus.atproto.oauth.client;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.sitenetsoft.multikey.Jwk;

/**
 * A confidential client's ES256 signing key. {@code kid} is the RFC 7638 thumbprint of the public key.
 * {@link #toString()} never shows the private key.
 */
public final class ClientKey {

    private final String kid;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public ClientKey(String kid, PrivateKey privateKey, PublicKey publicKey) {
        this.kid = Objects.requireNonNull(kid, "kid");
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
    }

    public String kid() {
        return kid;
    }

    public PrivateKey privateKey() {
        return privateKey;
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    /** The public JWK as published: {@code kty, crv, x, y, kid, alg, use}. */
    public Map<String, Object> publicJwk() {
        Map<String, Object> jwk = new LinkedHashMap<>(Jwk.of(publicKey));
        jwk.put("kid", kid);
        jwk.put("alg", "ES256");
        jwk.put("use", "sig");
        return jwk;
    }

    @Override
    public String toString() {
        return "ClientKey[kid=" + kid + "]";
    }
}
