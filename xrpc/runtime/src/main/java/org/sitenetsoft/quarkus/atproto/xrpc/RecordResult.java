package org.sitenetsoft.quarkus.atproto.xrpc;

import java.util.Objects;
import java.util.Optional;

import org.sitenetsoft.atproto.syntax.AtUri;
import org.sitenetsoft.atproto.syntax.Cid;

/**
 * A written record.
 *
 * @param uri the record's {@code at://} URI
 * @param cid the CID of the version written
 * @param validationStatus the PDS's {@code validationStatus} ({@code valid}, {@code unknown}), when it sent one
 */
public record RecordResult(AtUri uri, Cid cid, Optional<String> validationStatus) {
  public RecordResult {
    Objects.requireNonNull(uri, "uri");
    Objects.requireNonNull(cid, "cid");
    validationStatus = validationStatus == null ? Optional.empty() : validationStatus;
  }
}
