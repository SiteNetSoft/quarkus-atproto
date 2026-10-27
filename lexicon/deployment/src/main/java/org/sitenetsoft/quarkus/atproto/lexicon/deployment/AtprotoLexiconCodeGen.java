package org.sitenetsoft.quarkus.atproto.lexicon.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;
import org.sitenetsoft.atproto.lexicon.codegen.GeneratedIndex;
import org.sitenetsoft.atproto.lexicon.codegen.Generator;
import org.sitenetsoft.atproto.lexicon.codegen.LexiconException;
import org.sitenetsoft.atproto.lexicon.codegen.LexiconParser;
import org.sitenetsoft.atproto.lexicon.codegen.LexiconSet;
import org.sitenetsoft.atproto.lexicon.codegen.model.LexiconDoc;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.quarkus.atproto.lexicon.LexiconRegistry;

import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.deployment.CodeGenProvider;
import io.quarkus.maven.dependency.ResolvedDependency;

/**
 * Generates Java types from the application's own Lexicons ({@code src/main/lexicons/**.json}) in the build's
 * {@code generate-code} phase.
 *
 * <p>References to NSIDs of dependency Lexicon packs resolve to the classes those packs already ship, found through
 * every {@code META-INF/atproto/lexicons/index.json} among the runtime dependencies; pack Lexicon JSON is read to check
 * references but never generated again. A dependency that ships Lexicon JSON <em>without</em> classes is generated
 * into the application only when listed in {@code quarkus.atproto.lexicon.codegen.generate-from-dependencies}.
 */
public class AtprotoLexiconCodeGen implements CodeGenProvider {
    static final String INPUT_DIRECTORY = "lexicons";
    static final String PREFIX = "quarkus.atproto.lexicon.codegen.";
    private static final Logger LOG = Logger.getLogger(AtprotoLexiconCodeGen.class);
    private static final String DIR = LexiconRegistry.RESOURCE_DIR;

    @Override
    public String providerId() {
        return "atproto-lexicon";
    }

    @Override
    public String inputExtension() {
        return "json";
    }

    @Override
    public String inputDirectory() {
        return INPUT_DIRECTORY;
    }

    @Override
    public boolean shouldRun(Path sourceDir, Config config) {
        return Files.isDirectory(sourceDir) || !list(config, "generate-from-dependencies").isEmpty();
    }

    @Override
    public boolean trigger(CodeGenContext context) throws CodeGenException {
        Config config = context.config();
        List<String> include = list(config, "include");
        List<String> exclude = list(config, "exclude");
        Optional<String> prefix = config.getOptionalValue(PREFIX + "package-prefix", String.class)
                .filter(p -> !p.isBlank());
        Set<String> fromDependencies = new LinkedHashSet<>(list(config, "generate-from-dependencies"));
        try {
            Map<String, LexiconDoc> docs = new LinkedHashMap<>();
            Set<Nsid> toGenerate = new LinkedHashSet<>();
            for (LexiconDoc doc : appDocs(context.inputDir())) {
                docs.put(doc.id().value(), doc);
                toGenerate.add(doc.id());
            }

            Map<String, String> dependencyIndex = new TreeMap<>();
            Set<String> listedFound = new LinkedHashSet<>();
            for (ResolvedDependency dep : context.applicationModel().getRuntimeDependencies()) {
                String ga = dep.getGroupId() + ":" + dep.getArtifactId();
                boolean generate = fromDependencies.contains(ga);
                Map<String, String> index = dep.getContentTree().apply(GeneratedIndex.RESOURCE,
                        visit -> visit == null ? null : readIndex(visit.getPath(), ga));
                if (index != null) {
                    if (generate) {
                        throw new CodeGenException(ga + " is listed in " + PREFIX + "generate-from-dependencies but "
                                + "already ships generated classes (" + GeneratedIndex.RESOURCE + "); remove it from "
                                + "the list, its types are used as they are");
                    }
                    dependencyIndex.putAll(index);
                }
                if (generate) {
                    listedFound.add(ga);
                }
                for (LexiconDoc doc : dependencyDocs(dep, ga, generate)) {
                    String id = doc.id().value();
                    if (docs.containsKey(id)) {
                        LOG.debugf("Lexicon %s from %s is already defined earlier (application or another "
                                + "dependency); that copy is used", id, ga);
                        continue;
                    }
                    docs.put(id, doc);
                    if (generate) {
                        toGenerate.add(doc.id());
                    }
                }
            }
            Set<String> missing = new LinkedHashSet<>(fromDependencies);
            missing.removeAll(listedFound);
            if (!missing.isEmpty()) {
                throw new CodeGenException(PREFIX + "generate-from-dependencies lists " + missing
                        + ", which are not runtime dependencies of the application");
            }

            Set<Nsid> selected = toGenerate.stream().filter(n -> selected(n, include, exclude))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (selected.isEmpty()) {
                return false;
            }
            LexiconSet set = LexiconSet.of(docs.values());
            List<Path> written = new ArrayList<>(
                    Generator.generate(set, selected, dependencyIndex, context.outDir(), prefix));
            if (!written.isEmpty()) {
                written.add(writeReflectionHolder(written, context.outDir()));
            }
            LOG.debugf("atproto-lexicon: generated %d Java files for %s into %s", written.size(), selected,
                    context.outDir());
            return !written.isEmpty();
        } catch (LexiconException | UncheckedIOException e) {
            throw new CodeGenException("Lexicon code generation failed: " + e.getMessage(), e);
        }
    }

