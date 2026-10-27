package org.sitenetsoft.quarkus.atproto.xrpc;

import java.io.IOException;
import java.lang.reflect.Array;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.quarkus.atproto.xrpc.api.ReactiveXrpcClient;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcAuth;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcException;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcRequest;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcResponse;
import org.sitenetsoft.quarkus.safefetch.FetchOptions;
import org.sitenetsoft.quarkus.safefetch.FetchResponse;
import org.sitenetsoft.quarkus.safefetch.ReactiveSafeFetchClient;
import org.sitenetsoft.quarkus.safefetch.SafeFetchException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.mutiny.Uni;

/**
 * The XRPC client over {@code quarkus-safe-fetch}: {@code <service>/xrpc/<nsid>}, JSON in and out (a {@code byte[]}
 * output class takes the raw body, {@code Void} discards it). With an {@link XrpcAuth} the request is authorized, sent,
 * and every response (first and retried) shown to {@link XrpcAuth#observe}; when the first response is a 4xx and
 * {@link XrpcAuth#shouldRetry} answers {@code true} the request is authorized again and sent once more. A 2xx is never
 * resent (a write would happen twice), nor a 5xx (the server failed; repeating a write is not this layer's call).
 * A response that comes from another host than the one asked (a followed {@code GET} redirect) is refused.
 */
@ApplicationScoped
public class XrpcClientImpl implements ReactiveXrpcClient {

  private static final Logger LOG = Logger.getLogger("org.sitenetsoft.quarkus.atproto.xrpc");
  private static final String JSON = "application/json";
  private static final String RATE_LIMIT_PREFIX = "ratelimit-";

  private final ReactiveSafeFetchClient fetch;
  private final ObjectMapper mapper;
  private final FetchOptions options;

  @Inject
  public XrpcClientImpl(ReactiveSafeFetchClient fetch, ObjectMapper mapper, XrpcConfig config) {
    this.fetch = fetch;
    this.mapper = mapper;
    this.options = FetchOptions.defaults().withTimeout(config.timeout())
        .withMaxResponseSize(config.maxResponseSize().asLongValue());
  }

  @Override
  public <T> Uni<T> query(URI service, Nsid nsid, Map<String, ?> params, Class<T> out, Optional<XrpcAuth> auth) {
    return Uni.createFrom().deferred(() -> {
      Map<String, String> headers = Map.of("Accept", JSON);
      return call(new XrpcRequest("GET", url(service, nsid, params), headers, null), out, auth);
    });
  }

  @Override
  public <T> Uni<T> procedure(URI service, Nsid nsid, Object input, Class<T> out, Optional<XrpcAuth> auth) {
    return Uni.createFrom().deferred(() -> {
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("Accept", JSON);
      byte[] body = null;
      if (input != null) {
        try {
          body = mapper.writeValueAsBytes(input);
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("Cannot write the " + nsid + " input as JSON: " + e.getOriginalMessage(),
              e);
        }
        headers.put("Content-Type", JSON);
      }
      return call(new XrpcRequest("POST", url(service, nsid, Map.of()), headers, body), out, auth);
    });
  }

  @Override
  public <T> Uni<T> procedureBytes(URI service, Nsid nsid, byte[] body, String contentType, Class<T> out,
      Optional<XrpcAuth> auth) {
    return Uni.createFrom().deferred(() -> {
      Objects.requireNonNull(body, "body");
      Objects.requireNonNull(contentType, "contentType");
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("Accept", JSON);
      headers.put("Content-Type", contentType);
      return call(new XrpcRequest("POST", url(service, nsid, Map.of()), headers, body), out, auth);
    });
  }

  private <T> Uni<T> call(XrpcRequest request, Class<T> out, Optional<XrpcAuth> auth) {
    Objects.requireNonNull(out, "out");
    Optional<XrpcAuth> a = auth == null ? Optional.empty() : auth;
    return exchange(request, a).map(response -> read(request, response, out));
  }

  private Uni<XrpcResponse> exchange(XrpcRequest request, Optional<XrpcAuth> auth) {
    if (auth.isEmpty()) {
      return send(request);
    }
    XrpcAuth a = auth.get();
    return authorizeAndSend(request, a).chain(response -> {
      if (clientError(response.status()) && a.shouldRetry(response)) {
        LOG.debugf("XRPC %s %s answered %d; authorizing again and retrying once", request.method(),
            path(request.url()), response.status());
        return authorizeAndSend(request, a);
      }
      return Uni.createFrom().item(response);
    });
  }

  private Uni<XrpcResponse> authorizeAndSend(XrpcRequest request, XrpcAuth auth) {
    return auth.authorize(request).chain(authorized -> {
      if (authorized == null) {
        return Uni.createFrom().failure(new IllegalStateException("XrpcAuth.authorize returned null"));
      }
      return send(authorized).invoke(auth::observe);
    });
  }

