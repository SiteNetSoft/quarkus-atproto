package org.sitenetsoft.quarkus.atproto.lexicon.deployment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.model.ApplicationModelBuilder;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.bootstrap.workspace.WorkspaceModuleId;
import io.quarkus.maven.dependency.ResolvedDependencyBuilder;

/**
 * The application's own {@code src/main/lexicons} become {@code META-INF/atproto/lexicons/<nsid path>.json} resources
 * (the only way the validator learns the app's record types when they are not also shipped as resources).
 */
class AppSourceLexiconsTest {

    @TempDir
    Path moduleDir;

    @Test
    void appLexiconsBecomeResourcesAtTheirNsidPath() throws IOException {
        Path lexicons = moduleDir.resolve("src/main/lexicons");
        // the file's location under src/main/lexicons does not matter; the resource name comes from its id
        Path note = lexicons.resolve("anywhere/note.json");
        Files.createDirectories(note.getParent());
        byte[] content;
        try (InputStream in = AppSourceLexiconsTest.class.getResourceAsStream("/lexicons/org/example/note.json")) {
            content = in.readAllBytes();
        }
        Files.write(note, content);
        Files.writeString(lexicons.resolve("broken.json"), "{\"lexicon\": 1, \"id\": \"not an nsid\", \"defs\": {}}");
        Files.writeString(lexicons.resolve("readme.txt"), "not a Lexicon");

        Map<String, byte[]> resources = AtprotoLexiconProcessor.appSourceLexicons(model(true));

        assertEquals(Set.of("META-INF/atproto/lexicons/org/example/note.json"), resources.keySet());
        assertArrayEquals(content, resources.get("META-INF/atproto/lexicons/org/example/note.json"));
    }

    @Test
    void noLexiconDirectory() {
        assertTrue(AtprotoLexiconProcessor.appSourceLexicons(model(true)).isEmpty());
    }

    @Test
    void noWorkspaceModule() {
        // logged at WARN; nothing registered
        assertTrue(AtprotoLexiconProcessor.appSourceLexicons(model(false)).isEmpty());
    }

    ApplicationModel model(boolean withWorkspace) {
        ResolvedDependencyBuilder app = ResolvedDependencyBuilder.newInstance().setGroupId("org.example")
                .setArtifactId("app").setVersion("1.0").setResolvedPath(moduleDir.resolve("target/classes"));
        if (withWorkspace) {
            app.setWorkspaceModule(WorkspaceModule.builder()
                    .setModuleId(WorkspaceModuleId.of("org.example", "app", "1.0"))
                    .setModuleDir(moduleDir).setBuildDir(moduleDir.resolve("target")).build());
        }
        return new ApplicationModelBuilder().setAppArtifact(app).build();
    }
}
