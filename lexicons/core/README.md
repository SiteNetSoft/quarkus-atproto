# atproto-lexicons-core

The `com.atproto.*` Lexicons this project needs, shipped under `META-INF/atproto/lexicons/` together with the
Java types generated from them at this pack's build (`com.atproto.repo.CreateRecord`, `StrongRef`, `RepoClient`, ...)
and `META-INF/atproto/lexicons/index.json` (NSID#def → generated class), which dependent packs and apps read so they
refer to these classes instead of generating them again.

## Lexicon snapshot

Copied verbatim from `bluesky-social/atproto`, directory `lexicons/`, at commit
`66ce7c5fde851be67c0b36362ef75ea3b0a46df6` (the `main` head on 2026-10-26). Refresh (from
`src/main/resources/META-INF/atproto/lexicons/`, after choosing a new `SHA`):

    SHA=66ce7c5fde851be67c0b36362ef75ea3b0a46df6
    for n in com.atproto.repo.createRecord com.atproto.repo.putRecord com.atproto.repo.getRecord \
             com.atproto.repo.deleteRecord com.atproto.repo.uploadBlob com.atproto.repo.describeRepo \
             com.atproto.repo.listRecords com.atproto.repo.strongRef com.atproto.repo.defs \
             com.atproto.identity.resolveHandle com.atproto.label.defs com.atproto.server.describeServer; do
      p=$(echo $n | tr . /).json
      mkdir -p "$(dirname "$p")"
      curl -sf -o "$p" "https://raw.githubusercontent.com/bluesky-social/atproto/$SHA/lexicons/$p"
    done

The same files are the fixtures of `atproto-lexicon-codegen` (`src/test/resources/lexicons/`); keep both in step.

## Generation

`exec-maven-plugin:java` runs `org.sitenetsoft.atproto.lexicon.codegen.Cli` in `generate-sources` into
`target/generated-sources/lexicon` (the index into `target/generated-resources/lexicon`); `build-helper-maven-plugin`
adds both to the build. The generator is a plugin dependency only, so it never reaches this pack's consumers.

## Test records

`src/test/resources/records/strongref.json` is a real `com.atproto.repo.strongRef`: the `bskyPostRef` of
`at://did:plc:g7j6qok5us4hjqlwjxwrrkjm/site.standard.document/3mmwlyios226v` (PDS `https://blacksky.app`), fetched
2026-10-26 with `com.atproto.repo.listRecords`. It points at
`at://did:plc:g7j6qok5us4hjqlwjxwrrkjm/app.bsky.feed.post/3mmwlyioqc46v`.
