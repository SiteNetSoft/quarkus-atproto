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

## Building

Depends on sibling checkouts installed to the local repository, in this order: `multikey-java`, `quarkus-safe-fetch`,
`quarkus-did` (quarkus-did depends on safe-fetch).

```
mvn -T 8 install
```

## License

Apache License 2.0.
