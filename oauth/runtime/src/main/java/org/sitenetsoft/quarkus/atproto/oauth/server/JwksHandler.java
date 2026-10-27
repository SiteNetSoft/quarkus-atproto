package org.sitenetsoft.quarkus.atproto.oauth.server;

import jakarta.inject.Singleton;

import org.sitenetsoft.quarkus.atproto.oauth.client.ClientIdentity;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientKeys;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/** {@code GET {jwks-path}}: every configured signing key's public JWK. 404 for the development client. */
@Singleton
public class JwksHandler implements Handler<RoutingContext> {

    private final boolean confidential;
    private final JsonDocument document;

    public JwksHandler(ClientIdentity identity, ClientKeys keys) {
        this.confidential = identity.confidential();
        this.document = new JsonDocument(keys.jwks());
    }

    @Override
    public void handle(RoutingContext ctx) {
        if (!confidential) {
            ctx.next();
            return;
        }
        document.send(ctx);
    }
}
