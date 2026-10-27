package org.sitenetsoft.quarkus.atproto.lexicon;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.sitenetsoft.atproto.syntax.AtUri;
import org.sitenetsoft.atproto.syntax.Cid;
import org.sitenetsoft.atproto.syntax.Datetime;
import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;
import org.sitenetsoft.atproto.syntax.Language;
import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.atproto.syntax.RecordKey;
import org.sitenetsoft.atproto.syntax.Tid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Checks records against their collection's Lexicon (from {@link LexiconRegistry}) and reports every problem found,
 * each with a JSON Pointer path.
 *
 * <p>Rules: the record is an object whose {@code $type} equals the collection; {@code required} properties are
 * present; {@code null} only where {@code nullable}; values have their schema's type; strings obey
 * {@code minLength}/{@code maxLength} (UTF-8 bytes), {@code minGraphemes}/{@code maxGraphemes} (extended grapheme
 * clusters), {@code enum}, {@code const} and {@code format}; integers {@code minimum}/{@code maximum}/{@code enum}/
 * {@code const}; arrays {@code minLength}/{@code maxLength} (items); {@code bytes} is {@code {"$bytes": base64}};
 * {@code cid-link} is {@code {"$link": cid}}; {@code blob} is {@code {"$type": "blob", "ref": {"$link"}, "mimeType",
 * "size"}} within {@code accept} and {@code maxSize}; {@code unknown} is an object; union values carry a
 * {@code $type}, which must name one of the refs in a closed union and may name anything in an open one; refs are
 * followed within the document and across documents. Properties the Lexicon does not declare are allowed.
 * {@code knownValues} are not enforced (they are open by definition).
 *
 * <p>A ref to a Lexicon that is not on the classpath is skipped in {@link ValidationMode#OPTIMISTIC} mode and a
 * problem in {@link ValidationMode#TRUE} mode.
 */
@ApplicationScoped
public class LexiconValidator {
  private static final int MAX_URI_BYTES = 8192;
  private static final int MAX_QUOTED = 64;

  private final LexiconRegistry registry;
  private final ObjectMapper mapper;
  private final ValidationMode mode;

  @Inject
  public LexiconValidator(LexiconRegistry registry, ObjectMapper mapper, LexiconConfig config) {
    this(registry, mapper, config.validation());
  }

  LexiconValidator(LexiconRegistry registry, ObjectMapper mapper, ValidationMode mode) {
    this.registry = registry;
    this.mapper = mapper;
    this.mode = mode;
  }

  /** The configured default mode ({@code quarkus.atproto.lexicon.validation}). */
  public ValidationMode mode() {
    return mode;
  }

  /** Validates in the configured mode. */
  public List<ValidationProblem> validate(Nsid collection, JsonNode record) {
    return validate(collection, record, mode);
  }

  /** Validates a typed record (a generated Lexicon class), converted with the application's {@link ObjectMapper}. */
  public List<ValidationProblem> validate(Nsid collection, Object record) {
    return validate(collection, record, mode);
  }

  public List<ValidationProblem> validate(Nsid collection, Object record, ValidationMode mode) {
    JsonNode tree = record instanceof JsonNode n ? n : mapper.valueToTree(record);
    return validate(collection, tree, mode);
  }

  /** @return the problems found, empty when the record is valid (or was not validated in this mode) */
  public List<ValidationProblem> validate(Nsid collection, JsonNode record, ValidationMode mode) {
    Objects.requireNonNull(collection, "collection");
    Objects.requireNonNull(mode, "mode");
    if (mode == ValidationMode.FALSE) {
      return List.of();
    }
    Run run = new Run(mode);
    Optional<JsonNode> main = registry.def(collection, "main");
    if (main.isEmpty()) {
      if (mode == ValidationMode.TRUE) {
        run.problem("/", "no Lexicon for collection " + collection + " on the classpath (validation mode true)");
      }
      return run.problems;
    }
    if (!"record".equals(main.get().path("type").asText())) {
      run.problem("/", collection + " is not a record Lexicon (main def type " + main.get().path("type").asText()
          + ")");
      return run.problems;
    }
    if (record == null || !record.isObject()) {
      run.problem("/", "a record must be an object");
      return run.problems;
    }
    JsonNode type = record.get("$type");
    if (type == null) {
      run.problem("/$type", "required property is missing (must be " + collection + ")");
    } else if (!type.isTextual() || !normalise(type.asText()).equals(collection.value())) {
      run.problem("/$type", "must be " + collection + ", is " + quote(type));
    }
    run.object(main.get().path("record"), record, "/", collection);
    return List.copyOf(run.problems);
  }

  /** {@code nsid#main} and {@code nsid} name the same def. */
  private static String normalise(String typeId) {
    return typeId.endsWith("#main") ? typeId.substring(0, typeId.length() - 5) : typeId;
  }

  private static String child(String path, String key) {
    String escaped = key.replace("~", "~0").replace("/", "~1");
    return path.equals("/") ? "/" + escaped : path + "/" + escaped;
  }

  private static String quote(JsonNode v) {
    String s = v.toString();
    return s.length() > MAX_QUOTED ? s.substring(0, MAX_QUOTED) + "…" : s;
  }

  private static String quote(String s) {
    return s.length() > MAX_QUOTED ? "\"" + s.substring(0, MAX_QUOTED) + "…\"" : "\"" + s + "\"";
  }

  /** A def address: the document NSID and the def name. */
  private record Target(Nsid nsid, String name) {
    String typeId() {
      return name.equals("main") ? nsid.value() : nsid.value() + "#" + name;
    }

    /** {@code #def}, {@code nsid} or {@code nsid#def} as written in {@code context}; null when malformed. */
    static Target parse(String ref, Nsid context) {
      int hash = ref.indexOf('#');
      String nsid = hash < 0 ? ref : ref.substring(0, hash);
      String name = hash < 0 ? "main" : ref.substring(hash + 1);
      if (name.isEmpty()) {
        return null;
      }
      if (nsid.isEmpty()) {
        return new Target(context, name);
      }
      return Nsid.isValid(nsid) ? new Target(Nsid.parse(nsid), name) : null;
    }
  }

  private final class Run {
    final List<ValidationProblem> problems = new ArrayList<>();
    final ValidationMode mode;
    /**
     * Defs entered through refs (and union members) for the current value, without descending into its children:
     * a def met twice here is a ref-only cycle ({@code #a -> #b -> #a}) in a malformed Lexicon. Reset for each child
     * value, so recursive data types (a node whose children ref the node) still validate.
     */
    Set<String> refChain = new HashSet<>();

    Run(ValidationMode mode) {
      this.mode = mode;
    }

    void problem(String path, String message) {
      problems.add(new ValidationProblem(path, message));
    }

    void value(JsonNode schema, JsonNode v, String path, Nsid ctx) {
      switch (schema.path("type").asText()) {
        case "ref" -> ref(schema.path("ref").asText(), v, path, ctx);
        case "union" -> union(schema, v, path, ctx);
        case "object" -> object(schema, v, path, ctx);
        case "record" -> object(schema.path("record"), v, path, ctx);
        case "string" -> string(schema, v, path);
        case "integer" -> integer(schema, v, path);
        case "boolean" -> bool(schema, v, path);
        case "null" -> {
          if (!v.isNull()) {
            problem(path, "must be null");
          }
        }
        case "bytes" -> bytes(schema, v, path);
        case "cid-link" -> cidLink(v, path);
        case "blob" -> blob(schema, v, path);
        case "array" -> array(schema, v, path, ctx);
        case "unknown" -> {
          if (!v.isObject()) {
            problem(path, "must be an object (Lexicon type unknown), is " + quote(v));
          }
        }
        default -> {
          // token, query, procedure, subscription, permission-set: not data types
        }
      }
    }

    void ref(String ref, JsonNode v, String path, Nsid ctx) {
      Target target = Target.parse(ref, ctx);
      Optional<JsonNode> def = target == null ? Optional.empty() : registry.def(target.nsid(), target.name());
      if (def.isEmpty()) {
        if (target == null || registry.knows(target.nsid()) || mode == ValidationMode.TRUE) {
          problem(path, "cannot resolve Lexicon ref " + ref + " (from " + ctx + ")");
        }
        return;
      }
      String key = target.nsid().value() + "#" + target.name();
      if (!refChain.add(key)) {
        problem(path, "Lexicon ref cycle through " + key + " (malformed Lexicon: refs that never reach a type)");
        return;
      }
      try {
        value(def.get(), v, path, target.nsid());
      } finally {
        refChain.remove(key);
      }
    }

    /** Validates a nested value (property or array item) with a fresh ref chain. */
    void nested(JsonNode schema, JsonNode v, String path, Nsid ctx) {
      Set<String> saved = refChain;
      refChain = new HashSet<>();
      try {
        value(schema, v, path, ctx);
      } finally {
        refChain = saved;
      }
    }

    void union(JsonNode schema, JsonNode v, String path, Nsid ctx) {
      if (!v.isObject()) {
        problem(path, "must be an object with a $type (union), is " + quote(v));
        return;
      }
      JsonNode type = v.get("$type");
      if (type == null || !type.isTextual() || type.asText().isEmpty()) {
        problem(path, "union member must have a string $type");
        return;
      }
      String typeId = normalise(type.asText());
      List<String> members = new ArrayList<>();
      for (JsonNode r : schema.path("refs")) {
        Target target = Target.parse(r.asText(), ctx);
        if (target == null) {
          problem(path, "cannot resolve Lexicon ref " + r.asText() + " (from " + ctx + ")");
          return;
        }
        if (target.typeId().equals(typeId)) {
          ref(r.asText(), v, path, ctx);
          return;
        }
        members.add(target.typeId());
      }
      if (schema.path("closed").asBoolean(false)) {
        problem(path, "$type " + typeId + " is not allowed in this closed union (one of " + members + ")");
      }
      // open union: a $type outside the refs is accepted as is
    }

    void object(JsonNode schema, JsonNode v, String path, Nsid ctx) {
      if (!v.isObject()) {
        problem(path, "must be an object, is " + quote(v));
        return;
      }
      for (JsonNode r : schema.path("required")) {
        if (!v.has(r.asText())) {
          problem(child(path, r.asText()), "required property is missing");
        }
      }
      Set<String> nullable = new HashSet<>();
      schema.path("nullable").forEach(n -> nullable.add(n.asText()));
      for (Map.Entry<String, JsonNode> p : schema.path("properties").properties()) {
        JsonNode pv = v.get(p.getKey());
        if (pv == null) {
          continue;
        }
        String at = child(path, p.getKey());
        if (pv.isNull()) {
          if (!nullable.contains(p.getKey())) {
            problem(at, "must not be null (not nullable)");
          }
          continue;
        }
        nested(p.getValue(), pv, at, ctx);
      }
    }

    void string(JsonNode schema, JsonNode v, String path) {
      if (!v.isTextual()) {
        problem(path, "must be a string, is " + quote(v));
        return;
      }
      String s = v.asText();
      if (schema.has("format")) {
        String format = schema.get("format").asText();
        if (!formatValid(format, s)) {
          problem(path, quote(s) + " is not a valid " + format);
        }
      }
      if (schema.has("maxLength") || schema.has("minLength")) {
        int bytes = Graphemes.utf8Length(s);
        if (schema.has("maxLength") && bytes > schema.get("maxLength").asLong()) {
          problem(path, "is " + bytes + " UTF-8 bytes long, at most " + schema.get("maxLength").asLong()
              + " bytes allowed (maxLength)");
        }
        if (schema.has("minLength") && bytes < schema.get("minLength").asLong()) {
          problem(path, "is " + bytes + " UTF-8 bytes long, at least " + schema.get("minLength").asLong()
              + " bytes required (minLength)");
        }
      }
      // grapheme count <= UTF-16 length, so short strings never need counting against a maximum
      boolean checkMax = schema.has("maxGraphemes") && s.length() > schema.get("maxGraphemes").asLong();
      if (checkMax || schema.has("minGraphemes")) {
        int graphemes = Graphemes.count(s);
        if (checkMax && graphemes > schema.get("maxGraphemes").asLong()) {
          problem(path, "is " + graphemes + " graphemes long, at most " + schema.get("maxGraphemes").asLong()
              + " graphemes allowed (maxGraphemes)");
        }
        if (schema.has("minGraphemes") && graphemes < schema.get("minGraphemes").asLong()) {
          problem(path, "is " + graphemes + " graphemes long, at least " + schema.get("minGraphemes").asLong()
              + " graphemes required (minGraphemes)");
        }
      }
      enumAndConst(schema, v, path);
    }

    void integer(JsonNode schema, JsonNode v, String path) {
      if (!v.isIntegralNumber() || !v.canConvertToLong()) {
        problem(path, "must be an integer, is " + quote(v));
        return;
      }
      long n = v.asLong();
      if (schema.has("minimum") && n < schema.get("minimum").asLong()) {
        problem(path, "is " + n + ", minimum is " + schema.get("minimum").asLong());
      }
      if (schema.has("maximum") && n > schema.get("maximum").asLong()) {
        problem(path, "is " + n + ", maximum is " + schema.get("maximum").asLong());
      }
      enumAndConst(schema, v, path);
    }

    void bool(JsonNode schema, JsonNode v, String path) {
      if (!v.isBoolean()) {
        problem(path, "must be a boolean, is " + quote(v));
        return;
      }
      enumAndConst(schema, v, path);
    }

    void enumAndConst(JsonNode schema, JsonNode v, String path) {
      JsonNode values = schema.get("enum");
      if (values != null && values.isArray()) {
        boolean found = false;
        for (JsonNode e : values) {
          found |= sameScalar(e, v);
        }
        if (!found) {
          problem(path, quote(v) + " must be one of " + values);
        }
      }
      JsonNode constant = schema.get("const");
      if (constant != null && !sameScalar(constant, v)) {
        problem(path, "must be " + constant + ", is " + quote(v));
      }
    }

    boolean sameScalar(JsonNode a, JsonNode b) {
      if (a.isIntegralNumber() && b.isIntegralNumber()) {
        return a.bigIntegerValue().equals(b.bigIntegerValue());
      }
      return a.equals(b);
    }

    void bytes(JsonNode schema, JsonNode v, String path) {
      JsonNode b64 = v.isObject() && v.size() == 1 ? v.get("$bytes") : null;
      if (b64 == null || !b64.isTextual()) {
        problem(path, "must be bytes in the form {\"$bytes\": \"<base64>\"}, is " + quote(v));
        return;
      }
      byte[] decoded;
      try {
        decoded = Base64.getDecoder().decode(b64.asText());
      } catch (IllegalArgumentException e) {
        problem(path, "$bytes is not valid base64");
        return;
      }
      if (schema.has("maxLength") && decoded.length > schema.get("maxLength").asLong()) {
        problem(path, "is " + decoded.length + " bytes long, at most " + schema.get("maxLength").asLong()
            + " bytes allowed (maxLength)");
      }
      if (schema.has("minLength") && decoded.length < schema.get("minLength").asLong()) {
        problem(path, "is " + decoded.length + " bytes long, at least " + schema.get("minLength").asLong()
            + " bytes required (minLength)");
      }
    }

    void cidLink(JsonNode v, String path) {
      JsonNode link = v.isObject() && v.size() == 1 ? v.get("$link") : null;
      if (link == null || !link.isTextual()) {
        problem(path, "must be a cid-link in the form {\"$link\": \"<cid>\"}, is " + quote(v));
      } else if (!Cid.isValid(link.asText())) {
        problem(path, quote(link.asText()) + " is not a valid cid");
      }
    }

    void blob(JsonNode schema, JsonNode v, String path) {
      String form = "must be a blob {\"$type\": \"blob\", \"ref\": {\"$link\": cid}, \"mimeType\", \"size\"}";
      if (!v.isObject() || !"blob".equals(v.path("$type").asText(null))) {
        problem(path, form + ", is " + quote(v));
        return;
      }
      JsonNode ref = v.get("ref");
      JsonNode mimeType = v.get("mimeType");
      JsonNode size = v.get("size");
      if (ref == null || mimeType == null || !mimeType.isTextual() || size == null || !size.isIntegralNumber()
          || !size.canConvertToLong() || size.asLong() < 0) {
        problem(path, form + ", is " + quote(v));
        return;
      }
      cidLink(ref, child(path, "ref"));
      String mime = mimeType.asText();
      JsonNode accept = schema.get("accept");
      if (accept != null && accept.isArray() && !accept.isEmpty()) {
        boolean ok = false;
        for (JsonNode pattern : accept) {
          ok |= mimeMatches(pattern.asText(), mime);
        }
        if (!ok) {
          problem(path, "MIME type " + mime + " is not accepted (accept " + accept + ")");
        }
      }
      if (schema.has("maxSize") && size.asLong() > schema.get("maxSize").asLong()) {
        problem(path, "is " + size.asLong() + " bytes, maxSize is " + schema.get("maxSize").asLong());
      }
    }

    void array(JsonNode schema, JsonNode v, String path, Nsid ctx) {
      if (!v.isArray()) {
        problem(path, "must be an array, is " + quote(v));
        return;
      }
      if (schema.has("maxLength") && v.size() > schema.get("maxLength").asLong()) {
        problem(path, "has " + v.size() + " items, at most " + schema.get("maxLength").asLong()
            + " allowed (maxLength)");
      }
      if (schema.has("minLength") && v.size() < schema.get("minLength").asLong()) {
        problem(path, "has " + v.size() + " items, at least " + schema.get("minLength").asLong()
            + " required (minLength)");
      }
      JsonNode items = schema.get("items");
      if (items == null) {
        return;
      }
      for (int i = 0; i < v.size(); i++) {
        JsonNode item = v.get(i);
        String at = child(path, Integer.toString(i));
        if (item.isNull()) {
          problem(at, "array items must not be null");
        } else {
          nested(items, item, at, ctx);
        }
      }
    }
  }

  static boolean mimeMatches(String pattern, String mime) {
    if (pattern.equals("*/*")) {
      return true;
    }
    if (pattern.endsWith("/*")) {
      return mime.regionMatches(true, 0, pattern, 0, pattern.length() - 1);
    }
    return pattern.equalsIgnoreCase(mime);
  }

  static boolean formatValid(String format, String s) {
    return switch (format) {
      case "at-identifier" -> Did.isValid(s) || Handle.isValidSyntax(s);
      case "at-uri" -> AtUri.isValid(s);
      case "cid" -> Cid.isValid(s);
      case "datetime" -> Datetime.isValid(s);
      case "did" -> Did.isValid(s);
      case "handle" -> Handle.isValidSyntax(s);
      case "nsid" -> Nsid.isValid(s);
      case "tid" -> Tid.isValid(s);
      case "record-key" -> RecordKey.isValid(s);
      case "language" -> Language.isValid(s);
      case "uri" -> isUri(s);
      default -> true; // an unknown format is not a reason to reject data
    };
  }

  /** An absolute URI (has a scheme), at most 8 KiB. */
  static boolean isUri(String s) {
    if (Graphemes.utf8Length(s) > MAX_URI_BYTES) {
      return false;
    }
    try {
      URI uri = new URI(s);
      return uri.isAbsolute() && uri.getScheme().matches("[a-zA-Z][a-zA-Z0-9+.-]*");
    } catch (URISyntaxException e) {
      return false;
    }
  }
}
