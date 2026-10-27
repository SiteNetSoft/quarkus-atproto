package org.sitenetsoft.quarkus.atproto.xrpc.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An XRPC response, as {@link XrpcAuth#shouldRetry} sees it.
 *
 * @param status the HTTP status
 * @param headers response headers (unmodifiable)
 * @param body the body (empty when none)
 */
public record XrpcResponse(int status, Map<String, List<String>> headers, byte[] body) {
  public XrpcResponse {
    headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
    body = body == null ? new byte[0] : body;
  }

  /** The first value of header {@code name}, compared ignoring case. */
  public Optional<String> header(String name) {
    return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
        .flatMap(e -> e.getValue().stream()).findFirst();
  }
}
