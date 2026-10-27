package org.sitenetsoft.quarkus.atproto.xrpc.api;

import java.util.Map;

/**
 * A non-2xx XRPC answer: the HTTP status and the body's {@code error} and {@code message} fields.
 */
public class XrpcException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final int status;
  private final String error;
  private final String errorMessage;
  private final Map<String, String> rateLimit;

  /**
   * @param status the HTTP status
   * @param error the XRPC error name ({@code InvalidSwap}), or {@code null} when the body had none
   * @param message the human-readable {@code message}, or {@code null}
   * @param rateLimit the {@code ratelimit-*} response headers (empty when none)
   */
  public XrpcException(int status, String error, String message, Map<String, String> rateLimit) {
    super(status + (error == null ? "" : " " + error) + (message == null ? "" : ": " + message));
    this.status = status;
    this.error = error;
    this.errorMessage = message;
    this.rateLimit = rateLimit == null ? Map.of() : Map.copyOf(rateLimit);
  }

  public int status() {
    return status;
  }

  /** The XRPC error name, or {@code null}. */
  public String error() {
    return error;
  }

  /** The body's {@code message}, or {@code null}. */
  public String errorMessage() {
    return errorMessage;
  }

  /** The {@code ratelimit-*} headers of the response. */
  public Map<String, String> rateLimit() {
    return rateLimit;
  }
}
