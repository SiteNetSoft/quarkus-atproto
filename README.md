# quarkus-atproto

AT Protocol (Bluesky) building blocks for Quarkus: identity resolution, lexicon runtime and code generation, an XRPC
client, and an OAuth client. Client side only in this milestone.

Minimum supported Quarkus: **3.40 (LTS)**. Status: experimental, version 0.1.

## Modules

| Module | Artifact | Kind |
| --- | --- | --- |
| `atproto-syntax` | `atproto-syntax` | plain library |
| `atproto-lexicon-codegen` | `atproto-lexicon-codegen` | plain library (JavaPoet generator) |
| `xrpc/api` | `atproto-xrpc-api` | plain library |
| `lexicons/core` | `atproto-lexicons-core` | lexicon pack (`com.atproto.*` subset) |
| `lexicons/standard-site` | `atproto-lexicons-standard-site` | lexicon pack |
| `identity` | `quarkus-atproto-identity` | extension |
| `lexicon` | `quarkus-atproto-lexicon` | extension |
| `xrpc` | `quarkus-atproto-xrpc` | extension |
| `oauth` | `quarkus-atproto-oauth` | extension |
| `test` | `quarkus-atproto-test` | test support |
| `bom` | `quarkus-atproto-bom` | BOM |

## OAuth: known limitations

- **One sign-in in progress per browser.** The login endpoint binds each started sign-in to the browser with a
  short-lived cookie (`<session.cookie-name>-binding`, path = the callback path, lifetime `pending.ttl`) that the
  callback must present (protection against login CSRF). There is one such cookie per browser, so when a user starts
  a second sign-in (for example in another tab) before finishing the first, the first one's callback fails with 401
  and the user signs in again.
- **Refresh during an outage.** Before spending a refresh token the extension checks that the session's
  authorization server still protects the account's current PDS. When the DID directory or the metadata cannot be
  reached, the call fails with `AtprotoTemporarilyUnavailable` and the session is kept; retry later.

## Building

Depends on sibling checkouts installed to the local repository, in this order: `multikey-java`, `quarkus-safe-fetch`,
`quarkus-did` (quarkus-did depends on safe-fetch).

```
mvn -T 8 install
```

## License

Apache License 2.0.
