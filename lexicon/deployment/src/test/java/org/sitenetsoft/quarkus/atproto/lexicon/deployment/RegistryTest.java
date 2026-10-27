package org.sitenetsoft.quarkus.atproto.lexicon.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.quarkus.atproto.lexicon.LexiconRegistry;

import io.quarkus.test.QuarkusUnitTest;

class RegistryTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addAsResource("lexicons/org/example/note.json",
                    "META-INF/atproto/lexicons/org/example/note.json"));

    @Inject
    LexiconRegistry registry;

    @Test
    void findsPackLexicons() {
        Nsid document = Nsid.parse("site.standard.document");
        assertTrue(registry.knows(document));
        assertTrue(registry.knows(Nsid.parse("site.standard.publication")));
        assertTrue(registry.knows(Nsid.parse("com.atproto.repo.strongRef")));
        assertTrue(registry.knows(Nsid.parse("com.atproto.label.defs")));
        assertEquals("record", registry.def(document, "main").orElseThrow().get("type").asText());
        assertEquals("object", registry.def(document, "contributor").orElseThrow().get("type").asText());
        assertTrue(registry.def(document, "nope").isEmpty());
        assertFalse(registry.knows(Nsid.parse("com.example.unknown")));
        assertTrue(registry.def(Nsid.parse("com.example.unknown"), "main").isEmpty());
    }

    @Test
    void findsApplicationLexicons() {
        assertTrue(registry.knows(Nsid.parse("org.example.note")));
        assertEquals("object", registry.def(Nsid.parse("org.example.note"), "link").orElseThrow().get("type").asText());
    }

    @Test
    void listsWhatItKnows() {
        assertTrue(registry.nsids().contains(Nsid.parse("site.standard.theme.color")));
        assertTrue(registry.nsids().stream().noneMatch(n -> n.value().equals("index")));
    }
}
