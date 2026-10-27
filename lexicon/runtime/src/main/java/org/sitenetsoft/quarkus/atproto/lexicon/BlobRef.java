package org.sitenetsoft.quarkus.atproto.lexicon;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;

/**
 * A Lexicon {@code blob} reference in its JSON wire form:
 * {@code {"$type": "blob", "ref": {"$link": "<cid>"}, "mimeType": "image/png", "size": 1234}}.
 *
 * @param type always {@value #TYPE}
 * @param ref the blob's CID
 * @param mimeType the blob's MIME type
 * @param size the blob's size in bytes
 */
@JsonPropertyOrder({"$type", "ref", "mimeType", "size"})
public record BlobRef(@JsonProperty("$type") String type, @JsonProperty("ref") CidLink ref,
                      @JsonProperty("mimeType") String mimeType, @JsonProperty("size") long size) {
  public static final String TYPE = "blob";

  public BlobRef {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(mimeType, "mimeType");
  }

  /** A reference with {@code $type} {@value #TYPE}, as {@code com.atproto.repo.uploadBlob} returns it. */
  public static BlobRef of(CidLink ref, String mimeType, long size) {
    return new BlobRef(TYPE, ref, mimeType, size);
  }
}
