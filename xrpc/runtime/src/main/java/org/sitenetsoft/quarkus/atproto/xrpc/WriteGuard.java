package org.sitenetsoft.quarkus.atproto.xrpc;

import java.util.Optional;

import org.sitenetsoft.atproto.syntax.Nsid;

/**
 * Asked by the records API before every write; throws to refuse it (no request is sent). The default bean allows
 * everything; the OAuth extension checks the session's granted scope.
 */
@FunctionalInterface
public interface WriteGuard {

  /**
   * @param collection the record collection; for a blob upload {@code com.atproto.repo.uploadBlob}
   * @param action {@code create}, {@code update} (a {@code put}) or {@code delete}; a blob upload is {@code create}
   * @param blobMime the MIME type of a blob upload, empty for record writes
   * @throws RuntimeException to refuse the write
   */
  void check(Nsid collection, String action, Optional<String> blobMime);
}
