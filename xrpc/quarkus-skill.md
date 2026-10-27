# quarkus-atproto-xrpc — skill notes for coding agents

- Generic client: `@Inject XrpcClient` / `ReactiveXrpcClient` (package `org.sitenetsoft.quarkus.atproto.xrpc.api`):
  `query(URI service, Nsid, Map<String,?> params, Class<T> out, Optional<XrpcAuth>)`, `procedure(..., Object input,
  ...)`, `procedureBytes(..., byte[] body, String contentType, ...)`. `service` is the PDS base URL (no `/xrpc`).
  `out = byte[].class` returns the raw body, `Void.class` nothing. Prefer the generated typed clients:
  `new com.atproto.repo.RepoClient(reactiveXrpcClient)`.
- Errors: `XrpcException` (`status()`, `error()` e.g. `InvalidSwap`/`RecordNotFound`, `errorMessage()`,
  `rateLimit()` map of `ratelimit-*` headers). Transport failures are `quarkus-safe-fetch` exceptions
  (`BlockedAddressException` for private addresses; tests set `quarkus.safe-fetch.https-only=false` and
  `quarkus.safe-fetch.allow-private-addresses=true`).
- Auth SPI `XrpcAuth`: `Uni<XrpcRequest> authorize(XrpcRequest)` (return `request.withHeader(...)`) and
  `boolean shouldRetry(XrpcResponse)`; only a 4xx response for which `shouldRetry` is true is authorized and resent,
  exactly once. Successes and 5xx are never resent. Never log the headers it adds.
- Records: `@Inject RecordsImpl records; records.forRepo(Did repo, URI pds, XrpcAuth auth)` →
  `ReactiveAtprotoRecords`; `blockingForRepo(...)` → `AtprotoRecords`; overloads take a `WriteGuard`. Methods:
  `create(collection, record)` (server TID), `create(collection, rkey, record)`, `put(collection, rkey, record,
  Optional<Cid> swapRecord)`, `get(collection, rkey, Class<T>)` → `Optional` (empty on `RecordNotFound`),
  `delete`, `uploadBlob(bytes, mime)` → `BlobRef`. Results: `RecordResult(AtUri uri, Cid cid,
  Optional<String> validationStatus)`. A caller-chosen rkey (`create(..., rkey, ...)`, `put`) must match the
  Lexicon's record `key` type (`tid` → use `Tid.next()`, `literal:self` → `self`, ...), else
  `RecordValidationException` (problem path `rkey`) before sending; a slug key for a `tid` collection is refused.
  `forRepo(repo, Supplier<Uni<URI>> pds, auth, guard)` looks the PDS up per call.
- Writes run `WriteGuard.check(collection, "create"|"update"|"delete", blobMime)` then `LexiconValidator` (mode
  `quarkus.atproto.lexicon.validation`) before any request; failures are `RecordValidationException(collection(),
  problems())` or the guard's exception, and nothing is sent. `$type` is added when missing; another collection's
  `$type` is refused.
- Config: `quarkus.atproto.xrpc.timeout` (30s), `quarkus.atproto.xrpc.max-response-size` (5M). Blocking APIs must not
  run on the event loop.
