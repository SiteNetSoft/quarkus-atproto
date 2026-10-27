package org.sitenetsoft.quarkus.atproto.identity.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

class AtprotoIdentityProcessor {

    static final String FEATURE = "atproto-identity";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }
}
