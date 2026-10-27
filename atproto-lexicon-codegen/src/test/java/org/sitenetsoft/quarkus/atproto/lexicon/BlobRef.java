package org.sitenetsoft.quarkus.atproto.lexicon;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Test stub of the runtime type defined in quarkus-atproto-lexicon (same components and wire form). */
public record BlobRef(@JsonProperty("$type") String type, @JsonProperty("ref") CidLink ref,
                      @JsonProperty("mimeType") String mimeType, @JsonProperty("size") long size) {
}
