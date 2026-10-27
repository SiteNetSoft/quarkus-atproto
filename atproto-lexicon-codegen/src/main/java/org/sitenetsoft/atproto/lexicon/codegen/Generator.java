package org.sitenetsoft.atproto.lexicon.codegen;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializable;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.lang.model.element.Modifier;
import org.sitenetsoft.atproto.lexicon.codegen.LexiconSet.DefKey;
import org.sitenetsoft.atproto.lexicon.codegen.model.*;
import org.sitenetsoft.atproto.syntax.Nsid;

/**
 * Generates Java sources from Lexicon documents with JavaPoet.
 *
 * <p>One file per generated NSID ({@link JavaNames} decides package and class). A {@code record}/{@code object} main
 * def becomes a Java {@code record}; query/procedure/def-only documents become a {@code final class} holder. Other
 * defs nest inside it. Query and procedure documents of one namespace share a generated XRPC client class.
 *
 * <p>Optional properties are plain nullable types, including {@code nullable} ones: absent and explicit JSON
 * {@code null} both read as Java {@code null}, and Java {@code null} is omitted on write. A typed record therefore
 * cannot send an explicit {@code null} (e.g. {@code com.atproto.repo.putRecord}'s {@code swapRecord} "must not
 * exist"); callers needing that use the generic XRPC client with a {@code JsonNode} body.
 *
 * <p>References to NSIDs outside {@code toGenerate} resolve to the classes already generated for them, read from a
 * dependency {@link GeneratedIndex}; a reference neither generated here nor indexed fails with a message naming it.
 */
public final class Generator {
  private static final System.Logger LOG = System.getLogger(Generator.class.getName());

  static final ClassName ATPROTO_RECORD = ClassName.get("org.sitenetsoft.quarkus.atproto.lexicon", "AtprotoRecord");
  static final ClassName BLOB_REF = ClassName.get("org.sitenetsoft.quarkus.atproto.lexicon", "BlobRef");
  static final ClassName CID_LINK = ClassName.get("org.sitenetsoft.quarkus.atproto.lexicon", "CidLink");
  static final ClassName XRPC_CLIENT = ClassName.get("org.sitenetsoft.quarkus.atproto.xrpc.api", "ReactiveXrpcClient");
  static final ClassName XRPC_AUTH = ClassName.get("org.sitenetsoft.quarkus.atproto.xrpc.api", "XrpcAuth");
  static final ClassName UNI = ClassName.get("io.smallrye.mutiny", "Uni");
  private static final String SYNTAX = "org.sitenetsoft.atproto.syntax";
  private static final Map<String, ClassName> FORMATS = Map.ofEntries(
      Map.entry("did", ClassName.get(SYNTAX, "Did")),
      Map.entry("handle", ClassName.get(SYNTAX, "Handle")),
      Map.entry("at-uri", ClassName.get(SYNTAX, "AtUri")),
      Map.entry("nsid", ClassName.get(SYNTAX, "Nsid")),
      Map.entry("tid", ClassName.get(SYNTAX, "Tid")),
      Map.entry("record-key", ClassName.get(SYNTAX, "RecordKey")),
      Map.entry("datetime", ClassName.get(SYNTAX, "Datetime")),
      Map.entry("cid", ClassName.get(SYNTAX, "Cid")),
      Map.entry("language", ClassName.get(SYNTAX, "Language")),
      Map.entry("at-identifier", ClassName.get(SYNTAX, "AtIdentifier")),
      Map.entry("uri", ClassName.get(URI.class)));
  private static final ClassName NSID_CLASS = ClassName.get(Nsid.class);
  private static final ClassName STRING = ClassName.get(String.class);
  private static final ClassName JSON_NODE = ClassName.get(JsonNode.class);
  /** Lexicon {@code bytes}: a runtime value type owning its {@code {"$bytes": base64}} wire form. */
  static final ClassName LEX_BYTES = ClassName.get("org.sitenetsoft.quarkus.atproto.lexicon", "Bytes");
  /** Raw XRPC bodies (blob upload input, non-JSON output). */
  private static final TypeName RAW_BYTES = ArrayTypeName.of(TypeName.BYTE);

  private final LexiconSet set;
  private final Set<String> toGenerate;
  private final Map<String, String> dependencyIndex;
  private final Optional<String> prefix;

