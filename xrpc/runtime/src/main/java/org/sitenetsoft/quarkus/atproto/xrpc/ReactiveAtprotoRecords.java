package org.sitenetsoft.quarkus.atproto.xrpc;

import java.util.Optional;

import org.sitenetsoft.atproto.syntax.Cid;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.atproto.syntax.RecordKey;
import org.sitenetsoft.quarkus.atproto.lexicon.BlobRef;

import io.smallrye.mutiny.Uni;

/**
 * Record writes and reads in one repository on its PDS, with the session's authorization. A record is a generated
 * Lexicon class ({@code site.standard.Document}), a {@code JsonNode} or a {@code Map}; it is sent with its
 * {@code $type} (the collection, added when missing) after the {@link WriteGuard} allowed the write and the
 * {@code LexiconValidator} accepted it in the configured mode ({@link RecordValidationException} otherwise; nothing
 * is sent). PDS errors fail with {@code XrpcException}.
 */
public interface ReactiveAtprotoRecords {

  /** {@code createRecord} with a server-generated TID record key. */
  Uni<RecordResult> create(Nsid collection, Object record);

  /** {@code createRecord} with the caller's record key (a client {@code Tid}, {@code self}, ...). */
  Uni<RecordResult> create(Nsid collection, RecordKey rkey, Object record);

  /** {@code putRecord}: create or replace; {@code swapRecord} makes it fail unless the current version has that CID. */
  Uni<RecordResult> put(Nsid collection, RecordKey rkey, Object record, Optional<Cid> swapRecord);

  /**
   * {@code getRecord}, the value mapped to {@code type} ({@code JsonNode.class} for the raw value); empty when the PDS
   * answers {@code RecordNotFound}.
   */
  <T> Uni<Optional<T>> get(Nsid collection, RecordKey rkey, Class<T> type);

  /** {@code deleteRecord}. */
  Uni<Void> delete(Nsid collection, RecordKey rkey);

  /** {@code uploadBlob}; reference the returned {@link BlobRef} from a record soon, or the PDS drops the blob. */
  Uni<BlobRef> uploadBlob(byte[] data, String mimeType);
}