  private Uni<XrpcResponse> send(XrpcRequest request) {
    FetchOptions o = options;
    for (Map.Entry<String, String> h : request.headers().entrySet()) {
      o = o.withHeader(h.getKey(), h.getValue());
    }
    return fetch.send(request.method(), request.url(), request.body(), o).map(r -> response(request, r));
  }

  private static XrpcResponse response(XrpcRequest request, FetchResponse r) {
    URI asked = request.url();
    if (r.uri() != null && !sameHost(asked, r.uri())) {
      throw new SafeFetchException("Refusing the XRPC response for " + path(asked) + ": redirected from "
          + asked.getHost() + " to " + r.uri().getHost());
    }
    LOG.debugf("XRPC %s %s -> %d", request.method(), path(asked), r.status());
    return new XrpcResponse(r.status(), r.headers(), r.body());
  }

  private <T> T read(XrpcRequest request, XrpcResponse response, Class<T> out) {
    byte[] body = response.body();
    if (!success(response.status())) {
      throw error(response);
    }
    if (out == byte[].class) {
      return out.cast(body);
    }
    if (out == Void.class || body.length == 0) {
      return null;
    }
    try {
      return mapper.readValue(body, out);
    } catch (IOException e) {
      String why = e instanceof JsonProcessingException j ? j.getOriginalMessage() : e.getMessage();
      throw new XrpcException(response.status(), "InvalidResponse",
          "the " + path(request.url()) + " response does not map to " + out.getSimpleName() + ": " + why,
          rateLimit(response));
    }
  }

  private XrpcException error(XrpcResponse response) {
    String error = null;
    String message = null;
    try {
      JsonNode node = response.body().length == 0 ? null : mapper.readTree(response.body());
      if (node != null && node.isObject()) {
        error = node.path("error").isTextual() ? node.get("error").asText() : null;
        message = node.path("message").isTextual() ? node.get("message").asText() : null;
      }
    } catch (IOException e) {
      // not a JSON error body (a proxy's HTML page): status only
    }
    return new XrpcException(response.status(), error, message, rateLimit(response));
  }

  /** The {@code ratelimit-*} headers, names lower-cased, first value each. */
  static Map<String, String> rateLimit(XrpcResponse response) {
    Map<String, String> out = new TreeMap<>();
    response.headers().forEach((name, values) -> {
      String n = name.toLowerCase(Locale.ROOT);
      if (n.startsWith(RATE_LIMIT_PREFIX) && !values.isEmpty()) {
        out.putIfAbsent(n, values.get(0));
      }
    });
    return out;
  }

  /**
   * {@code <service>/xrpc/<nsid>?<params>}: {@code null} values left out, collections and arrays repeated, values
   * written with {@code toString()} (syntax types give their string form), percent-encoded.
   */
  static URI url(URI service, Nsid nsid, Map<String, ?> params) {
    Objects.requireNonNull(service, "service");
    Objects.requireNonNull(nsid, "nsid");
    if (!service.isAbsolute() || service.getHost() == null || service.getRawQuery() != null
        || service.getRawFragment() != null) {
      throw new IllegalArgumentException("Not a service base URL (absolute, with a host, no query): " + service);
    }
    String base = service.toString();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    StringBuilder url = new StringBuilder(base).append("/xrpc/").append(nsid);
    char sep = '?';
    if (params != null) {
      for (Map.Entry<String, ?> e : params.entrySet()) {
        for (Object v : values(e.getValue())) {
          url.append(sep).append(encode(e.getKey())).append('=').append(encode(String.valueOf(v)));
          sep = '&';
        }
      }
    }
    return URI.create(url.toString());
  }

  private static List<Object> values(Object value) {
    if (value == null) {
      return List.of();
    }
    if (value instanceof Iterable<?> it) {
      ArrayList<Object> all = new ArrayList<>();
      it.forEach(v -> {
        if (v != null) {
          all.add(v);
        }
      });
      return all;
    }
    if (value.getClass().isArray()) {
      ArrayList<Object> all = new ArrayList<>();
      for (int i = 0; i < Array.getLength(value); i++) {
        Object v = Array.get(value, i);
        if (v != null) {
          all.add(v);
        }
      }
      return all;
    }
    return List.of(value);
  }

  private static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static boolean clientError(int status) {
    return status >= 400 && status < 500;
  }

  private static boolean success(int status) {
    return status >= 200 && status < 300;
  }

  private static boolean sameHost(URI a, URI b) {
    return a.getHost() != null && b.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost());
  }

  /** The URL without its query (parameters can be personal; never logged). */
  private static String path(URI url) {
    return url.getScheme() + "://" + url.getRawAuthority() + url.getRawPath();
  }
}
