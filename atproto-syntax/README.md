# atproto-syntax

Validated AT Protocol string formats for Java: one immutable type per format, plain Java (Jackson only, no Quarkus).
The rules follow <https://atproto.com/specs> and the official syntax interop fixtures (committed under
`src/test/resources`, with their source and fetch date).

Status: experimental, version 0.1.

```xml
<dependency>
  <groupId>org.sitenetsoft.quarkus</groupId>
  <artifactId>atproto-syntax</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

| Type | Notes |
| --- | --- |
| `Did` | `did:<method>:<id>`; `method()`, `isBlessed()` (`plc`, `web`) |
| `Handle` | stored lowercase; `Handle.normalise(userInput)` strips a leading `@` and a trailing `.`; disallowed TLDs (`.local`, `.arpa`, `.example`, `.internal`, `.invalid`, `.localhost`, `.onion`, `.alt`) refused; `Handle.INVALID` = `handle.invalid` |
| `AtIdentifier` | sealed: `Did` or `Handle` |
| `Nsid` | `authority()`, `name()`, `segments()`, `authorityDomain()` |
| `RecordKey`, `RecordKeyType` | record keys; Lexicon key types `tid`, `nsid`, `literal:<v>`, `any` with `accepts(key)` |
| `Tid` | `Tid.next()` (monotonic per JVM), `timestamp()` |
| `AtUri` | `at://<authority>[/<collection>[/<rkey>]]`; `of(...)`, `authority()`, `collection()`, `rkey()` |
| `Cid` | CID string (syntax only) |
| `Datetime` | the spec's RFC 3339 profile; `now()` / `of(Instant)` give UTC, milliseconds, `Z`; `instant()` |
| `Language` | BCP 47 tag |

`parse` and the constructors throw `SyntaxException` (an `IllegalArgumentException`); every type has `isValid(String)`.
`AtprotoSyntaxModule` (Jackson) reads and writes them as strings; the Quarkus extensions register it on the managed
`ObjectMapper`.
