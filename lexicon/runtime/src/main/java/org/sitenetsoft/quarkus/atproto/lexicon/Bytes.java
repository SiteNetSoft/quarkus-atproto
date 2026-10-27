package org.sitenetsoft.quarkus.atproto.lexicon;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * A Lexicon {@code bytes} value: immutable, compared by content, written as
 * {@code {"$bytes": "<base64, standard alphabet, no padding>"}}. Padded input is accepted when reading.
 */
public final class Bytes {
  private final byte[] value;

  private Bytes(byte[] value) {
    this.value = value;
  }

  /** A copy of {@code value}; later changes to the array do not affect it. */
  public static Bytes of(byte[] value) {
    return new Bytes(Objects.requireNonNull(value, "value").clone());
  }

  /** A fresh copy of the bytes. */
  public byte[] toByteArray() {
    return value.clone();
  }

  public int length() {
    return value.length;
  }

  @JsonValue
  Map<String, String> json() {
    return Map.of("$bytes", Base64.getEncoder().withoutPadding().encodeToString(value));
  }

  @JsonCreator
  static Bytes fromJson(@JsonProperty("$bytes") String base64) {
    return new Bytes(Base64.getDecoder().decode(Objects.requireNonNull(base64, "$bytes")));
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Bytes b && Arrays.equals(value, b.value);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(value);
  }

  @Override
  public String toString() {
    return "Bytes[" + value.length + "]";
  }
}