  private Generator(LexiconSet set, Set<Nsid> toGenerate, Map<String, String> dependencyIndex,
                    Optional<String> prefix) {
    this.set = set;
    this.toGenerate = new TreeSet<>();
    for (Nsid n : toGenerate) {
      if (set.doc(n).isEmpty()) {
        throw new LexiconException(n + ": asked to generate an NSID that is not in the Lexicon set");
      }
      this.toGenerate.add(n.value());
    }
    this.dependencyIndex = Map.copyOf(dependencyIndex);
    this.prefix = prefix.filter(p -> !p.isBlank());
  }

  /**
   * Generates the sources for {@code toGenerate} into {@code outDir} (package directories created as needed).
   *
   * @param dependencyIndex {@code nsid#def} → binary class name for defs generated elsewhere (dependency packs)
   * @return the written files, in generation order
   */
  public static List<Path> generate(LexiconSet set, Set<Nsid> toGenerate, Map<String, String> dependencyIndex,
                                    Path outDir, Optional<String> packagePrefix) {
    Generator g = new Generator(set, toGenerate, dependencyIndex, packagePrefix);
    List<Path> written = new ArrayList<>();
    for (JavaFile f : g.files()) {
      try {
        written.add(f.writeToPath(outDir));
      } catch (IOException e) {
        throw new UncheckedIOException("cannot write " + f.packageName() + "." + f.typeSpec().name(), e);
      }
    }
    return written;
  }

  /** The {@link GeneratedIndex} entries for {@code toGenerate}: {@code nsid#def} → binary class name. */
  static Map<String, String> index(LexiconSet set, Set<Nsid> toGenerate, Optional<String> packagePrefix) {
    Generator g = new Generator(set, toGenerate, Map.of(), packagePrefix);
    Map<String, String> out = new TreeMap<>();
    for (String nsid : g.toGenerate) {
      LexiconDoc doc = set.doc(Nsid.parse(nsid)).orElseThrow();
      if (g.skipped(doc)) {
        continue;
      }
      doc.defs().forEach((name, def) -> {
        DefKey key = new DefKey(doc.id(), name);
        TypeName t = switch (def) {
          case LexRecord r -> g.classOf(key);
          case LexObject o -> g.classOf(key);
          case LexUnion u -> g.classOf(key);
          case LexString s -> stringType(s);
          case LexInteger i -> ClassName.get(Long.class);
          case LexBoolean b -> ClassName.get(Boolean.class);
          case LexBlob b -> BLOB_REF;
          case LexCidLink c -> CID_LINK;
          case LexBytes b -> LEX_BYTES;
          case LexUnknown u -> JSON_NODE;
          default -> null;
        };
        if (t instanceof ClassName cn) {
          out.put(key.key(), cn.reflectionName());
        }
      });
    }
    return out;
  }

  // ---------------------------------------------------------------------------------------------------------------
  // naming

  private ClassName topClass(Nsid nsid) {
    return ClassName.get(JavaNames.packageName(nsid, prefix), JavaNames.className(nsid));
  }

  /** The class generated for a non-structural def (record, object, union) of a generated NSID. */
  private ClassName classOf(DefKey key) {
    ClassName top = topClass(key.nsid());
    return key.name().equals("main") ? top : top.nestedClass(nestedDefName(top, key.name()));
  }

  private static String nestedDefName(ClassName top, String def) {
    String n = JavaNames.typeName(def);
    return n.equals(top.simpleName()) ? n + "Def" : n;
  }

  private boolean skipped(LexiconDoc doc) {
    LexDef main = doc.defs().get("main");
    return (main instanceof LexSubscription || main instanceof LexPermissionSet) && doc.defs().size() == 1;
  }

  // ---------------------------------------------------------------------------------------------------------------
  // type resolution

  private static TypeName stringType(LexString s) {
    return s.format() == null ? STRING : FORMATS.getOrDefault(s.format(), STRING);
  }

  /** The Java type a {@code ref} written in {@code from} denotes. */
  private TypeName refType(String ref, Nsid from, String where) {
    DefKey key = LexiconSet.parseRef(ref, from);
    Optional<LexiconSet.Resolved> resolved = set.resolve(key);
    if (resolved.isPresent()) {
      LexDef def = resolved.get().def();
      switch (def) {
        case LexRecord r -> {
          return classRef(key, where);
        }
        case LexObject o -> {
          return classRef(key, where);
        }
        case LexUnion u -> {
          return classRef(key, where);
        }
        case LexString s -> {
          return stringType(s);
        }
        case LexInteger i -> {
          return ClassName.get(Long.class);
        }
        case LexBoolean b -> {
          return ClassName.get(Boolean.class);
        }
        case LexBytes b -> {
          return LEX_BYTES;
        }
        case LexCidLink c -> {
          return CID_LINK;
        }
        case LexBlob b -> {
          return BLOB_REF;
        }
        case LexUnknown u -> {
          return JSON_NODE;
        }
        case LexArray a when a.items() instanceof LexRef || isScalar(a.items()) -> {
          return ParameterizedTypeName.get(ClassName.get(List.class), boxed(a.items() instanceof LexRef r
              ? refType(r.ref(), key.nsid(), where) : scalarType(a.items())));
        }
        default -> throw new LexiconException(where + ": ref " + key + " points to a " + def.type()
            + ", which cannot be used as a field type");
      }
    }
    return classRef(key, where);
  }

