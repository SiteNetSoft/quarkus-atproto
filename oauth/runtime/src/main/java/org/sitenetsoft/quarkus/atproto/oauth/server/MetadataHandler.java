package org.sitenetsoft.quarkus.atproto.oauth.server;

import jakarta.inject.Singleton;

import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientIdentity;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientMetadata;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * {@code GET {metadata-path}}: the confidential client's metadata document. 404 for the localhost development client,
 * whose metadata the authorization server derives from the {@code client_id}.
 */
@Singleton
public class MetadataHandler implements Handler<RoutingContext> {

    private final ClientIdentity identity;
    private final JsonDocument document;

    public MetadataHandler(ClientIdentity identity, OAuthConfig config) {
        this.identity = identity;
        this.document = identity.confidential() ? new JsonDocument(ClientMetadata.document(identity, config)) : null;
    }

    @Override
    public void handle(RoutingContext ctx) {
        if (!identity.confidential()) {
            ctx.next();
            return;
        }
        document.send(ctx);
    }
}
