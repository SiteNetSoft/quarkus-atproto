# quarkus-atproto-oauth — skill notes for coding agents

- Modes (`quarkus.atproto.oauth.mode`, runtime): `login` (default; AT Protocol OAuth is the app's sign-in, principal =
  DID, cookie session, `POST /atproto/logout`) or `link` (the app's own mechanism signs users in; `/atproto/login`
  needs that identity, else 401 (and same-origin POST, see below); the callback links app principal → DID → session and must be made by the same app
  user; no cookie, the atproto mechanism adds no identity; `POST /atproto/unlink[?did=]` → 204/400/401/404). Pick
  `link` when the app has its own accounts (form/OIDC); QuarkPress uses `link`.
- Starting a sign-in: only `POST {login-path}` (form `handle`, optional `redirect`) from the app's own page;
  same-origin required (`Sec-Fetch-Site: same-origin`, else `Origin` = the app's origin; neither → 403); `GET` → 405.
  Never link to the login endpoint with `<a href>`: render a form.
- Reactive endpoints in link mode with an app store: `user.reactiveRecordsAsync()` / `user.didAsync()` /
  `user.resolve().map(u -> ...)`; the sync accessors throw on the event loop instead of blocking.
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
- Scopes must be registered: the client metadata (or development `client_id`) carries `scope` + `fallback-scope`, and
  the authorization server refuses anything else. `invalid_scope` from bsky.social → set
  `quarkus.atproto.oauth.fallback-scope=atproto transition:generic`. Always read the granted scope (`user.scope()`).
- Dev mode: open the app at `http://127.0.0.1:<port>` (the development client's redirect URI host) so the sign-in's
  binding cookie reaches the callback; a 401 at the callback means another browser/host/tab or an expired sign-in.
- Packaged-app tests (`@QuarkusIntegrationTest`) run in normal launch mode: configure a confidential client
  (`client-base-url`, a generated key, `session.encryption-key`) and let the fake authorization server reach the
  metadata with `FakeAuthorizationServer.rewriteClientFetches(base, "http://127.0.0.1:<port>")`; see
  `integration-tests/.../AtprotoFakes.java`.
