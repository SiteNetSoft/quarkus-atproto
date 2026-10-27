# atproto-lexicon-codegen

Parses AT Protocol [Lexicon](https://atproto.com/specs/lexicon) JSON and generates Java sources with
[JavaPoet](https://github.com/palantir/javapoet). Plain Java. It is used two ways:

- by the **Lexicon packs** at their own build (`exec-maven-plugin:java` running `Cli` in `generate-sources`, see
  `lexicons/pom.xml`), which also writes `META-INF/atproto/lexicons/index.json` (`nsid#def` → generated class);
- by the **`quarkus-atproto-lexicon` extension**'s code generator for an application's `src/main/lexicons`
  (consumers normally use that, not this library directly).

Status: experimental, version 0.1.

## Command line (`org.sitenetsoft.atproto.lexicon.codegen.Cli`)

```
--lexicons <dir>              Lexicon JSON to generate (every *.json below it)
--out <dir>                   source root for the generated .java files
--index <file>                where to write the generated index.json
[--package-prefix <p>]        optional package prefix
[--dependency-index <file>]   index of a dependency pack (index.json, a pack .jar or its classes dir); repeatable
[--dependency-lexicons <dir>] Lexicon JSON of dependencies, read for ref resolution only; repeatable
```

Errors are thrown (never `System.exit`), since Maven runs it in its own JVM.

## Mapping

Package = NSID authority, class = PascalCase name, other defs nested; `record`/`object` → Java `record` with plain
nullable optional fields and `unknownFields` (lossless round trips); `union` → sealed interface (`Unknown` for open
unions); string formats → `atproto-syntax` types; `blob` → `BlobRef`; `cid-link` → `CidLink`; `bytes` → `Bytes`;
`unknown` → `JsonNode`; queries/procedures → a typed client per namespace using `atproto-xrpc-api`. Refs into another
pack use that pack's classes (through its index); a pack's types are never generated twice. Golden tests generate the
core and Standard.site Lexicons and compile the result in memory; the packs round-trip real records.
