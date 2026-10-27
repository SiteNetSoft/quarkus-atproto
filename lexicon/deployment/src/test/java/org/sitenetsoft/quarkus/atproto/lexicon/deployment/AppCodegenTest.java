package org.sitenetsoft.quarkus.atproto.lexicon.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.deployment.CodeGenProvider;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Runs the {@code CodeGenProvider} the way the app's {@code generate-code} phase does: input {@code src/main/lexicons}
 * (here a temp dir holding {@code org/example/note.json}), the Standard.site and core packs as runtime dependencies,
 * then compiles the output against the test classpath.
 */
class AppCodegenTest {

    @TempDir
    Path tmp;

    @Test
    void appLexiconGeneratedAndReferencesPackClass() throws Exception {
        Path in = appLexicons();
        Path out = tmp.resolve("out");
        AtprotoLexiconCodeGen provider = new AtprotoLexiconCodeGen();
        assertEquals("atproto-lexicon", provider.providerId());
        assertEquals("lexicons", provider.inputDirectory());
        assertEquals("json", provider.inputExtension());

        assertTrue(provider.trigger(context(in, out, Map.of(), packs())));

        Path note = out.resolve("org/example/Note.java");
        assertTrue(Files.isRegularFile(note), "Note.java not generated: " + list(out));
        String source = Files.readString(note);
        assertTrue(source.contains("site.standard.Document"), source);
        assertTrue(source.contains("com.atproto.repo.StrongRef"), source);
        // pack types are referenced, never generated again
        assertFalse(Files.exists(out.resolve("site/standard/Document.java")));
        assertFalse(Files.exists(out.resolve("com/atproto/repo/StrongRef.java")));

        ClassLoader loader = compile(list(out));
        Class<?> noteClass = loader.loadClass("org.example.Note");
        assertEquals("site.standard.Document", noteClass.getMethod("about").getReturnType().getName());

        // native: the generated types are registered for reflection by a generated holder class
        Class<?> holder = loader.loadClass("org.example." + AtprotoLexiconCodeGen.REFLECTION_HOLDER);
        io.quarkus.runtime.annotations.RegisterForReflection r = holder
                .getAnnotation(io.quarkus.runtime.annotations.RegisterForReflection.class);
        assertTrue(r != null && !r.ignoreNested(), "holder not annotated: " + holder);
        assertTrue(java.util.Arrays.asList(r.targets()).contains(noteClass), java.util.Arrays.toString(r.targets()));
    }

    @Test
    void includeExcludeAndPackagePrefix() throws Exception {
        Path in = appLexicons();
        Files.writeString(in.resolve("org/example/draft.json"), """
                {"lexicon": 1, "id": "org.example.draft", "defs": {"main": {"type": "record", "key": "tid",
                  "record": {"type": "object", "properties": {"text": {"type": "string"}}}}}}
                """);
        Path out = tmp.resolve("out");
        new AtprotoLexiconCodeGen().trigger(context(in, out, Map.of(
                "quarkus.atproto.lexicon.codegen.exclude", "org.example.dr*",
                "quarkus.atproto.lexicon.codegen.package-prefix", "com.acme.gen"), packs()));
        assertTrue(Files.isRegularFile(out.resolve("com/acme/gen/org/example/Note.java")), list(out)::toString);
        assertFalse(Files.exists(out.resolve("com/acme/gen/org/example/Draft.java")));

        Path out2 = tmp.resolve("out2");
        new AtprotoLexiconCodeGen().trigger(context(in, out2, Map.of(
                "quarkus.atproto.lexicon.codegen.include", "org.example.draft"), packs()));
        assertTrue(Files.isRegularFile(out2.resolve("org/example/Draft.java")));
        assertFalse(Files.exists(out2.resolve("org/example/Note.java")));
    }

    @Test
    void generatesListedDependencyWithoutClasses() throws Exception {
        Path thirdParty = tmp.resolve("third-party");
        Path lex = thirdParty.resolve("META-INF/atproto/lexicons/com/thirdparty/widget.json");
        Files.createDirectories(lex.getParent());
        Files.writeString(lex, """
                {"lexicon": 1, "id": "com.thirdparty.widget", "defs": {"main": {"type": "record", "key": "tid",
                  "record": {"type": "object", "properties": {"name": {"type": "string"}}}}}}
                """);
        Path in = tmp.resolve("lexicons");
        Path appLex = in.resolve("org/example/gadget.json");
        Files.createDirectories(appLex.getParent());
        Files.writeString(appLex, """
                {"lexicon": 1, "id": "org.example.gadget", "defs": {"main": {"type": "record", "key": "tid",
                  "record": {"type": "object", "properties": {"widget": {"type": "ref", "ref": "com.thirdparty.widget"}}}}}}
                """);
        List<ResolvedDependencyBuilder> deps = new java.util.ArrayList<>(packs());
        deps.add(dep("com.thirdparty", "widgets", thirdParty));

        // not listed: the ref has no class anywhere, the build fails naming it
        CodeGenException e = assertThrows(CodeGenException.class,
                () -> new AtprotoLexiconCodeGen().trigger(context(in, tmp.resolve("o1"), Map.of(), deps)));
        assertTrue(e.getMessage().contains("com.thirdparty.widget"), e.getMessage());

        Path out = tmp.resolve("o2");
        new AtprotoLexiconCodeGen().trigger(context(in, out,
                Map.of("quarkus.atproto.lexicon.codegen.generate-from-dependencies", "com.thirdparty:widgets"), deps));
        assertTrue(Files.isRegularFile(out.resolve("com/thirdparty/Widget.java")), list(out)::toString);
        assertTrue(Files.readString(out.resolve("org/example/Gadget.java")).contains("com.thirdparty.Widget"));
        compile(list(out));
    }

