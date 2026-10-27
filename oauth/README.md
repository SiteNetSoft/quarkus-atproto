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

## Sign-in modes

`quarkus.atproto.oauth.mode` (runtime, default `login`):

- **`login`**: AT Protocol OAuth *is* the application's sign-in. `GET|POST /atproto/login?handle=...` starts it; after
  the callback the extension sets its encrypted session cookie and its authentication mechanism produces the
  `SecurityIdentity` (principal = the DID, attributes `atproto.handle`, `atproto.pds`, `atproto.scope`, role `user`
  plus whatever your `AtprotoRoleMapper` beans add). `POST /atproto/logout` revokes (best effort), deletes the session
  and clears the cookie.
- **`link`**: the user is already signed in through the application's own mechanism (form, OIDC, basic, anything
  producing a `SecurityIdentity`) and links a Bluesky account to that user.
  - The login endpoint requires that identity (401 otherwise, nothing is sent anywhere). The started login records
    the application user, and the callback must be made by **the same user** (and, as in `login` mode, by the browser
    that started it: the binding cookie), so an account cannot be linked to someone else.
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

## Dev UI

The card shows the client type and `client_id`, the served client metadata, the active sessions (DID, handle, PDS,
scope, access token expiry; never tokens; listed for the in-memory store only) and a sign-in box that opens the login
endpoint.
