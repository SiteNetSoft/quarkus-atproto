package org.sitenetsoft.quarkus.atproto.xrpc.api;

import io.smallrye.mutiny.Uni;

/**
 * Authenticates an XRPC request (an OAuth session with DPoP, an app password, ...). The client calls
 * {@link #authorize} before sending and, when {@link #shouldRetry} answers {@code true} for the response, authorizes
 * and sends the request once more (a rotated DPoP nonce, a refreshed token).
 */
@FunctionalInterface
public interface XrpcAuth {
  /** The request with its credentials added (typically through {@link XrpcRequest#withHeader}). */
  Uni<XrpcRequest> authorize(XrpcRequest request);

  /**
   * Whether the request should be authorized and sent again after this response; called at most once per request.
   * Implementations may update their state here (store a new DPoP nonce). Default: never.
   */
  default boolean shouldRetry(XrpcResponse response) {
    return false;
  }
}
