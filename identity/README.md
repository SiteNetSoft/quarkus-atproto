# quarkus-atproto-identity

Resolve AT Protocol identities from Quarkus: handle → DID, DID → verified identity (handle checked in both
directions, PDS, signing key). DID documents come from [`quarkus-did`](https://github.com/SiteNetSoft/quarkus-did);
every HTTP fetch goes through [`quarkus-safe-fetch`](https://github.com/SiteNetSoft/quarkus-safe-fetch).

Status: experimental, version 0.1.

```xml
<dependency>
  <groupId>org.sitenetsoft.quarkus</groupId>
  <artifactId>quarkus-atproto-identity</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Usage

```java
@Inject IdentityResolver identities;            // blocking; never on the event loop
@Inject ReactiveIdentityResolver reactive;      // Mutiny

// user input such as "@Alice.bsky.social." -> normalise first
AtIdentifier id = input.startsWith("did:") ? Did.parse(input) : Handle.normalise(input);
AtprotoIdentity who = identities.resolve(id);
who.did();                  // did:plc:… or did:web:…
who.handle();               // verified handle, or Handle.INVALID (handle.invalid)
who.handleVerified();
who.pds();                  // https://host[:port]
who.signingKeyMultibase();  // #atproto key, Optional
who.handleCheckFailed();    // true: handle.invalid only because the reverse check failed transiently (not cached)
```

### Rules

- **Handle → DID** (`resolveHandle`): DNS TXT `_atproto.<handle>` (`did=<did>`) and HTTPS
  `https://<handle>/.well-known/atproto-did` run in parallel. A DNS answer wins at once; an HTTPS answer is used only
  once DNS has reported nothing, so on disagreement DNS wins. TXT records naming several different DIDs fail with
  `IdentityResolutionFailed` (even if HTTPS answers). When neither method gives a DID, the handle is `HandleNotFound`
  only if both answered cleanly (no record, 404, a body that is not a DID); if either could not tell (DNS error or
  timeout, transport failure, HTTP 5xx or 429) it is `IdentityResolutionFailed`, meaning "try again".
- **Bidirectional check**: the DID document's first valid `at://` handle in `alsoKnownAs` is kept only if it resolves
  back to the same DID; otherwise the identity's handle is `handle.invalid`. `resolve(handle)` also requires that the
  document claims the handle you started from. If the claimed handle cannot be checked right now (a transient error),
  the identity carries `handle.invalid` for that call but is not cached, so the next call checks again.
- **PDS**: the service `#atproto_pds` of type `AtprotoPersonalDataServer`, whose endpoint must be `https://host[:port]`
  (a trailing `/` is dropped; any other path, query, fragment or user info is rejected) → otherwise `NoPds`.
  `http` is accepted only when `quarkus.safe-fetch.https-only=false` (tests).
- Only `did:plc` and `did:web` are AT Protocol account DIDs; others fail with `IdentityResolutionFailed`. `did:web` is
  hostname-level only, as AT Protocol requires: a path (`did:web:host:u:alice`) or a port (`did:web:host%3A8443`,
  allowed on `localhost` only) fails with `IdentityResolutionFailed` before anything is fetched.
- Both directions are cached for `cache-ttl`; failures are not cached. `quarkus-did` caches the DID documents the
  identities are built from under `quarkus.did.cache-ttl`, so an identity can be as old as **both TTLs together**:
  for AT Protocol OAuth keep `quarkus.did.cache-ttl + quarkus.atproto.identity.cache-ttl` at 10 minutes or less (the
  defaults, 5m + 5m, do). Startup logs a WARN when the sum is larger.

| Exception (all `IdentityException`) | When |
| --- | --- |
| `HandleNotFound` | neither DNS nor HTTPS gave a DID and both answered cleanly (including `handle.invalid`) |
| `IdentityResolutionFailed` | conflicting DNS records, a handle lookup that errored (DNS failure, transport error, 5xx/429), non-AT DID method, DID resolution failure (cause: the `DidException`), timeout |
| `NoPds` | missing `#atproto_pds`, wrong type, or an endpoint that is not a bare origin |

### DNS

DNS goes through the `TxtResolver` SPI (`Uni<List<String>> txt(String name)`; empty list for NXDOMAIN or no
records). The default `VertxTxtResolver` (`@DefaultBean`) uses the Quarkus-managed Vert.x DNS client; declare your own
CDI bean implementing `TxtResolver` to replace it (tests use a map).

## Configuration

| Property | Default | Purpose |
| --- | --- | --- |
| `quarkus.atproto.identity.cache-ttl` | `5m` | Cache for handle → DID and DID → identity; `0` disables. For AT Protocol OAuth keep it plus `quarkus.did.cache-ttl` at 10 minutes or less (WARN at startup otherwise) |
| `quarkus.atproto.identity.dns.servers` | system resolver | DNS servers for the default `TxtResolver`, tried in order (`host`, `host:port`, `[v6]:port`) |
| `quarkus.atproto.identity.https-well-known-override."<handle>"` | empty | **Test/dev only.** Fetch this handle's well-known from `<base URL>/.well-known/atproto-did` instead of `https://<handle>/…`, so a loopback server can stand in for the handle's host. Keys are case-insensitive. **Ignored in production** (normal launch mode, logged as a WARN) |

Fetches also obey `quarkus.safe-fetch.*` and `quarkus.did.*` (e.g. `quarkus.did.plc.url`). In tests against loopback
servers set `quarkus.safe-fetch.https-only=false` and `quarkus.safe-fetch.allow-private-addresses=true`, replace
`TxtResolver`, and map each test handle with `https-well-known-override`. A `@QuarkusIntegrationTest` runs the
packaged application in normal launch mode (the override is ignored and test beans are not part of the app): start a
`FakeDns` from `quarkus-atproto-test` (`dns.handle(account)`) and set `quarkus.atproto.identity.dns.servers` to
`dns.server()`.

## Dev UI

The card has an identity lookup: type a handle (as a user would, `@` and trailing dot allowed) or a DID and see the
DID, verified handle, PDS and signing key, or the error. It also shows the cache TTL, DNS servers and active
well-known overrides.
