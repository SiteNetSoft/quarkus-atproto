package org.sitenetsoft.quarkus.atproto.xrpc;

import java.util.Optional;

import org.sitenetsoft.atproto.syntax.Cid;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.atproto.syntax.RecordKey;
import org.sitenetsoft.quarkus.atproto.lexicon.BlobRef;

/** Blocking twin of {@link ReactiveAtprotoRecords}; never call it on the event loop. */
public interface AtprotoRecords {

  RecordResult create(Nsid collection, Object record);

  RecordResult create(Nsid collection, RecordKey rkey, Object record);

  RecordResult put(Nsid collection, RecordKey rkey, Object record, Optional<Cid> swapRecord);

  <T> Optional<T> get(Nsid collection, RecordKey rkey, Class<T> type);

  void delete(Nsid collection, RecordKey rkey);

  BlobRef uploadBlob(byte[] data, String mimeType);
}
