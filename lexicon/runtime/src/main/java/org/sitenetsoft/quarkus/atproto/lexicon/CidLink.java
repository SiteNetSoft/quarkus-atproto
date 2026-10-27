package org.sitenetsoft.quarkus.atproto.lexicon;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * A Lexicon {@code cid-link}: a CID in the JSON wire form {@code {"$link": "<cid>"}}.
 *
 * @param link the CID string
 */
public record CidLink(@JsonProperty("$link") String link) {
  public CidLink {
    Objects.requireNonNull(link, "link");
  }
}
