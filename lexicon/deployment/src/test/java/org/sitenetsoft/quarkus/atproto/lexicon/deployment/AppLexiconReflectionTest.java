package org.sitenetsoft.quarkus.atproto.lexicon.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.jboss.shrinkwrap.api.asset.FileAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Native reflection for types generated from the application's own Lexicons: the code generator's output for
 * {@code org/example/note.json} (compiled here, as the application build would) is the application, and every class it
 * contains (records, nested objects, unions and their {@code Unknown}) ends up registered for reflection.
 */
class AppLexiconReflectionTest {

    static final String PROPERTY = AppLexiconReflectionTest.class.getName() + ".registered";
    static final String GENERATED = AppLexiconReflectionTest.class.getName() + ".generated";

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> {
                Path classes = generateAndCompile();
                List<String> names = new ArrayList<>();
                try (Stream<Path> files = Files.walk(classes)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                        String resource = classes.relativize(f).toString().replace('\\', '/');
                        jar.add(new FileAsset(f.toFile()), resource);
                        names.add(resource.substring(0, resource.length() - ".class".length()).replace('/', '.'));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                System.setProperty(GENERATED, String.join(",", names));
            })
            .addBuildChainCustomizer(chain -> chain.addBuildStep(context -> {
                Set<String> registered = new TreeSet<>();
                for (var item : context.consumeMulti(
                        io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem.class)) {
                    if (item.isConstructors() && item.isMethods() && item.isFields()) {
                        registered.addAll(item.getClassNames());
                    }
                }
                System.setProperty(PROPERTY, String.join(",", registered));
            }).consumes(io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem.class)
                    .produces(io.quarkus.deployment.builditem.GeneratedResourceBuildItem.class).build());

    static Path generateAndCompile() {
        try {
            Path tmp = Files.createTempDirectory(Path.of("target"), "app-lexicon-reflection");
            Path in = tmp.resolve("lexicons/org/example/note.json");
            Files.createDirectories(in.getParent());
            try (InputStream s = AppLexiconReflectionTest.class.getResourceAsStream("/lexicons/org/example/note.json")) {
                Files.copy(s, in);
            }
            Path out = Files.createDirectories(tmp.resolve("generated"));
            Path appRoot = Files.createDirectories(tmp.resolve("app-classes"));
            CodeGenContext context = new CodeGenContext(new ApplicationModelBuilder()
                    .setAppArtifact(ResolvedDependencyBuilder.newInstance().setGroupId("org.example")
                            .setArtifactId("app").setVersion("1.0").setResolvedPath(appRoot))
                    .addDependencies(AppCodegenTest.packs()).build(), out, tmp.resolve("work"),
                    tmp.resolve("lexicons"), false, new SmallRyeConfigBuilder().build(), false);
            new AtprotoLexiconCodeGen().trigger(context);
            List<String> args = new ArrayList<>(List.of("-d", appRoot.toString(), "-classpath",
                    System.getProperty("java.class.path"), "-proc:none"));
            for (Path p : AppCodegenTest.list(out)) {
                args.add(p.toString());
            }
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            if (javac.run(null, null, null, args.toArray(String[]::new)) != 0) {
                throw new IllegalStateException("compilation of the generated sources failed");
            }
            return appRoot;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void everyGeneratedClassIsRegistered() {
        Set<String> registered = Set.of(System.getProperty(PROPERTY, "").split(","));
        List<String> generated = List.of(System.getProperty(GENERATED, "").split(","));
        assertTrue(generated.contains("org.example.Note"), generated.toString());
        assertTrue(generated.stream().anyMatch(n -> n.startsWith("org.example.Note$")), generated.toString());
        for (String name : generated) {
            if (!name.contains(AtprotoLexiconCodeGen.REFLECTION_HOLDER)) {
                assertTrue(registered.contains(name), name + " not registered: " + registered);
            }
        }
    }
}
