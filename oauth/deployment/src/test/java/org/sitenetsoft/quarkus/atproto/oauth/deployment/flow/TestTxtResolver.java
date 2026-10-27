package org.sitenetsoft.quarkus.atproto.oauth.deployment.flow;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.quarkus.atproto.identity.TxtResolver;

import io.smallrye.mutiny.Uni;

/** No DNS in tests: every TXT lookup answers no records, so handles resolve through the well-known override. */
@ApplicationScoped
public class TestTxtResolver implements TxtResolver {

    @Override
    public Uni<List<String>> txt(String name) {
        return Uni.createFrom().item(List.of());
    }
}
