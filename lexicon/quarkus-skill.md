# quarkus-atproto-lexicon — skill notes for coding agents

- Lexicon packs (`atproto-lexicons-core`, `atproto-lexicons-standard-site`) ship JSON under
  `META-INF/atproto/lexicons/<nsid path>.json`, the generated classes (`site.standard.Document`,
  `com.atproto.repo.StrongRef`) and `META-INF/atproto/lexicons/index.json`. Use those classes; never generate a pack.
- App Lexicons go in `src/main/lexicons/**.json`; the `CodeGenProvider` (id `atproto-lexicon`) generates them in
  `generate-code` into `target/generated-sources/atproto-lexicon`. Refs to pack NSIDs resolve to pack classes via the
  dependencies' `index.json`. Config (build time): `quarkus.atproto.lexicon.codegen.include`/`exclude` (NSID globs),
  `package-prefix`, `generate-from-dependencies` (`groupId:artifactId` of jars shipping JSON without classes; listing a
  jar that has `index.json` fails the build).
- Generated records: plain nullable fields (no `Optional`), unknown properties kept in `unknownFields`, constraints
  not checked in constructors.
- Validate before writing: `@Inject LexiconValidator v; v.validate(Nsid collection, JsonNode|Object record)` →
  `List<ValidationProblem(path, message)>` (JSON Pointer paths like `/title`); empty = valid. Overload with
  `ValidationMode` for one call. Mode config `quarkus.atproto.lexicon.validation` = `optimistic` (default: unknown
  collections pass) | `true` (unknown collections fail) | `false` (skip). Record keys:
  `v.validateKey(collection, rkey)` against the Lexicon's `key` type (problem path `rkey`); the records API calls it.
- Lengths: `maxLength` is UTF-8 bytes, `maxGraphemes` is grapheme clusters (`Graphemes.count`, `Graphemes.utf8Length`).
  `👩‍💻` = 1 grapheme but 11 bytes, so 500 of them break a 5000-byte `maxLength`.
- Open unions accept unknown `$type`s; closed ones don't. The record's own `$type` must equal the collection.
- `LexiconRegistry` (`knows`, `def`, `document`, `nsids`) is filled at build time; adding Lexicon JSON at run time is
  not supported. The managed `ObjectMapper` has `AtprotoSyntaxModule` registered.
- Native: pack classes (every `index.json` entry and its nested classes) and the types generated from
  `src/main/lexicons` (through the generated `AtprotoLexiconReflection_<hash>` holder, one per module) are registered for reflection
  automatically; don't add `@RegisterForReflection` for them.
