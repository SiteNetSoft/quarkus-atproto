package org.sitenetsoft.quarkus.atproto.xrpc.deployment;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcClient;
import org.sitenetsoft.quarkus.safefetch.BlockedAddressException;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkus.test.QuarkusUnitTest;

/** The default safe-fetch policy (no test relaxations): a PDS on a private address is refused before any request. */
class XrpcDefaultPolicyTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest().withEmptyApplication();

    @Inject
    XrpcClient xrpc;

    @Test
    void blockedAddressRefused() {
        assertThrows(BlockedAddressException.class, () -> xrpc.query(URI.create("https://127.0.0.1:9/"),
                Nsid.parse("com.atproto.repo.describeRepo"), Map.of("repo", "did:plc:x"), JsonNode.class,
                Optional.empty()));
        assertThrows(BlockedAddressException.class, () -> xrpc.procedure(URI.create("https://10.0.0.1"),
                Nsid.parse("com.atproto.repo.createRecord"), Map.of(), JsonNode.class, Optional.empty()));
    }
}
