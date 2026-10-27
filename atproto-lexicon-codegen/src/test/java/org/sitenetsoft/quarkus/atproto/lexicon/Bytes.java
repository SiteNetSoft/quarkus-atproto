package org.sitenetsoft.quarkus.atproto.lexicon;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * Test stub of the runtime type created in Task 8: immutable bytes with value equality, written as
 * {@code {"$bytes": "<base64 without padding>"}}.
 */
public final class Bytes {
  private final byte[] value;

  private Bytes(byte[] value) {
    this.value = value;
  }

  public static Bytes of(byte[] value) {
    return new Bytes(Objects.requireNonNull(value, "value").clone());
  }

  public byte[] toByteArray() {
    return value.clone();
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
}
