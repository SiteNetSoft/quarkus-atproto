package org.sitenetsoft.quarkus.atproto.lexicon.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.quarkus.atproto.lexicon.LexiconValidator;
import org.sitenetsoft.quarkus.atproto.lexicon.ValidationMode;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.QuarkusUnitTest;

/** {@code quarkus.atproto.lexicon.validation=true}: records of an unknown collection are rejected. */
class ValidationModeConfigTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.atproto.lexicon.validation", "true")
            // the build-time codegen root shares the prefix; setting it must not upset the runtime root
            .overrideConfigKey("quarkus.atproto.lexicon.codegen.package-prefix", "org.example.gen");

    @Inject
    LexiconValidator validator;

    @Inject
    ObjectMapper mapper;

    @Test
    void unknownCollectionFailsWhenConfiguredTrue() {
        assertEquals(ValidationMode.TRUE, validator.mode());
        assertFalse(validator.validate(Nsid.parse("com.example.unknown"),
                mapper.createObjectNode().put("$type", "com.example.unknown")).isEmpty());
    }
}
