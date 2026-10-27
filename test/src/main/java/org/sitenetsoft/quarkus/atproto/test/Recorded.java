package org.sitenetsoft.quarkus.atproto.test;

import java.util.Map;
import java.util.Optional;

/**
 * One request a fake server answered: method, path, raw query (or {@code null}), request headers (names lower-case,
 * first value), the request body as UTF-8 text and the response status.
 */
public record Recorded(String method, String path, String query, Map<String, String> headers, String body,
    int status) {

  public Recorded {
    headers = Map.copyOf(headers);
  }

  /** A request header by case-insensitive name. */
  public Optional<String> header(String name) {
    return Optional.ofNullable(headers.get(name.toLowerCase(java.util.Locale.ROOT)));
  }
}
