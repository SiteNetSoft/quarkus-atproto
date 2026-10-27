package org.sitenetsoft.atproto.lexicon.codegen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.sitenetsoft.atproto.syntax.Nsid;

class NamesTest {
  @Test void packageAndClass() {
    Nsid doc = Nsid.parse("site.standard.document");
    assertEquals("site.standard", JavaNames.packageName(doc, Optional.empty()));
    assertEquals("Document", JavaNames.className(doc));
    Nsid create = Nsid.parse("com.atproto.repo.createRecord");
    assertEquals("com.atproto.repo", JavaNames.packageName(create, Optional.empty()));
    assertEquals("CreateRecord", JavaNames.className(create));
    assertEquals("x.y.site.standard", JavaNames.packageName(doc, Optional.of("x.y")));
    assertEquals("RepoClient", JavaNames.clientClassName(create));
    assertEquals("StandardClient", JavaNames.clientClassName(doc));
  }

  @Test void sanitisesJavaIdentifiers() {
    assertEquals("com.my_app._3d", JavaNames.packageName(Nsid.parse("com.my-app.3d.thing"), Optional.empty()));
    assertEquals("int_.example", JavaNames.packageName(Nsid.parse("int.example.thing"), Optional.empty()));
    assertEquals("default_", JavaNames.memberName("default"));
    assertEquals("hashCode_", JavaNames.memberName("hashCode"));
    assertEquals("displayName", JavaNames.memberName("displayName"));
    assertEquals("SelfLabels", JavaNames.typeName("selfLabels"));
    assertEquals("VALIDATION_STATUS", JavaNames.constantName("validationStatus"));
    assertEquals("NO_UNAUTHENTICATED", JavaNames.constantName("!no-unauthenticated"));
    assertEquals("COM_EXAMPLE_DEFS_REASON_SPAM", JavaNames.constantName("com.example.defs#reasonSpam"));
    assertEquals("_18", JavaNames.constantName("18+"));
  }
}