  private TypeName classRef(DefKey key, String where) {
    if (toGenerate.contains(key.nsid().value())) {
      return classOf(key);
    }
    String fqcn = dependencyIndex.get(key.key());
    if (fqcn == null) {
      throw new LexiconException(where + ": unresolved ref " + key + ": " + key.nsid()
          + " is not generated here and no dependency index lists it (add its Lexicon pack as a dependency)");
    }
    return fromBinaryName(fqcn);
  }

  static ClassName fromBinaryName(String binary) {
    int dot = binary.lastIndexOf('.', binary.indexOf('$') < 0 ? binary.length() : binary.indexOf('$'));
    String pkg = dot < 0 ? "" : binary.substring(0, dot);
    String[] names = binary.substring(dot + 1).split("\\$");
    ClassName cn = ClassName.get(pkg, names[0]);
    for (int i = 1; i < names.length; i++) {
      cn = cn.nestedClass(names[i]);
    }
    return cn;
  }

  private static boolean isScalar(LexDef d) {
    return d instanceof LexString || d instanceof LexInteger || d instanceof LexBoolean || d instanceof LexBytes
        || d instanceof LexCidLink || d instanceof LexBlob || d instanceof LexUnknown;
  }

  private static TypeName scalarType(LexDef d) {
    return switch (d) {
      case LexString s -> stringType(s);
      case LexInteger i -> ClassName.get(Long.class);
      case LexBoolean b -> ClassName.get(Boolean.class);
      case LexBytes b -> LEX_BYTES;
      case LexCidLink c -> CID_LINK;
      case LexBlob b -> BLOB_REF;
      case LexUnknown u -> JSON_NODE;
      default -> throw new IllegalArgumentException(d.type());
    };
  }

  private static TypeName boxed(TypeName t) {
    return t.isPrimitive() ? t.box() : t;
  }

  // ---------------------------------------------------------------------------------------------------------------
  // scopes: an enclosing type being built, with the nested type and constant names it has handed out

  private static final class Scope {
    final ClassName name;
    final TypeSpec.Builder builder;
    final Set<String> nested = new HashSet<>();
    final Set<String> constants = new HashSet<>();
    boolean hasDoc;
    boolean hasParamDoc;

    Scope(ClassName name, TypeSpec.Builder builder) {
      this.name = name;
      this.builder = builder;
      nested.addAll(name.simpleNames());
      constants.add("NSID");
    }

    String nestedName(String base, String where) {
      for (String candidate : List.of(base, base + "Union", base + "Type")) {
        if (nested.add(candidate)) {
          return candidate;
        }
      }
      throw new LexiconException(where + ": cannot find a free nested type name for \"" + base + "\" in " + name);
    }

    void constant(String base, String value) {
      String n = base;
      for (int i = 2; !constants.add(n); i++) {
        n = base + "_" + i;
      }
      builder.addField(FieldSpec.builder(STRING, n, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
          .initializer("$S", value).build());
    }
  }

  /** The Java type of a property, adding nested types and knownValues constants to {@code scope} as needed. */
  private TypeName fieldType(LexDef def, Nsid ctx, Scope scope, String prop, String where) {
    return switch (def) {
      case LexString s -> {
        for (String v : s.knownValues()) {
          scope.constant(JavaNames.constantName(prop) + "_" + JavaNames.constantName(v), v);
        }
        yield stringType(s);
      }
      case LexRef r -> refType(r.ref(), ctx, where);
      case LexUnion u -> {
        ClassName cn = scope.name.nestedClass(scope.nestedName(JavaNames.typeName(prop), where));
        scope.builder.addType(unionType(cn, u, ctx, where));
        yield cn;
      }
      case LexObject o -> {
        ClassName cn = scope.name.nestedClass(scope.nestedName(JavaNames.typeName(prop), where));
        scope.builder.addType(recordType(cn, o, ctx, where));
        yield cn;
      }
      case LexArray a -> ParameterizedTypeName.get(ClassName.get(List.class),
          boxed(fieldType(a.items(), ctx, scope, prop, where + "[]")));
      default -> {
        if (isScalar(def)) {
          yield scalarType(def);
        }
        throw new LexiconException(where + ": type \"" + def.type() + "\" is not allowed here");
      }
    };
  }

