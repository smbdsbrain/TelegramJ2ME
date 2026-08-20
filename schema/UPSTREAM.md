# Telegram TL schema

The API schema is pinned from Telegram Desktop's official TL source and
converted to JSON locally. The MTProto schema remains a verbatim copy of
Telegram's public JSON endpoint. Ordinary builds use only these committed files.

| | `api.json` | `mtproto.json` |
|---|---|---|
| Source | derived from committed `api.tl` by `tools/import-tl-schema.py` | https://core.telegram.org/schema/mtproto-json |
| Retrieved | 2026-08-20 | 2026-07-27 |
| SHA-256 | `0a067b20e244b41104f61ba5f9315d5896c33232ebdda8583a0c1a35fce6c9bc` | `0d01d96b7df87fe76ea6d034380644ce138e8ba092d4ad2a00e041ff89509b59` |
| Contents | 1573 constructors, 782 methods | 38 constructors, 10 methods |

Pinned API TL commit: `bff17504bc96df235559211d97ea616506f22889`

Pinned API TL SHA-256: `aa21644954119b6b8be10c839c24cbbeff389d7158f65a5a0419887014f89a93`

Pinned API TL contents: 1573 constructors, 782 methods

Known lagging API endpoint SHA-256: `287817f49fea78191c5f4342ad683ba2aed6c3bc2acc89757fc64f68ea24d846`

The verbatim source is
[`Telegram/SourceFiles/mtproto/scheme/api.tl`](https://github.com/telegramdesktop/tdesktop/blob/bff17504bc96df235559211d97ea616506f22889/Telegram/SourceFiles/mtproto/scheme/api.tl).
The commit is the final layer-225 schema snapshot before Telegram Desktop moved
to layer 226. Its `// LAYER 225` marker is checked against `Layer.LAYER`.

These are protocol definitions: constructor ids, method names, field names and
field types. They contain no account data or credentials.

## Layer 225

`src/tg/mt/Layer.java` pins `LAYER = 225`. `tools/generate-tl.py` writes the
same number into generated sources, and the build verifies that the two agree.

Telegram's public API JSON endpoint does not carry a layer marker and currently
lags the production configuration: on 2026-08-20 `config.json` reported layer
225 while `/schema/json` still served the documented layer-223 hash above.
Raising the number without a matching schema would be unsafe, so this upgrade
uses the reviewed official Telegram Desktop TL source rather than its layer-228
tip. `tools/import-tl-schema.py --check schema/api.tl schema/api.json` proves the
committed JSON is exactly the deterministic conversion of that source.

## Re-verifying

All ordinary checks are offline:

```powershell
python tools/import-tl-schema.py --check schema/api.tl schema/api.json
python tools/check-schema-drift.py
python tools/verify-tl-ids.py
```

`tools/check-schema-drift.py --online` additionally asks Telegram's official
`config.json`, API JSON and MTProto JSON endpoints. It accepts either the pinned
layer-225 API JSON hash or the one documented lagging hash while production
still reports layer 225. Any other API hash, a different live layer or an
MTProto change is drift. The known lag allowance is exact, so an endpoint
change cannot pass silently.

The monitor exits `0` when the pin is internally consistent and current, `10`
on drift, `20` when upstream could not be reached, `30` for an unreadable
response and `40` when the repository disagrees with its provenance record.
The online check runs only in `.github/workflows/schema-drift.yml`; it never
rewrites protocol files.

## Layer-225 validation

On 2026-08-20 the production normal JAR was 583026 bytes, 191 bytes above the
layer-223 baseline; the minified JAR was 415208 bytes, 178 bytes above its
baseline. Both passed the 32 MiB packaged smoke test, CLDC audit, production
config/dialogs/reactions/forum checks, ordinary two-account packaged E2E and
the fragmented slow-network variant. Every remote probe cleanup was confirmed.

## Upgrading the layer

A layer upgrade is reviewed rather than fetched by a build:

1. Confirm the production layer through the official `config.json`.
2. Pin the final official Telegram Desktop commit for that exact layer and
   verify its full commit id, TL marker and SHA-256.
3. Convert the committed TL file to JSON offline and update this record and
   `Layer.java` together.
4. Regenerate, verify hand-written TL ids, run the complete test/build/audit
   gate, measure both JAR variants and run production live/e2e scenarios.

## What is generated from this

`tools/generate-tl.py` reads `api.json`, `mtproto.json` and
`config/tl-whitelist.txt`, then emits `generated/tg/api/{Api,TlSchema}.java`.
The whitelist currently exposes 41 methods and pulls 897 of the 1609 unique
constructors into the transitive closure. Generated field constants protect
the application from field-index shifts in constructors such as `user`,
`message`, `dialog`, `forumTopic` and `userFull`.

`tools/verify-tl-ids.py` separately checks the hand-written MTProto constructor
ids in `src/tg/tl/Tl.java`; application-layer ids remain generated.
