package org.sitenetsoft.quarkus.atproto.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import io.vertx.core.Vertx;
import io.vertx.core.dns.DnsClient;
import io.vertx.core.dns.DnsClientOptions;
import io.vertx.core.dns.DnsException;
import io.vertx.core.dns.DnsResponseCode;

/** The fake DNS server answers a real DNS client (Vert.x, as the identity extension's default resolver uses). */
class FakeDnsTest {

  @Test
  void answersTxtAndNxdomain() {
    Vertx vertx = Vertx.vertx();
    try (FakeDns dns = FakeDns.start(0)) {
      FakeAccount alice = new FakeAccount(org.sitenetsoft.atproto.syntax.Handle.parse("alice.example.test"));
      dns.handle(alice).txt("multi.example.test", "a", "b");
      DnsClient client = vertx.createDnsClient(new DnsClientOptions().setHost("127.0.0.1").setPort(dns.port())
          .setQueryTimeout(5000));

      assertEquals(List.of("did=" + alice.did().value()), join(client.resolveTXT("_atproto.alice.example.test")));
      assertEquals(List.of("a", "b"), join(client.resolveTXT("MULTI.example.test.")));
      CompletionException e = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
          () -> join(client.resolveTXT("_atproto.nobody.example.test")));
      assertTrue(e.getCause() instanceof DnsException d && d.code() == DnsResponseCode.NXDOMAIN, e.toString());
      assertTrue(dns.server().endsWith(":" + dns.port()), dns.server());
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().join();
    }
  }

  private static List<String> join(io.vertx.core.Future<List<String>> f) {
    return f.toCompletionStage().toCompletableFuture().join();
  }
}
