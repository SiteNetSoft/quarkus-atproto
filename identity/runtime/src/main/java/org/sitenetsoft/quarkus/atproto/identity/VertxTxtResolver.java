package org.sitenetsoft.quarkus.atproto.identity;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.core.dns.DnsClient;
import io.vertx.core.dns.DnsClientOptions;
import io.vertx.core.dns.DnsException;
import io.vertx.core.dns.DnsResponseCode;

/**
 * Default {@link TxtResolver}: the Quarkus-managed Vert.x DNS client. Servers come from
 * {@code quarkus.atproto.identity.dns.servers} (tried in order) or, when unset, the system resolver configuration.
 * NXDOMAIN and empty answers give an empty list.
 * <p>
 * Each string of a TXT record is returned as its own element; {@code did=} records fit in one string.
 */
@DefaultBean
@ApplicationScoped
public class VertxTxtResolver implements TxtResolver {

    private final Vertx vertx;
    private final List<String> servers;
    private volatile List<DnsClient> clients;

    @Inject
    public VertxTxtResolver(Vertx vertx, IdentityConfig config) {
        this.vertx = vertx;
        this.servers = config.dns().servers().orElse(List.of());
    }

    @Override
    public Uni<List<String>> txt(String name) {
        return attempt(clients(), 0, name);
    }

    private Uni<List<String>> attempt(List<DnsClient> all, int index, String name) {
        DnsClient client = all.get(index);
        return Uni.createFrom().completionStage(() -> client.resolveTXT(name).toCompletionStage())
                .map(r -> r == null ? List.<String> of() : List.copyOf(r))
                .onFailure(VertxTxtResolver::isNxDomain).recoverWithItem(List.of())
                .onFailure(t -> index + 1 < all.size()).recoverWithUni(() -> attempt(all, index + 1, name));
    }

    private static boolean isNxDomain(Throwable t) {
        return t instanceof DnsException e && e.code() == DnsResponseCode.NXDOMAIN;
    }

    private List<DnsClient> clients() {
        List<DnsClient> c = clients;
        if (c == null) {
            synchronized (this) {
                c = clients;
                if (c == null) {
                    c = new ArrayList<>();
                    if (servers.isEmpty()) {
                        c.add(vertx.createDnsClient());
                    } else {
                        for (String server : servers) {
                            c.add(vertx.createDnsClient(options(server)));
                        }
                    }
                    clients = c = List.copyOf(c);
                }
            }
        }
        return c;
    }

    /** {@code host}, {@code host:port}, {@code [v6]} or {@code [v6]:port}. */
    static DnsClientOptions options(String server) {
        String s = server.strip();
        String host = s;
        int port = 53;
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("Invalid DNS server: " + server);
            }
            host = s.substring(1, close);
            if (s.length() > close + 1) {
                if (s.charAt(close + 1) != ':') {
                    throw new IllegalArgumentException("Invalid DNS server: " + server);
                }
                port = Integer.parseInt(s.substring(close + 2));
            }
        } else if (s.indexOf(':') >= 0 && s.indexOf(':') == s.lastIndexOf(':')) {
            host = s.substring(0, s.indexOf(':'));
            port = Integer.parseInt(s.substring(s.indexOf(':') + 1));
        }
        return new DnsClientOptions().setHost(host).setPort(port).setRecursionDesired(true);
    }
}
