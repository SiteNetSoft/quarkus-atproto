# atproto-lexicons-standard-site

The [Standard.site](https://standard.site) Lexicons (`site.standard.*`), shipped under `META-INF/atproto/lexicons/`
together with the Java types generated from them at this pack's build (`site.standard.Document`, `Publication`,
`graph.Subscription`, ...) and `META-INF/atproto/lexicons/index.json`. `authFull` and `authSocial` are permission
sets: shipped as JSON for `include:` scopes, not generated into classes.

## Lexicon snapshot

The eight `com.atproto.lexicon.schema` records published by `did:plc:re3ebnp5v7ffagz6rb6xfei4` (`at://standard.site`),
fetched 2026-10-26. Its PDS, from `https://plc.directory/did:plc:re3ebnp5v7ffagz6rb6xfei4`, was
`https://auriporia.us-west.host.bsky.network`:

    curl -s 'https://auriporia.us-west.host.bsky.network/xrpc/com.atproto.repo.listRecords?repo=did:plc:re3ebnp5v7ffagz6rb6xfei4&collection=com.atproto.lexicon.schema&limit=100'

Each record's `value` was written to `<nsid path>.json` with the record wrapper's `$type`
(`com.atproto.lexicon.schema`) removed, keeping `lexicon`, `id`, optional `description` and `defs` (2-space indent).
Record CIDs at fetch time:

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

The same files are the fixtures of `atproto-lexicon-codegen` (`src/test/resources/lexicons/`); keep both in step.

## Generation

As in the core pack (`exec-maven-plugin:java` running the generator's `Cli` in `generate-sources`), plus
`--dependency-index` pointing at the core pack: `maven-dependency-plugin:properties` (phase `initialize`) sets
`${org.sitenetsoft.quarkus:atproto-lexicons-core:jar}` to the core artifact this build resolved — the installed jar,
the reactor's packaged jar, or core's `target/classes` when the reactor stops before `package` — and the generator
reads `META-INF/atproto/lexicons/index.json` from it. Refs to `com.atproto.*` defs therefore use the core pack's
classes (`com.atproto.repo.StrongRef`, `com.atproto.label.Defs.SelfLabels`).

## Test records

Real records, fetched 2026-10-26 with `com.atproto.repo.listRecords` from each author's PDS (found through
`com.atproto.sync.listReposByCollection?collection=site.standard.document` on `relay1.us-east.bsky.network`);
each file is the record's `value`, re-indented:

| File | AT-URI | CID | PDS |
|---|---|---|---|
| `document-pckt-labels.json` | `at://did:plc:xtuer24wmkwitvr2ajjeewra/site.standard.document/3mk6kki3i2skf` | `bafyreibeohnt2sfmux6cf55izgfrd7wiprerhtfsxltcnayub5k5o2xekq` | `https://northsky.social` |
| `document-wordpress-bskypostref.json` | `at://did:plc:g7j6qok5us4hjqlwjxwrrkjm/site.standard.document/3mmwlyios226v` | `bafyreifvqvnobbnhfoakh3vq2newb6cpyxs76ork5txjb26l3bgetmw55q` | `https://blacksky.app` |
| `publication-sharpie.json` | `at://did:plc:g7j6qok5us4hjqlwjxwrrkjm/site.standard.publication/3mn6tnsqaiv2x` | `bafyreibydsho7k3hbxe4s46tgrddi32sucxdrsl5w6jvqmtznkudjqzr2e` | `https://blacksky.app` |
| `document-langs.json` | `at://did:plc:v46ojbiop5ebs5h7gaomixcc/site.standard.document/3mx6teewqchiu` | `bafyreifuho5puz2r3o5pvi3os67i6wszk4xubhh5bhpi35eucminebj7nm` | `https://pds.pckt.cafe` |
| `document-visibility.json` | `at://did:plc:e5zplwgiznavptdxhx5n2zlp/site.standard.document/3mbfxw2mchy2b` | `bafyreibf3iox63fgtmgubsitjnrvmd7bfxjag2ejn4t2odnehg5b7vnflu` | `https://enoki.us-east.host.bsky.network` |
| `document-canonicalurl.json` | `at://did:plc:re3ebnp5v7ffagz6rb6xfei4/site.standard.document/3mmdfzlqhvs2h` | `bafyreicnqoi53ts4k7wxov5trhhbecdpi3sqlrgezwqd2vtmlqh5zdul6i` | `https://auriporia.us-west.host.bsky.network` |
| `publication-leaflet-theme-prefs.json` | `at://did:plc:xtuer24wmkwitvr2ajjeewra/site.standard.publication/3lybvppsj7c2s` | `bafyreiglb5uo62jfmiiiq22lbqg3v64npytwya3k2b5u7lpfnj72ju3v2i` | `https://northsky.social` |
| `publication-typed-prefs-theme.json` | `at://did:plc:h3wpawnrlptr4534chevddo6/site.standard.publication/3mcvwkm4dxh2z` | `bafyreic77ptqe423uwh2jrtwxoxcj2z54xgvxftlrexdj2ub2pia2prw5e` | `https://airlock.ltd` |

They cover self-labels, an open-union `content` (pckt.blog), cover-image blobs, a `bskyPostRef` strong ref, a
`basicTheme` with `theme.color#rgb` union members and an icon blob, and properties the Lexicons do not declare,
which must survive a read-then-write: `langs`, `visibility` and `canonicalUrl` on documents, `theme` on publications,
extra Leaflet keys (`showComments`, `showPrevNext`, ...) and an optional `$type` on the plain `#preferences` object.
The last five rows were fetched 2026-10-27 (the Standard.site document with `com.atproto.repo.getRecord`).
