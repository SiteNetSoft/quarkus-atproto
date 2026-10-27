package org.sitenetsoft.quarkus.atproto.oauth.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

class AtprotoOauthProcessor {

    static final String FEATURE = "atproto-oauth";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }
}
