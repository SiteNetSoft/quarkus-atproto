package org.sitenetsoft.quarkus.atproto.lexicon;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WireTypesTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test void bytesWriteStandardBase64WithoutPadding() throws Exception {
    Bytes b = Bytes.of("hi?".getBytes(StandardCharsets.US_ASCII)); // aGk/ : '/' only in the standard alphabet
    assertEquals("{\"$bytes\":\"aGk/\"}", MAPPER.writeValueAsString(b));
    assertEquals("{\"$bytes\":\"aGk\"}", MAPPER.writeValueAsString(Bytes.of("hi".getBytes(StandardCharsets.US_ASCII))));
    assertEquals(b, MAPPER.readValue("{\"$bytes\":\"aGk/\"}", Bytes.class));
  }

  @Test void bytesAcceptPaddedInputAndAreImmutableValues() throws Exception {
    assertEquals(Bytes.of(new byte[] {'h', 'i'}), MAPPER.readValue("{\"$bytes\":\"aGk=\"}", Bytes.class));
    byte[] raw = {1, 2, 3};
    Bytes b = Bytes.of(raw);
    raw[0] = 9;
    b.toByteArray()[1] = 9;
    assertArrayEquals(new byte[] {1, 2, 3}, b.toByteArray());
    assertEquals(Bytes.of(new byte[] {1, 2, 3}).hashCode(), b.hashCode());
    assertNotEquals(Bytes.of(new byte[] {1, 2}), b);
    assertThrows(Exception.class, () -> MAPPER.readValue("{}", Bytes.class));
  }

  @Test void blobRefWireForm() throws Exception {
    String json = "{\"$type\":\"blob\",\"ref\":{\"$link\":\"bafkreie2pd7dqnj2rxyfilg45hpohna73p7ykfmeax4m4rg7wjxyg6ktye\"},"
        + "\"mimeType\":\"image/png\",\"size\":34552}";
    BlobRef ref = MAPPER.readValue(json, BlobRef.class);
    assertEquals(new BlobRef("blob", new CidLink("bafkreie2pd7dqnj2rxyfilg45hpohna73p7ykfmeax4m4rg7wjxyg6ktye"),
        "image/png", 34552), ref);
    assertEquals(MAPPER.readTree(json), MAPPER.readTree(MAPPER.writeValueAsString(ref)));
    assertEquals("{\"$link\":\"bafy\"}", MAPPER.writeValueAsString(new CidLink("bafy")));
  }

  @Test void blobRefOfFillsTheType() {
    assertEquals("blob", BlobRef.of(new CidLink("bafy"), "image/png", 1).type());
  }
}
