package org.sitenetsoft.quarkus.atproto.lexicon;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Test stub of the runtime type defined in quarkus-atproto-lexicon (Task 9). */
public record BlobRef(@JsonProperty("ref") CidLink ref, @JsonProperty("mimeType") String mimeType,
                      @JsonProperty("size") long size) {
}
