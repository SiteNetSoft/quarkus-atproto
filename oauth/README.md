# quarkus-atproto-oauth

An AT Protocol [OAuth](https://atproto.com/specs/oauth) client for Quarkus: PAR, PKCE, DPoP and `private_key_jwt`
(confidential client) or the localhost development client in dev mode. Users sign in with their Bluesky (or any
AT Protocol) account; the application then writes records to their repository on their PDS.

Status: experimental, version 0.1.

```xml
<dependency>
  <groupId>org.sitenetsoft.quarkus</groupId>
  <artifactId>quarkus-atproto-oauth</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Client identity

- **Confidential client** (production; whenever `client-base-url` is a non-loopback URL): `client_id` =
  `<client-base-url><metadata-path>`, a client metadata document served by the extension (with `jwks_uri`, also
  served), authenticated with `private_key_jwt` (ES256). Needs `signing-key.location` and `session.encryption-key`;
  startup fails naming the property when one is missing. The metadata and JWKS are served under
  `quarkus.http.root-path`: include it in `client-base-url`.
- **Localhost development client** (dev and test mode when `client-base-url` is unset or loopback): `client_id` =
  `http://localhost?redirect_uri=http://127.0.0.1:<port><callback-path>&scope=<scope>`, a public client without keys.
  bsky.social accepts it, so a developer signs in with a real account from a laptop (see the docs' live check). Refused
  in production (normal launch mode). In dev mode the metadata path answers 404.

The registered scope (metadata `scope` or the development `client_id`'s) is `scope` plus `fallback-scope`: an
authorization server grants only what the client registered. The **granted** scope can be narrower than the request;
the session records it (`AtprotoUser.scope()`) and writes are checked against it.

## Sign-in modes

`quarkus.atproto.oauth.mode` (runtime, default `login`):

- **`login`**: AT Protocol OAuth *is* the application's sign-in. A `POST /atproto/login` (form `handle`, optional
  `redirect`) from the application's own page starts it (see "Starting a sign-in" below); after
  the callback the extension sets its encrypted session cookie and its authentication mechanism produces the
  `SecurityIdentity` (principal = the DID, attributes `atproto.handle`, `atproto.pds`, `atproto.scope`, role `user`
  plus whatever your `AtprotoRoleMapper` beans add). `POST /atproto/logout` revokes (best effort), deletes the session
  and clears the cookie.
- **`link`**: the user is already signed in through the application's own mechanism (form, OIDC, basic, anything
  producing a `SecurityIdentity`) and links a Bluesky account to that user.
  - The login endpoint requires that identity (401 otherwise, nothing is sent anywhere). The started login records
    the application user, and the callback must be made by **the same user** and by the browser that started it
    (the binding cookie). That stops a callback URL from being completed by another user or browser; what stops
    another *site* from starting a link of the attacker's account in the victim's signed-in browser is the
    same-origin rule below.
  - The callback stores the session and a **link** application principal → DID → session (`AtprotoAccountLinks`);
    linking the same account again replaces the link's session. No session cookie is set, and the extension's
    authentication mechanism contributes no identity and no challenge: the application's own mechanism stays in
    charge.
  - `POST /atproto/unlink[?did=...]` (`quarkus.atproto.oauth.unlink-path`) revokes the session's refresh token
    (best effort), deletes the session and the link: 204; `did` is needed only when several accounts are linked
    (400 otherwise); 401 without an application user, 404 when the account is not linked to them. Protect it (and
    logout) against cross-site requests as you do your other state-changing endpoints.
  - A user can link several accounts; several users can link the same account (each link has its own session).

In each mode the other mode's endpoint (logout or unlink) is not served.

### Starting a sign-in

The login endpoint accepts only `POST` (form `handle`, optional local `redirect`), and only **same-origin**: when the
browser sends `Sec-Fetch-Site` it must be `same-origin`; otherwise `Origin` must be the application's own origin
(the origin of `client-base-url` for a confidential client, of the request's host for the development client); a
request with neither header is refused. Cross-site or header-less requests answer 403 (no pushed authorization
request, no cookie), `GET` answers 405 (`Allow: POST`). This prevents login CSRF: another site cannot make the
user's browser sign in (or, in `link` mode, link) an account the attacker controls. Serve the sign-in form from the
application itself:

```html
<form method="post" action="/atproto/login">
  <input name="handle" placeholder="alice.bsky.social"> <button>Sign in with Bluesky</button>
</form>
```

## Using the session in a request

`@Inject AtprotoUser user` (request scoped):

```java
@POST @Path("publish") @Authenticated
public String publish(@QueryParam("title") String title) {
    return user.records().create(Nsid.parse("site.standard.document"), Map.of(
            "site", "https://blog.example.com", "title", title,
            "publishedAt", Instant.now().toString())).uri().toString();
}
```

- `did()`, `handle()`, `pds()`, `scope()` (granted), `records()` / `reactiveRecords()` (DPoP, writes outside the
  granted scope refused with `AtprotoScopeException` before anything is sent), `xrpc()`.
- `link` mode on the **event loop** (an endpoint returning `Uni`) with an application link or session store: use
  `user.reactiveRecordsAsync()`, `user.didAsync()`, or `user.resolve()` first (loads the link and session without
  blocking and caches them for the request; then every accessor answers from memory). The synchronous accessors there
  fail fast with an `IllegalStateException` saying so instead of blocking. Blocking endpoints (worker threads) and the
  in-memory default stores need nothing special. A session past `session.max-age` counts as ended.
- `link` mode: `did()` is the single linked account; `IllegalStateException("no linked AT Protocol account")` when
  there is none and `IllegalStateException("several linked accounts; use forDid")` when there are several:
  `user.forDid(did).records()` picks one (refused unless it is linked to the current user). When the link's session
  has ended (revoked, refresh refused) the account methods other than `did()` throw `AtprotoSessionExpired`: ask the
  user to link the account again.

## Background work: `AtprotoSessions`

`@Inject AtprotoSessions sessions` (application scoped) works without a request or cookie, for example a scheduled
job publishing a post later:

```java
try {
    sessions.records(authorDid).create(Nsid.parse("site.standard.document"), document);
} catch (AtprotoSessionExpired e) {
    // no live session, or the refresh token was refused (invalid_grant): ask the author to sign in again
} catch (AtprotoTemporarilyUnavailable e) {
    // the DID directory or the authorization server metadata could not be reached; the session is kept: retry later
}
```

- `find(did)` → the account's most recently created session within `session.max-age` (an account may be signed in
  from several browsers, or linked to several users); `records(did)`, `reactiveRecords(did)`, `xrpc(did)` use it;
  `revoke(did)` ends every session of the account (revoked at the authorization server, best effort; fires
  `AtprotoSessionRevoked`).
- Access tokens are refreshed as in requests: one refresh per session in the process, compare-and-set on the refresh
  token across nodes, the issuer's authority over the account's PDS re-checked before a refresh token is spent.
- These methods read the session store: call them from worker or background threads, not the event loop.

## Events

CDI events, fired on a worker thread (an observer's failure is logged and does not undo anything):

- `AtprotoLoggedIn(Did did, Handle handle, Optional<String> appPrincipal)`: a new session; `appPrincipal` is the
  application user in `link` mode, empty in `login` mode.
- `AtprotoLoggedOut(Did did, Optional<String> appPrincipal)`: logout (`login` mode) or unlink (`link` mode).
- `AtprotoSessionRevoked(Did did, String reason)`: a session ended without the user asking here (refresh refused,
  failed checks, `AtprotoSessions.revoke`).

## Storage SPIs

In-memory `@DefaultBean`s that WARN once in production; provide your own `@ApplicationScoped` bean (blocking or
`Reactive*`) to persist across restarts and nodes. Implementations must encrypt tokens and DPoP private keys at rest.

- `AtprotoSessionStore` / `ReactiveAtprotoSessionStore`: `save`, `find(id)`, `findByDid`, `replaceIfRefreshToken`
  (atomic compare-and-set), `delete`.
- `PendingAuthorizationStore`: started logins between the login endpoint and the callback.
- `AtprotoAccountLinks` / `ReactiveAtprotoAccountLinks` (`link` mode): `link(appPrincipal, did, sessionId)`,
  `findByPrincipal(appPrincipal)`, `sessionFor(appPrincipal, did)`, `unlink(appPrincipal, did)`. A link outlives its
  session (see above).

## Configuration

| Property | Default | Purpose |
| --- | --- | --- |
| `quarkus.atproto.oauth.enabled` (build time) | `true` | When `false` no routes are registered |
| `quarkus.atproto.oauth.mode` | `login` | `login` or `link` |
| `quarkus.atproto.oauth.client-base-url` | none | Public base URL; makes this a confidential client. Required in production |
| `quarkus.atproto.oauth.client-name` | none | `client_name` in the client metadata |
| `quarkus.atproto.oauth.client-uri` | `client-base-url` | `client_uri` in the client metadata |
| `quarkus.atproto.oauth.logo-uri` | none | `logo_uri` in the client metadata |
| `quarkus.atproto.oauth.tos-uri` | none | `tos_uri` in the client metadata |
| `quarkus.atproto.oauth.policy-uri` | none | `policy_uri` in the client metadata |
| `quarkus.atproto.oauth.scope` | `atproto` | Scopes requested, space separated |
| `quarkus.atproto.oauth.fallback-scope` | none | Scope to retry PAR with once after `invalid_scope` (e.g. `atproto transition:generic`) |
| `quarkus.atproto.oauth.login-path` | `/atproto/login` | Login endpoint (same-origin `POST`) |
| `quarkus.atproto.oauth.callback-path` | `/atproto/oauth/callback` | OAuth callback (the redirect URI) |
| `quarkus.atproto.oauth.logout-path` | `/atproto/logout` | Logout endpoint (`login` mode) |
| `quarkus.atproto.oauth.unlink-path` | `/atproto/unlink` | Unlink endpoint (`link` mode) |
| `quarkus.atproto.oauth.metadata-path` | `/oauth-client-metadata.json` | Client metadata document; part of the `client_id` |
| `quarkus.atproto.oauth.jwks-path` | `/oauth/jwks.json` | The client's public JWKS (`jwks_uri`) |
| `quarkus.atproto.oauth.signing-key.location` | none | ES256 private keys (PKCS#8 PEM or JWK; file system or class path), comma separated; the first signs, all are published. Required for a confidential client |
| `quarkus.atproto.oauth.session.cookie-name` | `atproto-session` | Session cookie name (the binding cookie is `<name>-binding`) |
| `quarkus.atproto.oauth.session.encryption-key` | generated per run in dev and test | Key encrypting the session cookie, at least 32 characters. Required in production |
| `quarkus.atproto.oauth.session.max-age` | `14d` | How long a session lasts |
| `quarkus.atproto.oauth.pending.ttl` | `10m` | How long a started sign-in waits for its callback |
| `quarkus.atproto.oauth.landing-page` | `/` | Where to go after a sign-in without `redirect` |
| `quarkus.atproto.oauth.error-page` | none | Where to send a failed sign-in; unset: 401 with the reason |

## Testing

`quarkus-atproto-test` (test scope) has `FakeAtproto` (PLC directory, authorization server, PDS) and `FakeDns`.
In `QuarkusUnitTest` / `@QuarkusTest` the development client is used: replace `TxtResolver`, point
`quarkus.did.plc.url` at the fake, map handles with `quarkus.atproto.identity.https-well-known-override."<handle>"`
and relax safe-fetch (`https-only=false`, `allow-private-addresses=true`). A `@QuarkusIntegrationTest` runs the
packaged application in normal launch mode, so it is a confidential client there: see the `integration-tests` module
(`AtprotoFakes` test resource: `client-base-url`, a generated key, `FakeDns` via `quarkus.atproto.identity.dns.servers`,
`FakeAuthorizationServer.rewriteClientFetches` to reach the app's client metadata on loopback).

## Dev UI

The card shows the client type and `client_id`, the served client metadata, the active sessions (DID, handle, PDS,
scope, access token expiry; never tokens; listed for the in-memory store only, sessions past `session.max-age` left out) and a sign-in box that posts to
the login endpoint in a new tab.