    /** Simple name of the generated class that registers the application's Lexicon types for reflection. */
    static final String REFLECTION_HOLDER = "AtprotoLexiconReflection";

    /**
     * Writes {@value #REFLECTION_HOLDER}, annotated {@code @RegisterForReflection(targets = ...)} with every top-level
     * class just generated (records, defs, XRPC types and clients; nested classes included, {@code ignoreNested =
     * false}), into the package of the first one: native images need reflection for Jackson to read and write them.
     * Packs get the same through their {@code index.json} instead.
     */
    static Path writeReflectionHolder(List<Path> generated, Path outDir) {
        List<String> topLevel = generated.stream()
                .map(f -> outDir.toAbsolutePath().relativize(f.toAbsolutePath()).toString().replace('\\', '/'))
                .filter(f -> f.endsWith(".java"))
                .map(f -> f.substring(0, f.length() - ".java".length()).replace('/', '.'))
                .distinct().sorted().toList();
        String first = topLevel.get(0);
        String pkg = first.lastIndexOf('.') < 0 ? "" : first.substring(0, first.lastIndexOf('.'));
        StringBuilder src = new StringBuilder("// Generated by quarkus-atproto-lexicon. Do not edit.\n");
        if (!pkg.isEmpty()) {
            src.append("package ").append(pkg).append(";\n\n");
        }
        src.append("/** Registers the types generated from this application's Lexicons for reflection (native). */\n")
                .append("@io.quarkus.runtime.annotations.RegisterForReflection(ignoreNested = false, targets = {\n");
        for (int i = 0; i < topLevel.size(); i++) {
            src.append("    ").append(topLevel.get(i)).append(".class").append(i + 1 < topLevel.size() ? "," : "")
                    .append('\n');
        }
        src.append("})\nfinal class ").append(REFLECTION_HOLDER).append(" {\n    private ").append(REFLECTION_HOLDER)
                .append("() {\n    }\n}\n");
        Path file = (pkg.isEmpty() ? outDir : outDir.resolve(pkg.replace('.', '/'))).resolve(REFLECTION_HOLDER + ".java");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, src);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
        return file;
    }

    private static List<LexiconDoc> appDocs(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            List<LexiconDoc> out = new ArrayList<>();
            for (Path p : files.filter(f -> f.toString().endsWith(".json") && Files.isRegularFile(f)).sorted()
                    .toList()) {
                out.add(LexiconParser.parse(p));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + dir, e);
        }
    }

    /**
     * The Lexicon documents a dependency ships under {@code META-INF/atproto/lexicons/} (index excluded). A document
     * that does not parse fails the build when the dependency is to be generated, and is skipped with a warning
     * otherwise (it is only read to check references).
     */
    private static List<LexiconDoc> dependencyDocs(ResolvedDependency dep, String ga, boolean generate) {
        List<LexiconDoc> out = new ArrayList<>();
        dep.getContentTree().walkIfContains(DIR, visit -> {
            String name = visit.getResourceName().replace('\\', '/');
            if (!name.endsWith(".json") || name.equals(GeneratedIndex.RESOURCE)
                    || !Files.isRegularFile(visit.getPath())) {
                return;
            }
            String json;
            try {
                json = Files.readString(visit.getPath());
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + name + " in " + ga, e);
            }
            try {
                out.add(LexiconParser.parse(json));
            } catch (LexiconException e) {
                if (generate) {
                    throw new LexiconException(name + " in " + ga + ": " + e.getMessage(), e);
                }
                LOG.warnf("Skipping %s in %s: %s", name, ga, e.getMessage());
            }
        });
        return out;
    }

    private static Map<String, String> readIndex(Path path, String ga) {
        try (InputStream in = Files.newInputStream(path)) {
            return GeneratedIndex.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + GeneratedIndex.RESOURCE + " in " + ga, e);
        }
    }

    private static List<String> list(Config config, String key) {
        return config.getOptionalValues(PREFIX + key, String.class).orElse(List.of()).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    static boolean selected(Nsid nsid, List<String> include, List<String> exclude) {
        String id = nsid.value();
        return (include.isEmpty() || include.stream().anyMatch(g -> glob(g).matcher(id).matches()))
                && exclude.stream().noneMatch(g -> glob(g).matcher(id).matches());
    }

    /** An NSID glob: {@code *} matches any run of characters, everything else is literal. */
    static Pattern glob(String glob) {
        StringBuilder re = new StringBuilder();
        for (String part : glob.split("\\*", -1)) {
            if (!re.isEmpty()) {
                re.append(".*");
            }
            re.append(Pattern.quote(part));
        }
        return Pattern.compile(re.toString());
    }
}
