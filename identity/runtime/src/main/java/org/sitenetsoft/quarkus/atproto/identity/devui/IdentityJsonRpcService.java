package org.sitenetsoft.quarkus.atproto.identity.devui;

import jakarta.inject.Inject;

import org.sitenetsoft.atproto.syntax.AtIdentifier;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.quarkus.atproto.identity.IdentityConfig;
import org.sitenetsoft.quarkus.atproto.identity.IdentityResolverImpl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.smallrye.mutiny.Uni;

/**
 * Dev UI backend: resolves a handle (as a user would type it) or a DID live.
 */
public class IdentityJsonRpcService {

    @Inject
    IdentityResolverImpl resolver;

    @Inject
    IdentityConfig config;

    @Inject
    ObjectMapper mapper;

    public ObjectNode settings() {
        ObjectNode n = mapper.createObjectNode();
        n.put("cacheTtl", config.cacheTtl().toString());
        n.put("dnsServers", config.dns().servers().map(Object::toString).orElse("system"));
        n.put("wellKnownOverrides", config.httpsWellKnownOverride().keySet().toString());
        return n;
    }

    public Uni<JsonNode> lookup(String identifier) {
        AtIdentifier id;
        try {
            String s = identifier == null ? "" : identifier.strip();
            id = s.startsWith("did:") ? Did.parse(s) : Handle.normalise(s);
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(error(e));
        }
        return resolver.resolve(id).map(identity -> {
            ObjectNode n = mapper.createObjectNode();
            n.put("did", identity.did().value());
            n.put("handle", identity.handle().value());
            n.put("handleVerified", identity.handleVerified());
            n.put("pds", identity.pds().toString());
            n.put("signingKeyMultibase", identity.signingKeyMultibase().orElse(null));
            return (JsonNode) n;
        }).onFailure().recoverWithItem(this::error);
    }

    private JsonNode error(Throwable t) {
        ObjectNode n = mapper.createObjectNode();
        n.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        return n;
    }
}
