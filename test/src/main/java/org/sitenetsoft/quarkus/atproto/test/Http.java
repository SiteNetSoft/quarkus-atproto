package org.sitenetsoft.quarkus.atproto.test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

/** Small Vert.x helpers shared by the fakes. */
final class Http {

  static final ObjectMapper MAPPER = new ObjectMapper();

  private Http() {
  }

  /** A router that buffers bodies (up to 10 MB) and records every request into {@code log} once answered. */
  static Router router(io.vertx.core.Vertx vertx, List<Recorded> log) {
    Router router = Router.router(vertx);
    router.route().handler(BodyHandler.create().setBodyLimit(10L * 1024 * 1024));
    router.route().handler(ctx -> {
      ctx.addBodyEndHandler(v -> {
        Map<String, String> headers = new HashMap<>();
        ctx.request().headers().forEach(e -> headers.putIfAbsent(e.getKey().toLowerCase(Locale.ROOT), e.getValue()));
        Buffer body = ctx.body() == null ? null : ctx.body().buffer();
        log.add(new Recorded(ctx.request().method().name(), ctx.request().path(), ctx.request().query(), headers,
            body == null ? "" : body.toString(StandardCharsets.UTF_8), ctx.response().getStatusCode()));
      });
      ctx.next();
    });
    return router;
  }

  static void json(RoutingContext ctx, int status, Object body) {
    try {
      ctx.response().setStatusCode(status).putHeader("Content-Type", "application/json")
          .putHeader("Cache-Control", "no-store").end(MAPPER.writeValueAsString(body));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** An OAuth error body {@code {"error": ..., "error_description": ...}}. */
  static void oauthError(RoutingContext ctx, int status, String error, String description) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", error);
    body.put("error_description", description);
    json(ctx, status, body);
  }

  /** An XRPC error body {@code {"error": ..., "message": ...}}. */
  static void xrpcError(RoutingContext ctx, int status, String error, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", error);
    body.put("message", message);
    json(ctx, status, body);
  }

  /** Parses {@code application/x-www-form-urlencoded}; repeated names keep every value. */
  static Map<String, List<String>> form(String body) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    if (body == null || body.isEmpty()) {
      return out;
    }
    for (String pair : body.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
    }
    return out;
  }

  static String bodyText(RoutingContext ctx) {
    return ctx.body() == null || ctx.body().buffer() == null ? "" : ctx.body().asString(StandardCharsets.UTF_8.name());
  }

  /** The URL the client addressed: scheme, the authority and the path (what {@code htu} must match). */
  static String requestUrl(RoutingContext ctx) {
    return "http://" + authority(ctx) + ctx.request().path();
  }

  /** {@code Host} (HTTP/1.1) or {@code :authority} (h2c), as {@code host[:port]}. */
  static String authority(RoutingContext ctx) {
    io.vertx.core.net.HostAndPort a = ctx.request().authority();
    if (a == null) {
      return "127.0.0.1";
    }
    return a.host() + (a.port() > 0 ? ":" + a.port() : "");
  }
}
