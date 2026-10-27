# quarkus-atproto-xrpc

An AT Protocol [XRPC](https://atproto.com/specs/xrpc) client for Quarkus, and a records API that validates each record
against its Lexicon before writing it to the user's PDS.

Status: experimental, version 0.1.

```xml
<dependency>
  <groupId>org.sitenetsoft.quarkus</groupId>
  <artifactId>quarkus-atproto-xrpc</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## XRPC client

Inject `XrpcClient` (blocking) or `ReactiveXrpcClient` (Mutiny). Both live in `atproto-xrpc-api`, so the
typed clients generated from Lexicons (`com.atproto.repo.RepoClient`, your own namespaces) use them directly:

```java
@Inject ReactiveXrpcClient xrpc;

RepoClient repo = new RepoClient(xrpc);
Uni<GetRecord.Output> out = repo.getRecord(URI.create("https://pds.example.com"),
    new GetRecord.Params(did, Nsid.parse("site.standard.document"), RecordKey.parse("3kabc"), null),
    Optional.empty());
```

- `query` is `GET <service>/xrpc/<nsid>?<params>`: `null` parameters are left out, lists repeat the parameter
  (`?a=1&a=2`), values are written with `toString()` (syntax types give their string form).
- `procedure` is `POST` with the input as JSON; `procedureBytes` posts a raw body (a blob) with its content type.
- Output classes are read with the application's `ObjectMapper`; `byte[].class` gives the raw body, `Void.class`
  discards it.
- A non-2xx answer fails with `XrpcException`: `status()`, `error()` and `errorMessage()` from the standard
  `{"error": ..., "message": ...}` body, and `rateLimit()` with the `ratelimit-limit`, `ratelimit-remaining`,
  `ratelimit-reset` and `ratelimit-policy` headers.
- Transport is `quarkus-safe-fetch`, so its SSRF rules apply to user-supplied PDS hosts (private addresses refused,
  https only); `POST`s never follow redirects, and a `GET` answered from another host after a redirect is refused.

### Authorization

Pass an `XrpcAuth` to authorize a call. The client calls `authorize(request)` (add headers with
`request.withHeader(...)`), sends, and shows the response to `shouldRetry(response)`; when that answers `true` for an
error response the request is authorized again and sent **once** more (a rotated DPoP nonce, a refreshed token). A
successful response is never resent. The OAuth extension provides the DPoP implementation; without auth calls are
anonymous.

## Records API

`RecordsImpl` (a bean) makes the records API of one repository:

```java
@Inject RecordsImpl records;

AtprotoRecords mine = records.blockingForRepo(did, pdsUri, auth);   // or forRepo(...) for ReactiveAtprotoRecords
RecordResult r = mine.create(Nsid.parse(Document.NSID), document);   // server-generated TID
mine.put(Nsid.parse(Document.NSID), RecordKey.parse(Tid.next().value()), document, Optional.empty()); // key: tid
mine.put(profiles, RecordKey.parse("self"), profile, Optional.of(previousCid));  // a "literal:self" collection
Optional<Document> d = mine.get(collection, rkey, Document.class);   // empty on RecordNotFound
mine.delete(collection, rkey);
BlobRef cover = mine.uploadBlob(bytes, "image/png");
```

Applications signed in through `quarkus-atproto-oauth` get the same API from `AtprotoUser.records()`.

- A record is a generated Lexicon class, a `JsonNode` or a `Map`. It is sent with its `$type`; a record without one
  gets the collection, a record of another collection is refused.
- Before anything is sent, the `WriteGuard` bean is asked (`check(collection, action, blobMime)`, action `create`,
  `update` or `delete`; a blob upload is `create` on `com.atproto.repo.uploadBlob` with its MIME type) and the record
  is validated with `LexiconValidator` in the mode `quarkus.atproto.lexicon.validation` (`optimistic` by default).
  Problems fail with `RecordValidationException` (`collection()`, `problems()`). The default guard allows everything;
  the OAuth extension checks the granted scope.
- A record key you choose (`create(collection, rkey, record)`, `put`) is checked against the Lexicon's record `key`
  type (`tid`, `nsid`, `literal:<value>`, `any`) in the same mode: a slug such as `my-first-post` for
  `site.standard.document` (key `tid`) is refused with a problem at path `rkey`, before anything is sent (a PDS that
  does not know the Lexicon would store it, and validating readers would skip the record). Use `Tid.next()` for `tid`
  collections.
- `RecordResult` carries the record's `AtUri`, its CID and the PDS's `validationStatus`.

## Configuration

| Property (runtime) | Default | Purpose |
| --- | --- | --- |
| `quarkus.atproto.xrpc.timeout` | `30s` | Timeout of one exchange (the authorization retry gets its own) |
| `quarkus.atproto.xrpc.max-response-size` | `5M` | Largest response body accepted |

Address and scheme rules come from `quarkus.safe-fetch.*`.