  // ---------------------------------------------------------------------------------------------------------------
  // records, unions, holders

  /**
   * A Java record for an object schema. {@code typeId} non-null marks a Lexicon {@code record}: it implements
   * {@code AtprotoRecord} and always writes {@code $type}.
   */
  private TypeSpec recordType(ClassName cn, LexObject o, Nsid ctx, String where) {
    TypeSpec.Builder b = TypeSpec.recordBuilder(cn).addModifiers(Modifier.PUBLIC, Modifier.STATIC);
    fillRecord(new Scope(cn, b), o, ctx, null, where, o.description());
    return b.build();
  }

  private void fillRecord(Scope scope, LexObject o, Nsid ctx, String typeId, String where, String doc) {
    ClassName cn = scope.name;
    TypeSpec.Builder b = scope.builder;
    b.addAnnotation(AnnotationSpec.builder(JsonInclude.class)
        .addMember("value", "$T.NON_NULL", JsonInclude.Include.class).build());
    b.addAnnotation(AnnotationSpec.builder(JsonIgnoreProperties.class)
        .addMember("ignoreUnknown", "true").build());
    if (typeId != null) {
      b.addAnnotation(AnnotationSpec.builder(JsonTypeInfo.class)
          .addMember("use", "$T.NAME", JsonTypeInfo.Id.class)
          .addMember("include", "$T.PROPERTY", JsonTypeInfo.As.class)
          .addMember("property", "$S", "$type")
          .addMember("defaultImpl", "$T.class", cn).build());
      b.addAnnotation(AnnotationSpec.builder(JsonTypeName.class).addMember("value", "$S", typeId).build());
      b.addSuperinterface(ATPROTO_RECORD);
      b.addMethod(MethodSpec.methodBuilder("$type").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
          .returns(STRING).addStatement("return NSID").build());
    }
    scope.hasDoc = javadoc(b, doc);
    MethodSpec.Builder ctor = MethodSpec.compactConstructorBuilder().addModifiers(Modifier.PUBLIC);
    components(o.properties(), o.required(), o.nullable(), ctx, scope, ctor, where);
    b.recordConstructor(ctor.build());
  }

  private void components(Map<String, LexDef> props, List<String> required, List<String> nullable, Nsid ctx,
                          Scope scope, MethodSpec.Builder ctor, String where) {
    Set<String> names = new HashSet<>();
    for (Map.Entry<String, LexDef> e : props.entrySet()) {
      String prop = e.getKey();
      String member = JavaNames.memberName(prop);
      if (!names.add(member)) {
        throw new LexiconException(where + ": properties collide on Java name \"" + member + "\"");
      }
      if (!scope.constants.add(member)) {
        throw new LexiconException(where + "." + prop + ": property name \"" + prop + "\" collides with the constant "
            + scope.name + "." + member);
      }
      TypeName t = boxed(fieldType(e.getValue(), ctx, scope, prop, where + "." + prop));
      boolean req = required.contains(prop);
      boolean nul = nullable.contains(prop);
      // Optional + nullable is a plain nullable T like any optional property: absent and JSON null both read as
      // Java null, and Java null is omitted on write, so an explicit JSON null cannot be sent through these types.
      ParameterSpec.Builder p = ParameterSpec.builder(t, member);
      p.addAnnotation(AnnotationSpec.builder(JsonProperty.class).addMember("value", "$S", prop).build());
      if (req && nul) {
        p.addAnnotation(AnnotationSpec.builder(JsonInclude.class)
            .addMember("value", "$T.ALWAYS", JsonInclude.Include.class).build());
      }
      String doc = e.getValue().description();
      if (doc != null || req || nul) {
        String text = (req ? (nul ? "Required, may be null." : "Required.")
            : (nul ? "Nullable; null is omitted, an explicit JSON null cannot be sent through this type." : ""))
            + (doc == null ? "" : " " + javadocText(doc));
        if (scope.hasDoc && !scope.hasParamDoc) {
          scope.builder.addJavadoc("\n");
        }
        scope.hasParamDoc = true;
        scope.builder.addJavadoc("@param $L $L\n", member, text.strip());
      }
      ctor.addParameter(p.build());
      if (req && !nul) {
        ctor.addStatement("$T.requireNonNull($N, $S)", Objects.class, member, prop);
      }
    }
  }

