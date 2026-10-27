package org.sitenetsoft.quarkus.atproto.oauth.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.atproto.syntax.Nsid;

class ScopeCheckTest {

    static final String DOC = "site.standard.document";
    static final String PUB = "site.standard.publication";

    @Test
    void repoScopes() {
        String granted = "atproto repo:site.standard.document?action=create&action=update";
        assertTrue(ScopeCheck.allows(granted, "repo", DOC, Optional.of("create")));
        assertTrue(ScopeCheck.allows(granted, "repo", DOC, Optional.of("update")));
        assertFalse(ScopeCheck.allows(granted, "repo", DOC, Optional.of("delete")));
        assertFalse(ScopeCheck.allows(granted, "repo", PUB, Optional.of("create")));
        assertTrue(ScopeCheck.allows(granted, "repo", DOC, Optional.empty()));
        // no action: all actions
        assertTrue(ScopeCheck.allows("atproto repo:site.standard.document", "repo", DOC, Optional.of("delete")));
        // wildcard and the query form, percent-encoded
        assertTrue(ScopeCheck.allows("atproto repo:*?action=create", "repo", PUB, Optional.of("create")));
        assertFalse(ScopeCheck.allows("atproto repo:*?action=create", "repo", PUB, Optional.of("delete")));
        String query = "atproto repo?collection=site.standard.document&collection=site.standard.publication&action=delete";
        assertTrue(ScopeCheck.allows(query, "repo", PUB, Optional.of("delete")));
        assertFalse(ScopeCheck.allows(query, "repo", PUB, Optional.of("create")));
        // atproto alone grants no writes
        assertFalse(ScopeCheck.allows("atproto", "repo", DOC, Optional.of("create")));
    }

    @Test
    void blobScopes() {
        assertTrue(ScopeCheck.allows("atproto blob:image/*", "blob", "image/png", Optional.empty()));
        assertTrue(ScopeCheck.allows("atproto blob:image/*", "blob", "IMAGE/PNG; charset=x", Optional.empty()));
        assertFalse(ScopeCheck.allows("atproto blob:image/*", "blob", "video/mp4", Optional.empty()));
        assertTrue(ScopeCheck.allows("atproto blob:*/*", "blob", "video/mp4", Optional.empty()));
        assertTrue(ScopeCheck.allows("atproto blob?accept=video/mp4&accept=image/png", "blob", "image/png",
                Optional.empty()));
        assertFalse(ScopeCheck.allows("atproto blob:image/png", "blob", "image/jpeg", Optional.empty()));
    }

    @Test
    void transitionGenericAllowsAllRepoAndBlob() {
        String g = "atproto transition:generic";
        assertTrue(ScopeCheck.allows(g, "repo", PUB, Optional.of("delete")));
        assertTrue(ScopeCheck.allows(g, "blob", "video/mp4", Optional.empty()));
        assertFalse(ScopeCheck.allows("atproto transition:chat.bsky", "repo", PUB, Optional.of("create")));
    }

    @Test
    void unknownOrMalformedScopesGrantNothing() {
        assertFalse(ScopeCheck.allows(null, "repo", DOC, Optional.of("create")));
        assertFalse(ScopeCheck.allows("", "repo", DOC, Optional.of("create")));
        assertFalse(ScopeCheck.allows("atproto repo:", "repo", DOC, Optional.of("create")));
        assertFalse(ScopeCheck.allows("atproto repo:site.standard.document?action", "repo", DOC, Optional.of("create")));
        assertFalse(ScopeCheck.allows("atproto include:site.standard.authFull", "repo", DOC, Optional.of("create")));
        assertThrows(IllegalArgumentException.class, () -> ScopeCheck.allows("atproto", "rpc", DOC, Optional.empty()));
    }

    @Test
    void writeGuardRefusesWithScopeException() {
        ScopeWriteGuard guard = new ScopeWriteGuard("atproto repo:site.standard.document?action=create");
        guard.check(Nsid.parse(DOC), "create", Optional.empty());
        AtprotoScopeException e = assertThrows(AtprotoScopeException.class,
                () -> guard.check(Nsid.parse(PUB), "create", Optional.empty()));
        assertEquals(Nsid.parse(PUB), e.collection());
        assertEquals("create", e.action());
        assertTrue(e.getMessage().contains(PUB), e.getMessage());
        // a put may create the record: it needs create and update
        assertThrows(AtprotoScopeException.class, () -> guard.check(Nsid.parse(DOC), "update", Optional.empty()));
        assertThrows(AtprotoScopeException.class, () -> guard.check(Nsid.parse("com.atproto.repo.uploadBlob"), "create",
                Optional.of("image/png")));
        new ScopeWriteGuard("atproto blob:image/*").check(Nsid.parse("com.atproto.repo.uploadBlob"), "create",
                Optional.of("image/png"));
    }
}
