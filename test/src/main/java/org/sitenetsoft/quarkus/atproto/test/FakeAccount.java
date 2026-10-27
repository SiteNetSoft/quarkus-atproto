package org.sitenetsoft.quarkus.atproto.test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.multikey.KeyPairs;
import org.sitenetsoft.multikey.KeyType;
import org.sitenetsoft.multikey.Multikey;

/**
 * An account on the fakes: a {@code did:plc:} DID derived from the handle (the same handle always yields the same DID,
 * even across class loaders), the handle, and a P-256 {@code #atproto} signing key published in its DID document.
 */
public final class FakeAccount {

  private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

  private final Did did;
  private final Handle handle;
  private final KeyPair signingKey;

  FakeAccount(Handle handle) {
    this.handle = handle;
    this.did = Did.parse("did:plc:" + plcId(handle.value()));
    this.signingKey = KeyPairs.generate(KeyType.P256);
  }

  public Did did() {
    return did;
  }

  public Handle handle() {
    return handle;
  }

  /** The {@code publicKeyMultibase} of the {@code #atproto} verification method. */
  public String publicKeyMultibase() {
    return Multikey.of(signingKey.getPublic()).encode();
  }

  public KeyPair signingKey() {
    return signingKey;
  }

  /** 24 base32 characters of SHA-256 of the handle, like a real PLC identifier. */
  static String plcId(String handle) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(handle.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(24);
      for (int i = 0; i < 24; i++) {
        sb.append(BASE32[(h[i] & 0xff) % 32]);
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public String toString() {
    return handle + " (" + did + ")";
  }
}
