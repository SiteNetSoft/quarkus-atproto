package org.sitenetsoft.quarkus.atproto.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sitenetsoft.atproto.syntax.Nsid;

class ScopeRulesTest {

  static final Nsid DOC = Nsid.parse("site.standard.document");
  static final Nsid PUB = Nsid.parse("site.standard.publication");

  @Test
  void repoActions() {
    ScopeRules r = ScopeRules.parse("atproto repo:site.standard.document?action=create&action=delete");
    assertTrue(r.hasAtproto());
    assertTrue(r.allowsRepo(DOC, "create"));
    assertTrue(r.allowsRepo(DOC, "delete"));
    assertFalse(r.allowsRepo(DOC, "update"));
    assertFalse(r.allowsRepo(PUB, "create"));
    ScopeRules all = ScopeRules.parse("atproto repo:site.standard.document");
    assertTrue(all.allowsRepo(DOC, "update"));
    assertTrue(ScopeRules.parse("atproto repo:*?action=update").allowsRepo(PUB, "update"));
    assertFalse(ScopeRules.parse("atproto repo:*?action=update").allowsRepo(PUB, "create"));
    ScopeRules query = ScopeRules.parse("atproto repo?collection=site.standard.document&collection=site.standard.publication");
    assertTrue(query.allowsRepo(PUB, "delete"));
  }

  @Test
  void blobs() {
    assertTrue(ScopeRules.parse("atproto blob:image/*").allowsBlob("image/png"));
    assertFalse(ScopeRules.parse("atproto blob:image/*").allowsBlob("video/mp4"));
    assertTrue(ScopeRules.parse("atproto blob:*/*").allowsBlob("video/mp4"));
    assertTrue(ScopeRules.parse("atproto blob:image/png").allowsBlob("image/png"));
    assertFalse(ScopeRules.parse("atproto blob:image/png").allowsBlob("image/jpeg"));
    assertTrue(ScopeRules.parse("atproto blob?accept=video/*&accept=image/png").allowsBlob("video/mp4"));
  }

  @Test
  void transitionGenericAllowsEverything() {
    ScopeRules r = ScopeRules.parse("atproto transition:generic");
    assertTrue(r.allowsRepo(PUB, "update"));
    assertTrue(r.allowsBlob("application/pdf"));
    assertFalse(ScopeRules.parse("atproto").allowsRepo(PUB, "create"));
    assertFalse(ScopeRules.parse("transition:generic").hasAtproto());
  }

  @Test
  void rejectsIncludeAndJunk() {
    assertThrows(ScopeRules.InvalidScopeException.class, () -> ScopeRules.parse("atproto include:site.standard.authFull"));
    assertThrows(ScopeRules.InvalidScopeException.class, () -> ScopeRules.parse("atproto repo:not an nsid"));
    assertThrows(ScopeRules.InvalidScopeException.class, () -> ScopeRules.parse("atproto repo:a.b.c?action=read"));
    assertThrows(ScopeRules.InvalidScopeException.class, () -> ScopeRules.parse("atproto blob:png"));
    assertThrows(ScopeRules.InvalidScopeException.class, () -> ScopeRules.parse("atproto whatever"));
    assertThrows(ScopeRules.InvalidScopeException.class, () -> ScopeRules.parse(""));
    ScopeRules.parse("atproto account:email rpc:app.bsky.feed.getFeed?aud=* identity:handle transition:email");
  }
}
