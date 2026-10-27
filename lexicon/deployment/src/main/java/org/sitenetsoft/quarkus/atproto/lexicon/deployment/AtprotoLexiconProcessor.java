package org.sitenetsoft.quarkus.atproto.lexicon.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.jboss.logging.Logger;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.quarkus.atproto.lexicon.AtprotoObjectMapperCustomizer;
import org.sitenetsoft.quarkus.atproto.lexicon.LexiconRecorder;
import org.sitenetsoft.quarkus.atproto.lexicon.LexiconRegistry;
import org.sitenetsoft.quarkus.atproto.lexicon.LexiconValidator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.paths.PathTree;

class AtprotoLexiconProcessor {

    static final String FEATURE = "atproto-lexicon";
    private static final Logger LOG = Logger.getLogger(AtprotoLexiconProcessor.class);
    private static final String DIR = LexiconRegistry.RESOURCE_DIR;
    private static final String INDEX = DIR + "/index.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem beans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(LexiconRegistry.class, LexiconValidator.class, AtprotoObjectMapperCustomizer.class)
                .setUnremovable()
                .build();
    }

    /**
     * Finds every {@code META-INF/atproto/lexicons/**.json} (except {@code index.json}) in the application and its
     * runtime dependencies, plus the application's own {@code src/main/lexicons} (the code generator's input, packaged
     * here under {@code META-INF/atproto/lexicons/} so the validator knows those Lexicons too). The NSID → resource
     * list is recorded for {@link LexiconRegistry}; the resources are registered for native images.
     */
    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void discover(CurateOutcomeBuildItem curate, ApplicationArchivesBuildItem archives, LexiconRecorder recorder,
            BuildProducer<GeneratedResourceBuildItem> generated,
            BuildProducer<NativeImageResourceBuildItem> nativeResources) {
        ApplicationModel model = curate.getApplicationModel();
        Discovery found = new Discovery();
        // the root archive is the application's classes (in QuarkusUnitTest, the test archive), not always the
        // app artifact's content tree
        archives.getRootArchive().accept(tree -> found.scan(tree, "the application"));
        for (var dep : model.getRuntimeDependencies()) {
            found.scan(dep.getContentTree(), dep.toCompactCoords());
        }
        for (Map.Entry<String, byte[]> e : appSourceLexicons(model).entrySet()) {
            if (found.add(e.getKey(), e.getValue(), "src/main/lexicons")) {
                generated.produce(new GeneratedResourceBuildItem(e.getKey(), e.getValue()));
            }
        }
        LOG.debugf("Lexicons on the classpath: %s", found.resources.keySet());
        recorder.register(found.resources);
        if (!found.resources.isEmpty()) {
            nativeResources.produce(new NativeImageResourceBuildItem(new ArrayList<>(found.resources.values())));
        }
    }

    /**
     * The application's {@code src/main/lexicons/**.json} as resources under {@value #DIR}, when built from source.
     * Without workspace information the directory cannot be located: logged at WARN, since the validator then does not
     * know the application's own record types (optimistic validation lets them through unchecked).
     */
    static Map<String, byte[]> appSourceLexicons(ApplicationModel model) {
        Map<String, byte[]> out = new TreeMap<>();
        WorkspaceModule module = model.getApplicationModule();
        if (module == null || module.getModuleDir() == null) {
            LOG.warnf("No workspace module for %s: Lexicons in src/main/%s are not registered for validation "
                    + "(ship them under src/main/resources/%s/ instead)", model.getAppArtifact().toCompactCoords(),
                    AtprotoLexiconCodeGen.INPUT_DIRECTORY, DIR);
            return out;
        }
        Path dir = module.getModuleDir().toPath().resolve("src/main/" + AtprotoLexiconCodeGen.INPUT_DIRECTORY);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json") && Files.isRegularFile(f)).toList()) {
                byte[] bytes = Files.readAllBytes(p);
                String id = JSON.readTree(bytes).path("id").asText();
                if (Nsid.isValid(id)) {
                    out.put(resourceFor(id), bytes);
                } else {
                    LOG.warnf("%s: no valid Lexicon \"id\"; not registered", p);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + dir, e);
        }
        return out;
    }

    static String resourceFor(String nsid) {
        return DIR + "/" + nsid.replace('.', '/') + ".json";
    }

    private static final class Discovery {
        final Map<String, String> resources = new TreeMap<>();
        final Map<String, String> origins = new TreeMap<>();

        void scan(PathTree tree, String origin) {
            tree.walkIfContains(DIR, visit -> {
                String name = visit.getResourceName().replace('\\', '/');
                if (name.endsWith(".json") && !name.equals(INDEX) && Files.isRegularFile(visit.getPath())) {
                    try {
                        add(name, Files.readAllBytes(visit.getPath()), origin);
                    } catch (IOException e) {
                        throw new UncheckedIOException("cannot read " + name + " in " + origin, e);
                    }
                }
            });
        }

        /** @return whether the document was added (a usable Lexicon not already found elsewhere) */
        boolean add(String resource, byte[] content, String origin) {
            JsonNode doc;
            try {
                doc = JSON.readTree(content);
            } catch (IOException e) {
                LOG.warnf("%s in %s is not valid JSON; ignored: %s", resource, origin, e.getMessage());
                return false;
            }
            String id = doc.path("id").asText();
            if (!Nsid.isValid(id) || !doc.path("defs").isObject()) {
                LOG.warnf("%s in %s is not a Lexicon document (no valid \"id\" or \"defs\"); ignored", resource,
                        origin);
                return false;
            }
            if (!resource.equals(resourceFor(id))) {
                LOG.warnf("%s in %s declares id %s; Lexicon files belong at %s", resource, origin, id,
                        resourceFor(id));
            }
            String previous = origins.putIfAbsent(id, origin);
            if (previous != null) {
                LOG.warnf("Lexicon %s is shipped by both %s and %s; the first on the classpath wins", id, previous,
                        origin);
                return false;
            }
            resources.put(id, resource);
            return true;
        }
    }
}
