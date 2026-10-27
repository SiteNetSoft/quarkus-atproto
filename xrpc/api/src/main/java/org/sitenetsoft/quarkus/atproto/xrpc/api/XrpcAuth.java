package org.sitenetsoft.quarkus.atproto.xrpc.api;

import io.smallrye.mutiny.Uni;

/**
 * Authenticates an XRPC request (an OAuth session with DPoP, an app password, ...). The client calls
 * {@link #authorize} before sending, shows every response (the first and a retried one) to {@link #observe}, and, when
 * the first response is a 4xx for which {@link #shouldRetry} answers {@code true}, authorizes and sends the request
 * once more (a rotated DPoP nonce, a refreshed token). A 2xx is never resent (a write would happen twice), nor a 5xx.
 */
@FunctionalInterface
public interface XrpcAuth {
  /** The request with its credentials added (typically through {@link XrpcRequest#withHeader}). */
  Uni<XrpcRequest> authorize(XrpcRequest request);

  /**
   * Sees every response, the retried one included, before anything else happens with it: the place to store a
   * server's new DPoP nonce. Default: nothing.
   */
  default void observe(XrpcResponse response) {
  }

  /**
   * Whether the request should be authorized and sent again after this 4xx response (after {@link #observe}); asked
   * at most once per request and never for a 2xx or 5xx. Default: never.
   */
  default boolean shouldRetry(XrpcResponse response) {
    return false;
  }
}
