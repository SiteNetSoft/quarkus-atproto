# quarkus-atproto-lexicon

AT Protocol [Lexicons](https://atproto.com/specs/lexicon) for Quarkus: Java types generated from your own Lexicon
JSON at build time, and runtime validation of records against every Lexicon on the classpath.

Status: experimental, version 0.1.

```xml
<dependency>
  <groupId>org.sitenetsoft.quarkus</groupId>
  <artifactId>quarkus-atproto-lexicon</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
<!-- Lexicon packs: JSON plus the classes generated from it -->
<dependency>
  <groupId>org.sitenetsoft.quarkus</groupId>
  <artifactId>atproto-lexicons-standard-site</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Lexicon packs

A pack is a jar shipping Lexicon JSON at `META-INF/atproto/lexicons/<nsid path>.json`
(`site/standard/document.json`), the Java types generated from it, and `META-INF/atproto/lexicons/index.json`
(`nsid#def` → generated class). `atproto-lexicons-core` has the `com.atproto.*` subset the client needs,
`atproto-lexicons-standard-site` the Standard.site Lexicons (`site.standard.Document`, `Publication`, ...).

## Code generation for your own Lexicons

Put Lexicon files under `src/main/lexicons/` (any layout, e.g. `src/main/lexicons/com/example/note.json`). The
extension's code generator runs in the build's `generate-code` phase (`quarkus-maven-plugin` goal `generate-code`, as
in every Quarkus project, or the Gradle plugin) and writes Java sources to `target/generated-sources/atproto-lexicon`.

- Mapping: package = NSID authority (`com.example.note` → `com.example`), class = PascalCase name (`Note`); other defs
  are nested types; `record`/`object` → Java `record` (optional properties are plain nullable fields; properties the
  Lexicon does not declare are kept in `unknownFields`, so read-modify-write is lossless); `union` → sealed interface
  (open unions add `Unknown`); string formats → `org.sitenetsoft.atproto.syntax` types; `blob` → `BlobRef`;
  `cid-link` → `CidLink`; `bytes` → `Bytes`; `unknown` → `JsonNode`; queries/procedures → a typed client per
  namespace (needs `quarkus-atproto-xrpc`).
- References to pack NSIDs (`"ref": "site.standard.document"`) use the pack's class (`site.standard.Document`),
  found through the `index.json` of every runtime dependency. Pack types are never generated again.
- A dependency that ships Lexicon JSON **without** generated classes (no `index.json`) is generated into your app
  only when listed in `quarkus.atproto.lexicon.codegen.generate-from-dependencies`. Listing a jar that ships classes
  fails the build (it would duplicate them). Otherwise its Lexicons are only read to check references, and a ref to
  one of them fails with a message naming it.
- Constraints (lengths, graphemes, enums, required) are not checked in constructors: that is the validator's job.

| Property (build time) | Default | Purpose |
| --- | --- | --- |
| `quarkus.atproto.lexicon.codegen.include` | all | NSID globs (`*` = any characters) of the Lexicons to generate |
| `quarkus.atproto.lexicon.codegen.exclude` | none | NSID globs not to generate (still read for references) |
| `quarkus.atproto.lexicon.codegen.package-prefix` | none | prefix for generated packages (`<prefix>.com.example.Note`) |
| `quarkus.atproto.lexicon.codegen.generate-from-dependencies` | none | `groupId:artifactId` of dependencies whose Lexicon JSON (shipped without classes) is generated into the app |

## Validation

```java
@Inject LexiconValidator validator;

List<ValidationProblem> problems = validator.validate(Nsid.parse("site.standard.document"), recordJsonNode);
List<ValidationProblem> problems2 = validator.validate(collection, documentRecord);   // generated type
validator.validate(collection, record, ValidationMode.TRUE);                         // per-call mode
// ValidationProblem(path = "/title", message = "is 501 graphemes long, at most 500 graphemes allowed (maxGraphemes)")
validator.validateKey(collection, RecordKey.parse("my-first-post"));                 // the record key
// ValidationProblem(path = "rkey", message = "the record key \"my-first-post\" is not a TID (... key type tid)")
```

`validateKey` checks a record key against the collection's record `key` type (`tid`, `nsid`, `literal:<value>`,
`any`); an unknown Lexicon is skipped in `optimistic` mode. The records API (`quarkus-atproto-xrpc`) runs it on every
caller-chosen key before sending.

`LexiconRegistry` holds every Lexicon on the classpath: the `META-INF/atproto/lexicons/**.json` of the application
and its dependencies plus your `src/main/lexicons`, found **at build time** (no runtime classpath scanning; the files
are registered as native-image resources). `knows(nsid)`, `def(nsid, "main")`, `document(nsid)`, `nsids()`.

Your `src/main/lexicons` files are packaged as `META-INF/atproto/lexicons/<nsid path>.json` when the build knows the
project's source layout (Maven and Gradle builds, dev mode, tests). Without that workspace information they are not
registered (a WARN says so) and optimistic validation lets your own records through unchecked. Lexicon JSON placed
directly under `src/main/resources/META-INF/atproto/lexicons/<nsid path>.json` is always registered (but is not a
code generation input).

Rules checked (reporting every problem, each with a JSON Pointer path):

- the record is an object and its `$type` equals the collection NSID;
- `required` properties present; `null` only for `nullable` properties; undeclared properties are allowed;
- types: `string`, `integer` (no fractions), `boolean`, `null`, `array`, `object`, `unknown` (must be an object),
  `bytes` (`{"$bytes": base64}`), `cid-link` (`{"$link": cid}`), `blob`
  (`{"$type": "blob", "ref": {"$link"}, "mimeType", "size"}`);
- strings: `minLength`/`maxLength` in **UTF-8 bytes**, `minGraphemes`/`maxGraphemes` in extended grapheme clusters
  (`👩‍💻` and a flag each count as one; JDK `BreakIterator`), `enum`, `const`, `format` (`at-identifier`, `at-uri`,
  `cid`, `datetime`, `did`, `handle`, `nsid`, `tid`, `record-key`, `language`, and `uri` = an absolute URI of at most
  8 KiB); `knownValues` are open and not enforced;
- integers: `minimum`, `maximum`, `enum`, `const`; arrays: `minLength`/`maxLength` item counts, every item;
- blobs: `accept` MIME patterns (`image/png`, `image/*`, `*/*`) and `maxSize`;
- unions: the value needs a `$type`; in a **closed** union it must be one of the refs, in an **open** union (the
  default) any other `$type` passes; refs are followed within the file and across files.

| Mode (`quarkus.atproto.lexicon.validation`) | Collection Lexicon known | Unknown |
| --- | --- | --- |
| `optimistic` (default) | validated | accepted |
| `true` | validated | rejected (and refs to unknown Lexicons are problems) |
| `false` | not validated | accepted |

The Quarkus-managed `ObjectMapper` gets the `atproto-syntax` Jackson module, so generated types (de)serialise with
it directly.

## Native

Lexicon JSON is registered as native-image resources. Every class named in a `META-INF/atproto/lexicons/index.json`
(the packs) is registered for reflection with its nested classes, so Jackson can read and write the generated records
and unions. For the application's own `src/main/lexicons` the code generator also writes
`AtprotoLexiconReflection_<hash>` (in the package of the first generated type; the suffix is a short hash of the
module's `groupId:artifactId`, so two modules generating into one package do not collide), annotated `@RegisterForReflection(ignoreNested = false, targets = ...)`
with every generated class, so they are registered as well. Nothing to do by hand.
