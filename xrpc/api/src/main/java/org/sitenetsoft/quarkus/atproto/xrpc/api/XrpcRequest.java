package org.sitenetsoft.quarkus.atproto.xrpc.api;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An XRPC request about to be sent, as {@link XrpcAuth} sees it.
 *
 * @param method {@code GET} or {@code POST}
 * @param url the full URL, query string included
 * @param headers request headers (unmodifiable)
 * @param body the body, or {@code null} for none
 */
public record XrpcRequest(String method, URI url, Map<String, String> headers, byte[] body) {
  public XrpcRequest {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(url, "url");
    headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
  }

  /** A copy with {@code name} set to {@code value} (replacing a header of the same name, compared ignoring case). */
  public XrpcRequest withHeader(String name, String value) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(value, "value");
    Map<String, String> h = new LinkedHashMap<>(headers);
    h.keySet().removeIf(k -> k.equalsIgnoreCase(name));
    h.put(name, value);
    return new XrpcRequest(method, url, h, body);
  }
}
