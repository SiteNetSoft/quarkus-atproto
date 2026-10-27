# integration-tests: the sample application

A small Quarkus application using `quarkus-atproto-oauth` and `atproto-lexicons-standard-site`, and the end-to-end
tests that run it packaged (`@QuarkusIntegrationTest`) against the fakes of `quarkus-atproto-test`.

## The application (`org.acme.atproto`)

- `GET /` (`Pages`): a page with the sign-in form (`POST /atproto/login`, field `handle`) and forms to publish.
- `GET /me`: principal, DID, handle, PDS and granted scope of the signed-in account (401 anonymous; 409 in link mode
  when no account is linked).
- `POST /publish/publication?name&url`: creates a `site.standard.publication`; remembered per account.
- `POST /publish/document?title&path&text[&site]`: creates a `site.standard.document` whose `site` is that
  publication's `at://` URI (409 when there is none). Both accept a form body too.
- Scope: `atproto repo:site.standard.publication?action=create&action=update repo:site.standard.document?action=create&action=update blob:image/*`.
- Profile `link`: `quarkus.atproto.oauth.mode=link` with an embedded basic-auth user `ann` / `ann-password` standing in
  for the application's own sign-in (basic auth is build-time configuration, so it is enabled in every profile; only
  `link` has users).

Dev mode (`mvn quarkus:dev -pl integration-tests` at the repository root) uses the localhost development client: sign
in with a real Bluesky account, see `docs/modules/ROOT/pages/live-check.adoc`.

## The tests

- `LoginAndPublishIT` (failsafe execution `login-mode`): page → sign-in (handle typed as `@Alice.Example.Test`) →
  fake authorization server → callback → `/me` → publication → document (form body) → the fake PDS holds a
  `site.standard.document` whose `site` is the publication's AT-URI and which reads back as `site.standard.Document`;
  logout. Plus: `GET` login → 405, cross-site `POST` → 403 without any PAR, anonymous → 401.
- `LinkModeIT` (execution `link-mode`, `quarkus.test.integration-test-profile=link`): signed in as `ann` with basic
  auth, link → `/me` → publish → unlink; an anonymous link attempt → 401.

The packaged application runs in a separate process in **normal launch mode**, so the tests wire it like a production
deployment whose URLs point at loopback fakes. The test resource `AtprotoFakes` starts `FakeAtproto` and `FakeDns` in
the test JVM and returns the configuration the application is launched with:

| Property | Value |
| --- | --- |
| `quarkus.atproto.oauth.client-base-url` | `https://app.example.test` (a confidential client: the development client is refused in normal mode) |
| `quarkus.atproto.oauth.signing-key.location` | an ES256 key generated per run (`target/it-client-key.pem`) |
| `quarkus.atproto.oauth.session.encryption-key` | random per run |
| `quarkus.did.plc.url` | the fake PLC directory |
| `quarkus.atproto.identity.dns.servers` | the `FakeDns` (`_atproto.alice.example.test` TXT); the well-known override is ignored in normal mode |
| `quarkus.safe-fetch.https-only`, `quarkus.safe-fetch.allow-private-addresses` | `false`, `true` |

The fake authorization server fetches `https://app.example.test/oauth-client-metadata.json` and the JWKS from
`http://127.0.0.1:<quarkus.http.test-port>` (`rewriteClientFetches`). The port is reserved by
`build-helper-maven-plugin:reserve-network-port` and passed to failsafe as `quarkus.http.test-port`. The tests call
the callback URL's path and query on that port with the binding cookie, and send the login form with
`Origin: https://app.example.test`.

Native: `mvn verify -Dnative -pl integration-tests -am -Dquarkus.native.additional-build-args=-J-XX:TieredStopAtLevel=1,--parallelism=8`
runs both executions against the native executable (CI's native job).