  private TypeSpec unionType(ClassName cn, LexUnion u, Nsid ctx, String where) {
    TypeSpec.Builder b = TypeSpec.interfaceBuilder(cn).addModifiers(Modifier.PUBLIC, Modifier.SEALED);
    javadoc(b, u.description());
    Set<String> used = new HashSet<>(cn.simpleNames());
    used.add("Unknown");
    AnnotationSpec.Builder subTypes = AnnotationSpec.builder(JsonSubTypes.class);
    Map<String, String> seenIds = new LinkedHashMap<>();
    for (String ref : u.refs()) {
      DefKey key = LexiconSet.parseRef(ref, ctx);
      if (seenIds.put(key.typeId(), ref) != null) {
        throw new LexiconException(where + ": union lists " + key + " twice");
      }
      TypeName target = refType(ref, ctx, where);
      String base = JavaNames.typeName(key.name().equals("main") ? key.nsid().name() : key.name());
      String name = base;
      if (!used.add(name)) {
        name = JavaNames.typeName(key.nsid().name()) + base;
        if (!used.add(name)) {
          throw new LexiconException(where + ": cannot name the union member " + key);
        }
      }
      ClassName variant = cn.nestedClass(name);
      b.addPermittedSubclass(variant);
      subTypes.addMember("value", "$L", AnnotationSpec.builder(JsonSubTypes.Type.class)
          .addMember("value", "$T.class", variant).addMember("name", "$S", key.typeId()).build());
      b.addType(TypeSpec.recordBuilder(variant).addModifiers(Modifier.PUBLIC, Modifier.STATIC)
          .addSuperinterface(cn)
          .addJavadoc("The {@code $L} member.\n", javadocText(key.typeId()))
          .recordConstructor(MethodSpec.compactConstructorBuilder().addModifiers(Modifier.PUBLIC)
              .addAnnotation(delegating())
              .addParameter(target, "value")
              .addStatement("$T.requireNonNull(value, $S)", Objects.class, "value").build())
          .addMethod(MethodSpec.methodBuilder("value").addAnnotation(JsonValue.class).addAnnotation(Override.class)
              .addModifiers(Modifier.PUBLIC).returns(target).addStatement("return value").build())
          .build());
    }
    AnnotationSpec.Builder typeInfo = AnnotationSpec.builder(JsonTypeInfo.class)
        .addMember("use", "$T.NAME", JsonTypeInfo.Id.class)
        .addMember("include", "$T.PROPERTY", JsonTypeInfo.As.class)
        .addMember("property", "$S", "$type")
        .addMember("visible", "true");
    if (!u.closed()) {
      ClassName unknown = cn.nestedClass("Unknown");
      typeInfo.addMember("defaultImpl", "$T.class", unknown);
      b.addPermittedSubclass(unknown);
      b.addType(unknownType(cn, unknown));
    }
    b.addAnnotation(typeInfo.build());
    if (!u.refs().isEmpty()) {
      b.addAnnotation(subTypes.build());
    }
    return b.build();
  }

  private static AnnotationSpec delegating() {
    return AnnotationSpec.builder(JsonCreator.class)
        .addMember("mode", "$T.DELEGATING", JsonCreator.Mode.class).build();
  }

  /** {@code Unknown(JsonNode raw)}: an open-union member of a type this code does not know, kept verbatim. */
  private static TypeSpec unknownType(ClassName union, ClassName unknown) {
    ClassName gen = ClassName.get(JsonGenerator.class);
    ClassName provider = ClassName.get(SerializerProvider.class);
    return TypeSpec.recordBuilder(unknown).addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .addSuperinterface(union).addSuperinterface(JsonSerializable.class)
        .addJavadoc("A member whose {@code $$type} is not listed; {@code raw} is the whole object, "
            + "{@code $$type} included, and is written back unchanged.\n")
        .recordConstructor(MethodSpec.compactConstructorBuilder().addModifiers(Modifier.PUBLIC)
            .addAnnotation(delegating())
            .addParameter(JSON_NODE, "raw")
            .addStatement("$T.requireNonNull(raw, $S)", Objects.class, "raw").build())
        .addMethod(MethodSpec.methodBuilder("serialize").addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
            .addParameter(gen, "gen").addParameter(provider, "provider").addException(IOException.class)
            .addStatement("raw.serialize(gen, provider)").build())
        .addMethod(MethodSpec.methodBuilder("serializeWithType").addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(gen, "gen").addParameter(provider, "provider")
            .addParameter(TypeSerializer.class, "typeSer").addException(IOException.class)
            .addStatement("raw.serialize(gen, provider)").build())
        .build();
  }

