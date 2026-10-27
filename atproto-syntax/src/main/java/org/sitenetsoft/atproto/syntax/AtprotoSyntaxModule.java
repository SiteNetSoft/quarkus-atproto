package org.sitenetsoft.atproto.syntax;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.util.function.Function;

/**
 * Jackson module (de)serialising every syntax type as a plain JSON string. Handles (also as AT-URI authorities and AT
 * identifiers) are read with the syntax-only rule ({@link Handle#parseSyntax}), as the Lexicon validator checks the
 * {@code handle} format: a record another app wrote with {@code alice.local} reads back. The disallowed-TLD policy is
 * for user input ({@link Handle#parse}, {@link Handle#normalise}).
 */
public class AtprotoSyntaxModule extends SimpleModule {
  private static final long serialVersionUID = 1L;

  public AtprotoSyntaxModule() {
    super("AtprotoSyntaxModule");
    string(Did.class, Did::parse);
    string(Handle.class, Handle::parseSyntax);
    string(Nsid.class, Nsid::parse);
    string(Tid.class, Tid::parse);
    string(RecordKey.class, RecordKey::parse);
    string(RecordKeyType.class, RecordKeyType::parse);
    string(AtUri.class, AtUri::parseSyntax);
    string(AtIdentifier.class, AtIdentifier::parseSyntax);
    string(Datetime.class, Datetime::parse);
    string(Cid.class, Cid::parse);
    string(Language.class, Language::parse);
  }

  private <T> void string(Class<T> type, Function<String, T> parser) {
    addSerializer(type, new StdSerializer<T>(type) {
      private static final long serialVersionUID = 1L;

      @Override
      public void serialize(T value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeString(value.toString());
      }
    });
    addDeserializer(type, new StdDeserializer<T>(type) {
      private static final long serialVersionUID = 1L;

      @Override
      public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String s = p.getValueAsString();
        if (s == null) {
          return (T) ctxt.handleUnexpectedToken(type, p);
        }
        try {
          return parser.apply(s);
        } catch (SyntaxException e) {
          return (T) ctxt.handleWeirdStringValue(type, s, "%s", e.getMessage());
        }
      }
    });
  }
}
