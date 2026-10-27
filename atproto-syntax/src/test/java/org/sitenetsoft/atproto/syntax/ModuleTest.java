package org.sitenetsoft.atproto.syntax;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ModuleTest {
  record Doc(Did did, Handle handle, Nsid nsid, Tid tid, RecordKey rkey, AtUri uri, AtIdentifier who,
             Datetime at, Cid cid, Language lang) {}

  @Test void jacksonRoundTrip() throws Exception {
    ObjectMapper m = new ObjectMapper().registerModule(new AtprotoSyntaxModule());
    Doc d = new Doc(Did.parse("did:plc:abc"), Handle.parse("a.test"), Nsid.parse("app.bsky.feed.post"),
        Tid.parse("3jzfcijpj2z2a"), RecordKey.parse("self"), AtUri.parse("at://did:plc:abc/app.bsky.feed.post/x"),
        Handle.parse("b.test"), Datetime.parse("1985-04-12T23:20:50.123000Z"),
        Cid.parse("bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"), Language.parse("en-GB"));
    String json = m.writeValueAsString(d);
    assertTrue(json.contains("\"did\":\"did:plc:abc\""), json);
    assertTrue(json.contains("\"who\":\"b.test\""), json);
    assertEquals(d, m.readValue(json, Doc.class));
  }
  @Test void badValueFails() {
    ObjectMapper m = new ObjectMapper().registerModule(new AtprotoSyntaxModule());
    assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class, () -> m.readValue("\"nope\"", Did.class));
  }
}
