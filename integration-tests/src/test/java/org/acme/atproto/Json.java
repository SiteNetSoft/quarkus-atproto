package org.acme.atproto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;

import org.sitenetsoft.atproto.syntax.AtprotoSyntaxModule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new AtprotoSyntaxModule());

    private Json() {
    }

    /** The body of a 200 JSON response. */
    static JsonNode parse(HttpResponse<String> r) {
        assertEquals(200, r.statusCode(), () -> r.uri() + ": " + r.statusCode() + " " + r.body());
        try {
            return MAPPER.readTree(r.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static <T> T read(JsonNode node, Class<T> type) {
        try {
            return MAPPER.treeToValue(node, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