    @Test
    void refusesToRegenerateAPackThatShipsClasses() throws Exception {
        Path in = appLexicons();
        CodeGenException e = assertThrows(CodeGenException.class, () -> new AtprotoLexiconCodeGen().trigger(context(in,
                tmp.resolve("out"), Map.of("quarkus.atproto.lexicon.codegen.generate-from-dependencies",
                        "org.sitenetsoft.quarkus:atproto-lexicons-standard-site"),
                packs())));
        assertTrue(e.getMessage().contains("index.json"), e.getMessage());
    }

    @Test
    void shouldRunOnlyWithInput() throws Exception {
        AtprotoLexiconCodeGen provider = new AtprotoLexiconCodeGen();
        var config = new SmallRyeConfigBuilder().build();
        assertFalse(provider.shouldRun(tmp.resolve("missing"), config));
        assertTrue(provider.shouldRun(appLexicons(), config));
        assertTrue(provider.shouldRun(tmp.resolve("missing"), new SmallRyeConfigBuilder().withDefaultValues(
                Map.of("quarkus.atproto.lexicon.codegen.generate-from-dependencies", "a:b")).build()));
    }

    @Test
    void registeredAsService() {
        assertTrue(java.util.ServiceLoader.load(CodeGenProvider.class).stream()
                .anyMatch(p -> p.type() == AtprotoLexiconCodeGen.class));
    }

    // --- helpers ---

    Path appLexicons() throws IOException {
        Path in = tmp.resolve("lexicons");
        Path note = in.resolve("org/example/note.json");
        Files.createDirectories(note.getParent());
        try (InputStream s = AppCodegenTest.class.getResourceAsStream("/lexicons/org/example/note.json")) {
            Files.copy(s, note);
        }
        return in;
    }

    static List<ResolvedDependencyBuilder> packs() throws Exception {
        return List.of(
                dep("org.sitenetsoft.quarkus", "atproto-lexicons-core",
                        root("META-INF/atproto/lexicons/com/atproto/repo/strongRef.json")),
                dep("org.sitenetsoft.quarkus", "atproto-lexicons-standard-site",
                        root("META-INF/atproto/lexicons/site/standard/document.json")));
    }

    static ResolvedDependencyBuilder dep(String group, String artifact, Path path) {
        return ResolvedDependencyBuilder.newInstance().setGroupId(group).setArtifactId(artifact)
                .setVersion("1.0").setResolvedPath(path).setRuntimeCp();
    }

    /** The jar or classes directory holding {@code resource} on the test classpath. */
    static Path root(String resource) throws Exception {
        URL url = AppCodegenTest.class.getClassLoader().getResource(resource);
        if (url.getProtocol().equals("jar")) {
            return Path.of(((JarURLConnection) url.openConnection()).getJarFileURL().toURI());
        }
        Path p = Path.of(url.toURI());
        for (int i = 0; i < resource.split("/").length; i++) {
            p = p.getParent();
        }
        return p;
    }

    CodeGenContext context(Path in, Path out, Map<String, String> config, List<ResolvedDependencyBuilder> deps)
            throws IOException {
        Path appRoot = tmp.resolve("app-classes");
        Files.createDirectories(appRoot);
        ApplicationModel model = new ApplicationModelBuilder()
                .setAppArtifact(ResolvedDependencyBuilder.newInstance().setGroupId("org.example")
                        .setArtifactId("app").setVersion("1.0").setResolvedPath(appRoot))
                .addDependencies(deps)
                .build();
        Files.createDirectories(out);
        return new CodeGenContext(model, out, tmp.resolve("work"), in, false,
                new SmallRyeConfigBuilder().withDefaultValues(config).build(), false);
    }

    static List<Path> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** Compiles {@code sources} against the test classpath (pattern: atproto-lexicon-codegen's InMemoryCompiler). */
    static ClassLoader compile(List<Path> sources) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, ByteArrayOutputStream> classes = new HashMap<>();
        try (StandardJavaFileManager std = javac.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            JavaFileManager fm = new ForwardingJavaFileManager<>(std) {
                @Override
                public JavaFileObject getJavaFileForOutput(Location location, String className,
                        JavaFileObject.Kind kind, FileObject sibling) {
                    return new SimpleJavaFileObject(
                            URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                        @Override
                        public OutputStream openOutputStream() {
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            classes.put(className, bytes);
                            return bytes;
                        }
                    };
                }
            };
            StringWriter log = new StringWriter();
            boolean ok = javac.getTask(log, fm, diagnostics,
                    List.of("-classpath", System.getProperty("java.class.path"), "-proc:none"),
                    null, std.getJavaFileObjectsFromPaths(sources)).call();
            if (!ok) {
                StringBuilder sb = new StringBuilder("compilation failed:\n").append(log);
                diagnostics.getDiagnostics().forEach(d -> sb.append(d).append('\n'));
                throw new AssertionError(sb.toString());
            }
        }
        return new ClassLoader(AppCodegenTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                ByteArrayOutputStream bytes = classes.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] b = bytes.toByteArray();
                return defineClass(name, b, 0, b.length);
            }
        };
    }
}
