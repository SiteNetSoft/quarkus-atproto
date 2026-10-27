package org.sitenetsoft.quarkus.atproto.xrpc.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class XrpcTypesTest {
  @Test void requestWithHeaderCopies() {
    XrpcRequest r = new XrpcRequest("POST", URI.create("https://pds.example/xrpc/com.atproto.repo.createRecord"),
        Map.of("content-type", "application/json"), new byte[] {1});
    XrpcRequest authed = r.withHeader("authorization", "Bearer x");
    assertEquals("Bearer x", authed.headers().get("authorization"));
    assertEquals("application/json", authed.headers().get("content-type"));
    assertNull(r.headers().get("authorization"));
    assertThrows(UnsupportedOperationException.class, () -> authed.headers().put("a", "b"));
  }

  @Test void responseHeaderLookupIsCaseInsensitive() {
    XrpcResponse resp = new XrpcResponse(401, Map.of("WWW-Authenticate", List.of("DPoP error=\"use_dpop_nonce\"")),
        new byte[0]);
    assertEquals("DPoP error=\"use_dpop_nonce\"", resp.header("www-authenticate").orElseThrow());
    assertTrue(resp.header("dpop-nonce").isEmpty());
  }

  @Test void exceptionCarriesTheXrpcError() {
    XrpcException e = new XrpcException(400, "InvalidSwap", "record was at bafy", Map.of("ratelimit-remaining", "9"));
    assertEquals(400, e.status());
    assertEquals("InvalidSwap", e.error());
    assertEquals("record was at bafy", e.errorMessage());
    assertEquals("9", e.rateLimit().get("ratelimit-remaining"));
    assertTrue(e.getMessage().contains("InvalidSwap"), e.getMessage());
  }

  @Test void authDoesNotRetryByDefault() {
    XrpcAuth auth = req -> io.smallrye.mutiny.Uni.createFrom().item(req.withHeader("authorization", "Bearer t"));
    assertFalse(auth.shouldRetry(new XrpcResponse(401, Map.of(), new byte[0])));
  }
}
