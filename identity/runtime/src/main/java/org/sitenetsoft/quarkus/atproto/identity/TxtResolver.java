package org.sitenetsoft.quarkus.atproto.identity;

import java.util.List;

import io.smallrye.mutiny.Uni;

/**
 * DNS TXT lookup SPI. The default ({@link VertxTxtResolver}) uses the Quarkus-managed Vert.x DNS client; declare
 * your own CDI bean implementing this interface to replace it (tests use a map).
 * <p>
 * Implementations must not block. A name without TXT records (NXDOMAIN or an empty answer) yields an empty list;
 * other errors fail the {@code Uni}. Each element is one TXT string.
 */
public interface TxtResolver {

    Uni<List<String>> txt(String name);
}
