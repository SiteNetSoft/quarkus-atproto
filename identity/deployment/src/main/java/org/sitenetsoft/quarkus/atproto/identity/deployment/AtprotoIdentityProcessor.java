package org.sitenetsoft.quarkus.atproto.identity.deployment;

import org.sitenetsoft.quarkus.atproto.identity.BlockingIdentityResolver;
import org.sitenetsoft.quarkus.atproto.identity.IdentityResolver;
import org.sitenetsoft.quarkus.atproto.identity.IdentityResolverImpl;
import org.sitenetsoft.quarkus.atproto.identity.ReactiveIdentityResolver;
import org.sitenetsoft.quarkus.atproto.identity.TxtResolver;
import org.sitenetsoft.quarkus.atproto.identity.VertxTxtResolver;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

class AtprotoIdentityProcessor {

    static final String FEATURE = "atproto-identity";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem beans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(IdentityResolverImpl.class, BlockingIdentityResolver.class, VertxTxtResolver.class)
                .setUnremovable()
                .build();
    }

    @BuildStep
    UnremovableBeanBuildItem unremovableSpis() {
        return UnremovableBeanBuildItem.beanTypes(TxtResolver.class, IdentityResolver.class,
                ReactiveIdentityResolver.class);
    }
}
