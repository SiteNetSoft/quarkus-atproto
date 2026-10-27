package org.sitenetsoft.quarkus.atproto.lexicon.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

class AtprotoLexiconProcessor {

    static final String FEATURE = "atproto-lexicon";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }
}
