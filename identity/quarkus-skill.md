# quarkus-atproto-identity — skill notes for coding agents

- Inject `IdentityResolver` (blocking, never on the event loop) or `ReactiveIdentityResolver` (Mutiny):
  `resolveHandle(Handle) → Did`, `resolve(AtIdentifier) → AtprotoIdentity`, `resolveDid(Did) → AtprotoIdentity`.
  Syntax types are in `org.sitenetsoft.atproto.syntax`.
- User input: build the identifier with `Handle.normalise(input)` (strips `@` and a trailing `.`, lowercases) or
  `Did.parse` for `did:` input. `Handle.parse` rejects `@alice.bsky.social`.
- `AtprotoIdentity(did, handle, pds, signingKeyMultibase)`: `handle` is `Handle.INVALID` when the bidirectional check
  fails; check `handleVerified()` before showing it as the user's name. `pds` is a bare `https://host[:port]`.
- Handle resolution: DNS `_atproto.<handle>` TXT `did=` and HTTPS `/.well-known/atproto-did` in parallel, DNS wins on
  disagreement, several different DNS DIDs fail.
- Failures are `IdentityException`: `HandleNotFound` (the handle cleanly does not exist: a user error),
  `IdentityResolutionFailed` (conflicting DNS, DNS/transport errors, 5xx: "try again"; wraps `DidException` causes),
  `NoPds`. A transient error during the reverse handle check yields `handle.invalid` for that call only (not cached). Failures are not cached; successes are for `quarkus.atproto.identity.cache-ttl` (`5m`).
- Only `did:plc` / `did:web` are accepted.
- Tests: replace DNS with your own `@ApplicationScoped TxtResolver` bean (the default is a `@DefaultBean`), point
  `quarkus.did.plc.url` at a fake, map handles to a loopback base with
  `quarkus.atproto.identity.https-well-known-override."<handle>"=http://127.0.0.1:<port>/...` (test/dev only: ignored with a WARN in production; keys case-insensitive), and set
  `quarkus.safe-fetch.https-only=false`, `quarkus.safe-fetch.allow-private-addresses=true`. On hosts without a
  usable `resolv.conf`, `-Dvertx.disableDnsResolver=true` keeps Netty quiet.
