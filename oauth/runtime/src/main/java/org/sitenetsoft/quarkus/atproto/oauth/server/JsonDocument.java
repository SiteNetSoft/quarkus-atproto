package org.sitenetsoft.quarkus.atproto.oauth.server;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/** A JSON document serialised once and served as is. */
final class JsonDocument {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Buffer body;

    JsonDocument(Map<String, Object> document) {
        try {
            this.body = Buffer.buffer(JSON.writeValueAsBytes(document));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + e.getOriginalMessage());
        }
    }

    void send(RoutingContext ctx) {
        ctx.response()
                .putHeader("Content-Type", "application/json")
                .putHeader("Cache-Control", "public, max-age=600")
                .putHeader("Access-Control-Allow-Origin", "*")
                .end(body);
    }
}
