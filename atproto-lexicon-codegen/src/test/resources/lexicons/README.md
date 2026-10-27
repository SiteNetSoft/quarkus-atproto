# Lexicon fixtures

Snapshot of the Lexicon JSON used by this module's tests (and copied into the
`atproto-lexicons-core` and `atproto-lexicons-standard-site` packs). Fetched on 2026-10-26;
tests never touch the network.

## `com/atproto/**` — core pack

Copied verbatim from `bluesky-social/atproto`, directory `lexicons/`, at commit
`66ce7c5fde851be67c0b36362ef75ea3b0a46df6` (the `main` head on 2026-10-26):

    SHA=66ce7c5fde851be67c0b36362ef75ea3b0a46df6
    for n in com.atproto.repo.createRecord com.atproto.repo.putRecord com.atproto.repo.getRecord \
             com.atproto.repo.deleteRecord com.atproto.repo.uploadBlob com.atproto.repo.describeRepo \
             com.atproto.repo.listRecords com.atproto.repo.strongRef com.atproto.repo.defs \
             com.atproto.identity.resolveHandle com.atproto.label.defs com.atproto.server.describeServer; do
      p=$(echo $n | tr . /).json
      mkdir -p "$(dirname "$p")"
      curl -sf -o "$p" "https://raw.githubusercontent.com/bluesky-social/atproto/$SHA/lexicons/$p"
    done

## `site/standard/**` — Standard.site pack

The eight `com.atproto.lexicon.schema` records published by `did:plc:re3ebnp5v7ffagz6rb6xfei4`
(`at://standard.site`). Its PDS, from `https://plc.directory/did:plc:re3ebnp5v7ffagz6rb6xfei4`, was
`https://auriporia.us-west.host.bsky.network`:

    curl -s 'https://auriporia.us-west.host.bsky.network/xrpc/com.atproto.repo.listRecords?repo=did:plc:re3ebnp5v7ffagz6rb6xfei4&collection=com.atproto.lexicon.schema&limit=100'

Each record's `value` was written to `<nsid path>.json` with the record wrapper's `$type`
(`com.atproto.lexicon.schema`) removed, keeping `lexicon`, `id`, optional `description` and `defs`
(2-space indent). Record CIDs at fetch time:

| NSID | CID |
|---|---|
| site.standard.publication | bafyreiapvnarfn3nl2wvg27hsylli5zqvou7a74p7od5mu4detprtejapy |
| site.standard.document | bafyreifylc5lfr4vqfzrwdtqm4pnfg7cbra7z4acbgikylqandzefwdeaq |
| site.standard.graph.subscription | bafyreiccyb5e4vq3rwhrajeud72ztezljufzrqbo73vz6mw2jwbgsxfqta |
| site.standard.graph.recommend | bafyreibglev4uvatb5do5obqxlif5w6b63tysjb7ve6w44x54g7hfe2rgy |
| site.standard.theme.basic | bafyreiabm6f64zosv5jcdmwd3qvekjhoi7enevg53rxj734ney4sd6lmku |
| site.standard.theme.color | bafyreicps42z5maxdqwml7t3klsrrpk332jthyfgpeb4zjn3wlvp5vyjia |
| site.standard.authFull | bafyreicwk5myf4dwjr6kpfy4qiko5mcwltk6kmnewbcvvdu2le3sbgpm4e |
| site.standard.authSocial | bafyreibdamyqel7hurrk66bbpvrqgf6cby3kmpnchvwrkjb5rijgkrdsve |
