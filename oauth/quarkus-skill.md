# quarkus-atproto-oauth — skill notes for coding agents

- Modes (`quarkus.atproto.oauth.mode`, runtime): `login` (default; AT Protocol OAuth is the app's sign-in, principal =
  DID, cookie session, `POST /atproto/logout`) or `link` (the app's own mechanism signs users in; `/atproto/login`
  needs that identity, else 401; the callback links app principal → DID → session and must be made by the same app
  user; no cookie, the atproto mechanism adds no identity; `POST /atproto/unlink[?did=]` → 204/400/401/404). Pick
  `link` when the app has its own accounts (form/OIDC); QuarkPress uses `link`.
- In a request: `@Inject AtprotoUser user; user.records().create(Nsid, Map|record)` (blocking; `reactiveRecords()`
  for Mutiny), `user.did()/handle()/pds()/scope()/xrpc()`. Link mode: `did()` throws
  `IllegalStateException("no linked AT Protocol account")` or `("several linked accounts; use forDid")`;
  `user.forDid(did)` picks a linked account. Ended link sessions throw `AtprotoSessionExpired` (re-link).
- Background jobs (no request context): `@Inject AtprotoSessions sessions; sessions.records(did)`,
  `reactiveRecords(did)`, `xrpc(did)`, `find(did)` (newest live session), `revoke(did)`. Catch
  `AtprotoSessionExpired` (no session / `invalid_grant`: ask the author to sign in again) and
  `AtprotoTemporarilyUnavailable` (outage, session kept: retry). Call from worker threads, not the event loop.
- Writes outside the granted scope fail with `AtprotoScopeException` before sending; request least-privilege scopes
  in `quarkus.atproto.oauth.scope` (e.g. `atproto repo:site.standard.document?action=create&action=update
  blob:image/*`). Granted scope may differ from requested: check `user.scope()`.
- Events: `AtprotoLoggedIn(did, handle, Optional<String> appPrincipal)`, `AtprotoLoggedOut(did, appPrincipal)`,
  `AtprotoSessionRevoked(did, reason)`; observers run on a worker thread.
- SPIs (in-memory `@DefaultBean`s WARN in prod): `AtprotoSessionStore`, `PendingAuthorizationStore`,
  `AtprotoAccountLinks` (+ `Reactive*`); encrypt tokens and DPoP keys at rest; `replaceIfRefreshToken` must be atomic.
- Roles: add `@ApplicationScoped AtprotoRoleMapper` beans (`Set<String> roles(Did, Handle)`); roles are united with
  the built-in `user`.
- Pitfalls: the localhost development client only works in dev/test; production needs
  `quarkus.atproto.oauth.client-base-url` (public https), `signing-key.location` (ES256 PEM/JWK) and
  `session.encryption-key` (≥ 32 chars, `login` mode). Tests against fakes set `quarkus.safe-fetch.https-only=false`
  and `quarkus.safe-fetch.allow-private-addresses=true`. Never log tokens, codes, keys or cookie values.
