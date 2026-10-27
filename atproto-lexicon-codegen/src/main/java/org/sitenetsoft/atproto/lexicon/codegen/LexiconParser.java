package org.sitenetsoft.atproto.lexicon.codegen;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.sitenetsoft.atproto.lexicon.codegen.model.*;
import org.sitenetsoft.atproto.syntax.Nsid;

/**
 * Parses Lexicon JSON (language version 1) into the {@link LexiconDoc} model and enforces the structural rules of the
 * Lexicon spec: primary types ({@code record}, {@code query}, {@code procedure}, {@code subscription},
 * {@code permission-set}) only as {@code main}, {@code token} only as a named def, {@code params} properties limited
 * to {@code boolean}/{@code integer}/{@code string}/{@code unknown} and arrays of those, XRPC body schemas limited to
 * {@code object}/{@code ref}/{@code union}. Cross-file checks live in {@link LexiconSet}.
 */
public final class LexiconParser {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern DEF_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
  private static final Set<String> PRIMARY = Set.of("record", "query", "procedure", "subscription", "permission-set");
  private static final Set<String> PARAM_TYPES = Set.of("boolean", "integer", "string", "unknown");

  private enum Where { MAIN, DEF, FIELD, ITEMS, BODY, PARAM, PARAM_ITEMS }

  private LexiconParser() {
  }

