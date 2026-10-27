package org.sitenetsoft.quarkus.atproto.identity.deployment;

import org.sitenetsoft.quarkus.atproto.identity.devui.IdentityJsonRpcService;

import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.devui.spi.JsonRPCProvidersBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;

public class AtprotoIdentityDevUiProcessor {

    @BuildStep(onlyIf = IsDevelopment.class)
    CardPageBuildItem card() {
        CardPageBuildItem card = new CardPageBuildItem();
        card.addPage(Page.webComponentPageBuilder()
                .title("Identity lookup")
                .icon("font-awesome-solid:id-card")
                .componentLink("qwc-atproto-identity.js"));
        card.addPage(Page.externalPageBuilder("Handle spec")
                .url("https://atproto.com/specs/handle")
                .icon("font-awesome-solid:book")
                .doNotEmbed());
        return card;
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    JsonRPCProvidersBuildItem jsonRpc() {
        return new JsonRPCProvidersBuildItem(IdentityJsonRpcService.class);
    }
}
