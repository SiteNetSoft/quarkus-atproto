package org.sitenetsoft.quarkus.atproto.xrpc.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

class AtprotoXrpcProcessor {

    static final String FEATURE = "atproto-xrpc";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }
}