  public static LexiconDoc parse(Path file) {
    try {
      return parse(Files.readString(file), file.toString());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }

  public static LexiconDoc parse(String json) {
    return parse(json, "<string>");
  }

  private static LexiconDoc parse(String json, String source) {
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      throw new LexiconException(source + ": not valid JSON: " + e.getOriginalMessage(), e);
    }
    if (root == null || !root.isObject()) {
      throw new LexiconException(source + ": a Lexicon document must be a JSON object");
    }
    JsonNode version = root.get("lexicon");
    if (version == null || !version.isInt() || version.intValue() != 1) {
      throw new LexiconException(source + ": unsupported \"lexicon\" version " + version + " (expected 1)");
    }
    JsonNode idNode = root.get("id");
    if (idNode == null || !idNode.isTextual() || !Nsid.isValid(idNode.textValue())) {
      throw new LexiconException(source + ": missing or invalid \"id\" " + idNode);
    }
    Nsid id = Nsid.parse(idNode.textValue());
    JsonNode defsNode = root.get("defs");
    if (defsNode == null || !defsNode.isObject() || defsNode.isEmpty()) {
      throw new LexiconException(id + ": \"defs\" must be a non-empty object");
    }
    Map<String, LexDef> defs = new LinkedHashMap<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = defsNode.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      String name = e.getKey();
      String path = id + "#" + name;
      if (!DEF_NAME.matcher(name).matches()) {
        throw new LexiconException(path + ": invalid def name");
      }
      defs.put(name, def(e.getValue(), path, name.equals("main") ? Where.MAIN : Where.DEF));
    }
    return new LexiconDoc(1, id, text(root, "description"), defs);
  }

  private static LexDef def(JsonNode n, String path, Where where) {
    if (n == null || !n.isObject()) {
      throw new LexiconException(path + ": a definition must be a JSON object");
    }
    String type = text(n, "type");
    if (type == null) {
      throw new LexiconException(path + ": missing \"type\"");
    }
    if (PRIMARY.contains(type) && where != Where.MAIN) {
      throw new LexiconException(path + ": type \"" + type + "\" is only allowed as the main def");
    }
    if (type.equals("token") && where != Where.MAIN && where != Where.DEF) {
      throw new LexiconException(path + ": type \"token\" is only allowed as a named def");
    }
    if (where == Where.BODY && !Set.of("object", "ref", "union").contains(type)) {
      throw new LexiconException(path + ": a body schema must be an object, ref or union, not \"" + type + "\"");
    }
    if (where == Where.PARAM && !PARAM_TYPES.contains(type) && !type.equals("array")) {
      throw new LexiconException(path + ": type \"" + type + "\" is not allowed in params");
    }
    if (where == Where.PARAM_ITEMS && !PARAM_TYPES.contains(type)) {
      throw new LexiconException(path + ": type \"" + type + "\" is not allowed as a params array item");
    }
    String d = text(n, "description");
    return switch (type) {
      case "record" -> {
        JsonNode rec = n.get("record");
        if (rec == null || !"object".equals(text(rec, "type"))) {
          throw new LexiconException(path + ": a record needs a \"record\" of type object");
        }
        String key = text(n, "key");
        if (key == null) {
          throw new LexiconException(path + ": a record needs a \"key\"");
        }
        yield new LexRecord(d, key, object(rec, path + ".record"));
      }
      case "object" -> object(n, path);
      case "query" -> new LexQuery(d, params(n.get("parameters"), path + ".parameters"),
          body(n.get("output"), path + ".output"), errors(n.get("errors"), path));
      case "procedure" -> new LexProcedure(d, params(n.get("parameters"), path + ".parameters"),
          body(n.get("input"), path + ".input"), body(n.get("output"), path + ".output"), errors(n.get("errors"), path));
      case "subscription" -> {
        JsonNode message = n.get("message");
        LexDef schema = message == null || message.get("schema") == null ? null
            : def(message.get("schema"), path + ".message.schema", Where.FIELD);
        yield new LexSubscription(d, params(n.get("parameters"), path + ".parameters"), schema);
      }
      case "permission-set" -> new LexPermissionSet(d, n);
      case "params" -> throw new LexiconException(path + ": \"params\" is only allowed as \"parameters\"");
      case "token" -> new LexToken(d);
      case "string" -> new LexString(d, text(n, "format"), strings(n, "knownValues", path), strings(n, "enum", path),
          text(n, "default"), text(n, "const"), integer(n, "minLength"), integer(n, "maxLength"),
          integer(n, "minGraphemes"), integer(n, "maxGraphemes"));
      case "integer" -> new LexInteger(d, longValue(n, "minimum"), longValue(n, "maximum"), longs(n, "enum", path),
          longValue(n, "default"), longValue(n, "const"));
      case "boolean" -> new LexBoolean(d, bool(n, "default"), bool(n, "const"));
      case "bytes" -> new LexBytes(d, integer(n, "minLength"), integer(n, "maxLength"));
      case "cid-link" -> new LexCidLink(d);
      case "blob" -> new LexBlob(d, strings(n, "accept", path), longValue(n, "maxSize"));
      case "array" -> {
        JsonNode items = n.get("items");
        if (items == null) {
          throw new LexiconException(path + ": an array needs \"items\"");
        }
        Where itemWhere = where == Where.PARAM ? Where.PARAM_ITEMS : Where.ITEMS;
        yield new LexArray(d, def(items, path + ".items", itemWhere), integer(n, "minLength"),
            integer(n, "maxLength"));
      }
      case "ref" -> {
        String ref = text(n, "ref");
        if (ref == null || ref.isEmpty()) {
          throw new LexiconException(path + ": a ref needs a \"ref\"");
        }
        yield new LexRef(d, ref);
      }
      case "union" -> {
        if (!n.has("refs")) {
          throw new LexiconException(path + ": a union needs \"refs\"");
        }
        JsonNode closed = n.get("closed");
        yield new LexUnion(d, strings(n, "refs", path), closed != null && closed.asBoolean());
      }
      case "unknown" -> new LexUnknown(d);
      default -> throw new LexiconException(path + ": unknown type \"" + type + "\"");
    };
  }

  private static LexObject object(JsonNode n, String path) {
    Map<String, LexDef> props = properties(n, path, Where.FIELD);
    return new LexObject(text(n, "description"), declared(n, "required", path, props),
        declared(n, "nullable", path, props), props);
  }

  private static LexParams params(JsonNode n, String path) {
    if (n == null) {
      return null;
    }
    if (!"params".equals(text(n, "type"))) {
      throw new LexiconException(path + ": \"parameters\" must have type \"params\"");
    }
    Map<String, LexDef> props = properties(n, path, Where.PARAM);
    return new LexParams(text(n, "description"), declared(n, "required", path, props), props);
  }

  /** {@code required}/{@code nullable}: every name must be a declared property. */
  private static List<String> declared(JsonNode n, String field, String path, Map<String, LexDef> props) {
    List<String> names = strings(n, field, path);
    for (String name : names) {
      if (!props.containsKey(name)) {
        throw new LexiconException(path + ": \"" + field + "\" names \"" + name + "\", which is not in \"properties\"");
      }
    }
    return names;
  }

  private static Map<String, LexDef> properties(JsonNode n, String path, Where where) {
    Map<String, LexDef> props = new LinkedHashMap<>();
    JsonNode p = n.get("properties");
    if (p == null) {
      return props;
    }
    if (!p.isObject()) {
      throw new LexiconException(path + ": \"properties\" must be an object");
    }
    for (Iterator<Map.Entry<String, JsonNode>> it = p.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      props.put(e.getKey(), def(e.getValue(), path + ".properties." + e.getKey(), where));
    }
    return props;
  }

  private static LexBody body(JsonNode n, String path) {
    if (n == null) {
      return null;
    }
    String encoding = text(n, "encoding");
    if (encoding == null) {
      throw new LexiconException(path + ": missing \"encoding\"");
    }
    JsonNode schema = n.get("schema");
    return new LexBody(text(n, "description"), encoding,
        schema == null ? null : def(schema, path + ".schema", Where.BODY));
  }

  private static List<LexError> errors(JsonNode n, String path) {
    List<LexError> out = new ArrayList<>();
    if (n == null) {
      return out;
    }
    for (JsonNode e : n) {
      String name = text(e, "name");
      if (name == null) {
        throw new LexiconException(path + ": an error needs a \"name\"");
      }
      out.add(new LexError(name, text(e, "description")));
    }
    return out;
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v != null && v.isTextual() ? v.textValue() : null;
  }

  private static Integer integer(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v != null && v.canConvertToInt() ? v.intValue() : null;
  }

  private static Long longValue(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v != null && v.isIntegralNumber() ? v.longValue() : null;
  }

  private static Boolean bool(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v != null && v.isBoolean() ? v.booleanValue() : null;
  }

  private static List<String> strings(JsonNode n, String field, String path) {
    List<String> out = new ArrayList<>();
    JsonNode v = n.get(field);
    if (v == null) {
      return out;
    }
    if (!v.isArray()) {
      throw new LexiconException(path + ": \"" + field + "\" must be an array of strings");
    }
    for (JsonNode s : v) {
      if (!s.isTextual()) {
        throw new LexiconException(path + ": \"" + field + "\" must be an array of strings");
      }
      out.add(s.textValue());
    }
    return out;
  }

  private static List<Long> longs(JsonNode n, String field, String path) {
    List<Long> out = new ArrayList<>();
    JsonNode v = n.get(field);
    if (v == null) {
      return out;
    }
    for (JsonNode s : v) {
      if (!s.isIntegralNumber()) {
        throw new LexiconException(path + ": \"" + field + "\" must be an array of integers");
      }
      out.add(s.longValue());
    }
    return out;
  }
}
