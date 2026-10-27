package org.sitenetsoft.quarkus.atproto.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.vertx.core.dns.DnsClientOptions;

class VertxTxtResolverTest {

    @Test
    void parsesServerAddresses() {
        check("1.1.1.1", "1.1.1.1", 53);
        check(" 9.9.9.9:5353 ", "9.9.9.9", 5353);
        check("dns.example.test", "dns.example.test", 53);
        check("[2606:4700::1111]", "2606:4700::1111", 53);
        check("[2606:4700::1111]:853", "2606:4700::1111", 853);
        assertThrows(IllegalArgumentException.class, () -> VertxTxtResolver.options("[::1"));
        assertThrows(IllegalArgumentException.class, () -> VertxTxtResolver.options("[::1]x"));
    }

    private static void check(String server, String host, int port) {
        DnsClientOptions o = VertxTxtResolver.options(server);
        assertEquals(host, o.getHost());
        assertEquals(port, o.getPort());
    }
}