  /** A nested holder of {@code knownValues} constants for a named string def. */
  private static TypeSpec knownValuesType(ClassName cn, LexString s) {
    TypeSpec.Builder b = TypeSpec.classBuilder(cn).addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
    javadoc(b, s.description());
    Scope scope = new Scope(cn, b);
    for (String v : s.knownValues()) {
      scope.constant(JavaNames.constantName(v), v);
    }
    b.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
    return b.build();
  }

  /** Adds {@code text} as Javadoc; returns whether anything was added. */
  private static boolean javadoc(TypeSpec.Builder b, String text) {
    if (text != null && !text.isBlank()) {
      b.addJavadoc("$L\n", javadocText(text));
      return true;
    }
    return false;
  }

  private static String joinDoc(String a, String b) {
    if (a == null || a.isBlank()) {
      return b;
    }
    return b == null || b.isBlank() ? a : a + "\n\n<p>" + b;
  }

  private static String javadocText(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("*/", "*&#47;")
        .replace("@", "{@literal @}").replace("\\u", "\\\\u").strip();
  }

  // ---------------------------------------------------------------------------------------------------------------
  // documents

  private List<JavaFile> files() {
    List<JavaFile> out = new ArrayList<>();
    Map<ClassName, List<LexiconDoc>> clients = new TreeMap<>((a, b) -> a.canonicalName().compareTo(b.canonicalName()));
    for (String nsid : toGenerate) {
      LexiconDoc doc = set.doc(Nsid.parse(nsid)).orElseThrow();
      if (skipped(doc)) {
        LOG.log(System.Logger.Level.INFO, "Skipping {0}: {1} defs are not generated", nsid,
            doc.defs().get("main").type());
        continue;
      }
      out.add(file(doc));
      LexDef main = doc.defs().get("main");
      if (main instanceof LexQuery || main instanceof LexProcedure) {
        ClassName client = ClassName.get(JavaNames.packageName(doc.id(), prefix),
            JavaNames.clientClassName(doc.id()));
        clients.computeIfAbsent(client, k -> new ArrayList<>()).add(doc);
      }
    }
    clients.forEach((cn, docs) -> {
      for (JavaFile f : out) {
        if (f.packageName().equals(cn.packageName()) && f.typeSpec().name().equals(cn.simpleName())) {
          throw new LexiconException(docs.get(0).id() + ": the generated client " + cn
              + " collides with the class generated for a Lexicon of the same name");
        }
      }
      out.add(javaFile(cn.packageName(), client(cn, docs)));
    });
    return out;
  }

  private static JavaFile javaFile(String pkg, TypeSpec type) {
    return JavaFile.builder(pkg, type).indent("  ").skipJavaLangImports(true)
        .addFileComment("Generated by atproto-lexicon-codegen. Do not edit.").build();
  }

