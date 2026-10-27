package org.sitenetsoft.quarkus.atproto.oauth.deployment;

import org.sitenetsoft.quarkus.atproto.oauth.OAuthBuildTimeConfig;
import org.sitenetsoft.quarkus.atproto.oauth.devui.OAuthJsonRpcService;

import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.devui.spi.JsonRPCProvidersBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;

/**
 * The AT Protocol OAuth Dev UI card (client type, {@code client_id}, the served client metadata, active sessions
 * without tokens, a sign-in box), in dev mode when {@code quarkus.atproto.oauth.enabled}.
 */
public class OAuthDevUiProcessor {

    @BuildStep(onlyIf = IsDevelopment.class)
    void devUi(OAuthBuildTimeConfig config, BuildProducer<CardPageBuildItem> cards,
            BuildProducer<JsonRPCProvidersBuildItem> jsonRpc) {
        if (!config.enabled()) {
            return;
        }
        CardPageBuildItem card = new CardPageBuildItem();
        card.addPage(Page.webComponentPageBuilder()
                .title("OAuth client")
                .icon("font-awesome-solid:key")
                .componentLink("qwc-atproto-oauth.js"));
        card.addPage(Page.externalPageBuilder("OAuth spec")
                .url("https://atproto.com/specs/oauth")
                .icon("font-awesome-solid:book")
                .doNotEmbed());
        cards.produce(card);
        jsonRpc.produce(new JsonRPCProvidersBuildItem(OAuthJsonRpcService.class));
    }
}
