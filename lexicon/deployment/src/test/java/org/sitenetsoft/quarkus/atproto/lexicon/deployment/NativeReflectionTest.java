package org.sitenetsoft.quarkus.atproto.lexicon.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.test.QuarkusUnitTest;

/** Every class in the packs' {@code index.json}, with its nested classes, is registered for reflection (native). */
class NativeReflectionTest {

    /** QuarkusUnitTest loads this class twice (build and test class loaders): the build step reports here. */
    static final String PROPERTY = NativeReflectionTest.class.getName() + ".registered";

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .addBuildChainCustomizer(capture());

    static Consumer<BuildChainBuilder> capture() {
        return chain -> chain.addBuildStep(new BuildStep() {
            @Override
            public void execute(BuildContext context) {
                Set<String> registered = new java.util.TreeSet<>();
                for (ReflectiveClassBuildItem item : context.consumeMulti(ReflectiveClassBuildItem.class)) {
                    if (item.isConstructors() && item.isMethods() && item.isFields()) {
                        registered.addAll(item.getClassNames());
                    }
                }
                System.setProperty(PROPERTY, String.join(",", registered));
            }
        }).consumes(ReflectiveClassBuildItem.class)
                .produces(io.quarkus.deployment.builditem.GeneratedResourceBuildItem.class).build();
    }

    @Test
    void packTypesAndTheirNestedTypes() {
        Set<String> registered = Set.of(System.getProperty(PROPERTY, "").split(","));
        for (String name : new String[] { "site.standard.Document", "site.standard.Publication",
                "site.standard.Document$Contributor", "site.standard.Document$Content",
                "site.standard.Document$Content$Unknown", "site.standard.Publication$Preferences",
                "site.standard.theme.Basic", "com.atproto.repo.StrongRef" }) {
            assertTrue(registered.contains(name), name + " not registered: " + registered);
        }
    }
}