  private JavaFile file(LexiconDoc doc) {
    Nsid id = doc.id();
    ClassName top = topClass(id);
    LexDef main = doc.defs().get("main");
    String where = id + "#main";
    TypeSpec.Builder b;
    LexObject object = null;
    String typeId = null;
    String topDoc = joinDoc(doc.description(), main == null ? null : main.description());
    switch (main) {
      case LexRecord r -> {
        b = TypeSpec.recordBuilder(top).addModifiers(Modifier.PUBLIC);
        object = r.record();
        typeId = id.value();
        topDoc = joinDoc(topDoc, object.description());
      }
      case LexObject o -> {
        b = TypeSpec.recordBuilder(top).addModifiers(Modifier.PUBLIC);
        object = o;
      }
      case LexUnion u -> throw new LexiconException(where + ": a union main def is not supported");
      case null, default -> b = TypeSpec.classBuilder(top).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
          .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
    }
    b.addField(FieldSpec.builder(STRING, "NSID", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
        .initializer("$S", id.value()).build());
    Scope scope = new Scope(top, b);
    // reserve the XRPC body names and the nested def names before inline types take any
    if (main instanceof LexQuery || main instanceof LexProcedure) {
      scope.nested.addAll(List.of("Params", "Input", "Output"));
    }
    Map<String, String> defNames = new LinkedHashMap<>();
    for (Map.Entry<String, LexDef> e : doc.defs().entrySet()) {
      if (e.getKey().equals("main")) {
        continue;
      }
      LexDef d = e.getValue();
      if (d instanceof LexObject || d instanceof LexUnion || (d instanceof LexString s && !s.knownValues().isEmpty())) {
        String n = nestedDefName(top, e.getKey());
        if (!scope.nested.add(n)) {
          throw new LexiconException(id + "#" + e.getKey() + ": nested type name " + n + " is already taken in "
              + top);
        }
        defNames.put(e.getKey(), n);
      }
    }
    if (object != null) {
      fillRecord(scope, object, id, typeId, where, topDoc);
    } else {
      javadoc(b, topDoc);
    }
    if (main instanceof LexQuery q) {
      xrpcTypes(doc, scope, q.parameters(), null, q.output(), q.errors());
    } else if (main instanceof LexProcedure p) {
      xrpcTypes(doc, scope, p.parameters(), p.input(), p.output(), p.errors());
    } else if (main instanceof LexString s) {
      for (String v : s.knownValues()) {
        scope.constant(JavaNames.constantName(v), v);
      }
    } else if (main instanceof LexSubscription || main instanceof LexPermissionSet) {
      LOG.log(System.Logger.Level.INFO, "Skipping {0}#main: {1} defs are not generated", id, main.type());
    }
    for (Map.Entry<String, LexDef> e : doc.defs().entrySet()) {
      String name = e.getKey();
      if (name.equals("main")) {
        continue;
      }
      String w = id + "#" + name;
      switch (e.getValue()) {
        case LexObject o -> b.addType(recordType(top.nestedClass(defNames.get(name)), o, id, w));
        case LexUnion u -> b.addType(unionType(top.nestedClass(defNames.get(name)), u, id, w));
        case LexString s when !s.knownValues().isEmpty() ->
            b.addType(knownValuesType(top.nestedClass(defNames.get(name)), s));
        case LexToken t -> scope.constant(JavaNames.constantName(name), w);
        default -> {
          // scalars and arrays have no class of their own; refs to them resolve structurally
        }
      }
    }
    return javaFile(top.packageName(), b.build());
  }

  /** Params/Input/Output nested in a query or procedure holder, plus error-name constants. */
  private void xrpcTypes(LexiconDoc doc, Scope scope, LexParams params, LexBody input, LexBody output,
                         List<LexError> errors) {
    Nsid id = doc.id();
    if (params != null) {
      if (params.properties().keySet().stream().map(JavaNames::memberName).anyMatch("toMap"::equals)) {
        throw new LexiconException(id + "#main.parameters: a parameter named \"toMap\" collides with the generated "
            + "Params.toMap() method");
      }
      ClassName cn = scope.name.nestedClass("Params");
      String where = id + "#main.parameters";
      TypeSpec.Builder pb = TypeSpec.recordBuilder(cn).addModifiers(Modifier.PUBLIC, Modifier.STATIC);
      boolean paramsDoc = javadoc(pb, params.description());
      pb.addAnnotation(AnnotationSpec.builder(JsonInclude.class)
          .addMember("value", "$T.NON_NULL", JsonInclude.Include.class).build());
      Scope ps = new Scope(cn, pb);
      ps.hasDoc = paramsDoc;
      MethodSpec.Builder ctor = MethodSpec.compactConstructorBuilder().addModifiers(Modifier.PUBLIC);
      components(params.properties(), params.required(), List.of(), id, ps, ctor, where);
      pb.recordConstructor(ctor.build());
      ParameterizedTypeName map = ParameterizedTypeName.get(ClassName.get(Map.class), STRING,
          ClassName.get(Object.class));
      MethodSpec.Builder toMap = MethodSpec.methodBuilder("toMap").addModifiers(Modifier.PUBLIC).returns(map)
          .addJavadoc("The parameters that are set, by Lexicon name, in schema order.\n")
          .addStatement("$T m = new $T<>()", map, LinkedHashMap.class);
      for (String prop : params.properties().keySet()) {
        String member = JavaNames.memberName(prop);
        toMap.beginControlFlow("if ($N != null)", member).addStatement("m.put($S, $N)", prop, member).endControlFlow();
      }
      pb.addMethod(toMap.addStatement("return m").build());
      scope.builder.addType(pb.build());
    }
    if (input != null && input.schema() != null) {
      bodyType(input.schema(), id, scope, "Input", id + "#main.input");
    }
    if (output != null && output.schema() != null) {
      bodyType(output.schema(), id, scope, "Output", id + "#main.output");
    }
    for (LexError e : errors) {
      scope.constant("ERROR_" + JavaNames.constantName(e.name()), e.name());
    }
  }

  private TypeName bodyType(LexDef schema, Nsid id, Scope scope, String name, String where) {
    return switch (schema) {
      case LexObject o -> {
        ClassName cn = scope.name.nestedClass(name);
        scope.builder.addType(recordType(cn, o, id, where));
        yield cn;
      }
      case LexUnion u -> {
        ClassName cn = scope.name.nestedClass(name);
        scope.builder.addType(unionType(cn, u, id, where));
        yield cn;
      }
      case LexRef r -> refType(r.ref(), id, where);
      default -> throw new LexiconException(where + ": unsupported body schema " + schema.type());
    };
  }

  /** The Java type of an XRPC body as the client exposes it, without generating anything. */
  private TypeName bodyJavaType(Nsid id, LexBody body, String name) {
    if (body == null) {
      return ClassName.get(Void.class);
    }
    if (body.schema() == null) {
      return body.encoding().equals("application/json") ? JSON_NODE : RAW_BYTES;
    }
    return switch (body.schema()) {
      case LexRef r -> refType(r.ref(), id, id + "#main");
      default -> topClass(id).nestedClass(name);
    };
  }

  // ---------------------------------------------------------------------------------------------------------------
  // clients

  private TypeSpec client(ClassName cn, List<LexiconDoc> docs) {
    TypeSpec.Builder b = TypeSpec.classBuilder(cn).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .addJavadoc("Typed XRPC calls for the {@code $L.*} Lexicons.\n", docs.get(0).id().authority())
        .addField(XRPC_CLIENT, "xrpc", Modifier.PRIVATE, Modifier.FINAL)
        .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)
            .addParameter(XRPC_CLIENT, "xrpc")
            .addStatement("this.xrpc = $T.requireNonNull(xrpc, $S)", Objects.class, "xrpc").build());
    ParameterizedTypeName optAuth = ParameterizedTypeName.get(ClassName.get(Optional.class), XRPC_AUTH);
    Set<String> methods = new HashSet<>();
    for (LexiconDoc doc : docs) {
      Nsid id = doc.id();
      ClassName holder = topClass(id);
      String constant = JavaNames.constantName(id.name());
      String method = JavaNames.memberName(id.name());
      if (!methods.add(method)) {
        throw new LexiconException(id + ": client method " + method + " collides in " + cn);
      }
      b.addField(FieldSpec.builder(NSID_CLASS, constant, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
          .initializer("$T.parse($T.NSID)", NSID_CLASS, holder).build());
      MethodSpec.Builder m = MethodSpec.methodBuilder(method).addModifiers(Modifier.PUBLIC)
          .addParameter(URI.class, "service");
      LexDef main = doc.defs().get("main");
      String description = main.description();
      if (description != null) {
        m.addJavadoc("$L\n\n", javadocText(description));
      }
      TypeName out;
      if (main instanceof LexQuery q) {
        out = bodyJavaType(id, q.output(), "Output");
        CodeBlock params = CodeBlock.of("$T.of()", Map.class);
        if (q.parameters() != null) {
          m.addParameter(holder.nestedClass("Params"), "params");
          params = CodeBlock.of("params.toMap()");
        }
        m.addParameter(optAuth, "auth")
            .addStatement("return xrpc.query(service, $N, $L, $L, auth)", constant, params, classLiteral(out));
      } else {
        LexProcedure p = (LexProcedure) main;
        if (p.parameters() != null && !p.parameters().properties().isEmpty()) {
          LOG.log(System.Logger.Level.WARNING,
              "{0}: procedure with query parameters; no client method generated (the XRPC client cannot send them)",
              id);
          continue;
        }
        out = bodyJavaType(id, p.output(), "Output");
        if (p.input() == null) {
          m.addParameter(optAuth, "auth")
              .addStatement("return xrpc.procedure(service, $N, null, $L, auth)", constant, classLiteral(out));
        } else if (p.input().schema() == null && !p.input().encoding().equals("application/json")) {
          m.addParameter(RAW_BYTES, "body").addParameter(STRING, "contentType").addParameter(optAuth, "auth")
              .addJavadoc("@param contentType the body's MIME type (Lexicon encoding <code>$L</code>)\n",
                  javadocText(p.input().encoding()))
              .addStatement("return xrpc.procedureBytes(service, $N, body, contentType, $L, auth)", constant,
                  classLiteral(out));
        } else {
          m.addParameter(bodyJavaType(id, p.input(), "Input"), "in").addParameter(optAuth, "auth")
              .addStatement("return xrpc.procedure(service, $N, in, $L, auth)", constant, classLiteral(out));
        }
      }
      b.addMethod(m.addJavadoc("@see $T\n", holder).returns(ParameterizedTypeName.get(UNI, out)).build());
    }
    return b.build();
  }

  private static CodeBlock classLiteral(TypeName t) {
    TypeName raw = t instanceof ParameterizedTypeName p ? p.rawType() : t;
    return CodeBlock.of("$T.class", raw);
  }
}
