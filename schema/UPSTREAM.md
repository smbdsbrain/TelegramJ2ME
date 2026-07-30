# Telegram TL schema

Verbatim copies of Telegram's published TL schema. Nothing here is hand-edited:
both files are byte-for-byte what the URLs below served.

| | `api.json` | `mtproto.json` |
|---|---|---|
| Source | https://core.telegram.org/schema/json | https://core.telegram.org/schema/mtproto-json |
| Retrieved | 2026-07-27 | 2026-07-27 |
| SHA-256 | `287817f49fea78191c5f4342ad683ba2aed6c3bc2acc89757fc64f68ea24d846` | `0d01d96b7df87fe76ea6d034380644ce138e8ba092d4ad2a00e041ff89509b59` |
| Contents | 1546 constructors, 757 methods | 38 constructors, 10 methods |

These are protocol definitions - constructor ids, method names, field names and
field types. They contain no values of any kind, so nothing here is sensitive.

## Layer 223

**The layer number is not in the JSON.** Telegram serves the schema without one,
so it is pinned by hand in [`src/tg/mt/Layer.java`](../src/tg/mt/Layer.java) as
`LAYER = 223`, read from there by `tools/generate-tl.py` (`detect_layer()`) and
sent in every `invokeWithLayer`.

That means the pairing is asserted, not checked: nothing verifies that these
files really are layer 223. Getting it wrong is not a build error - the server
would simply interpret requests against a layer we are not speaking. When you
refresh the schema, update `Layer.java` in the same commit.

## Re-verifying

The files are unmodified downloads, so the sha256 above is the whole check:

```powershell
Get-FileHash schema/api.json, schema/mtproto.json -Algorithm SHA256
```

To compare against what Telegram serves today:

```powershell
Invoke-WebRequest https://core.telegram.org/schema/json -OutFile live-api.json -UseBasicParsing
Get-FileHash live-api.json -Algorithm SHA256
```

A mismatch is expected once Telegram moves past layer 223 - the URL always
serves the *current* layer, which is exactly why these files are pinned here
rather than fetched at build time, and why `tools/sdk.lock.json` does not carry
them: a lock entry against a moving URL would break every clone the day the
layer changes.

Last confirmed identical to the live URLs: 2026-07-30.

## What is generated from this

`tools/generate-tl.py` reads both files plus
[`config/tl-whitelist.txt`](../config/tl-whitelist.txt) and emits
`generated/tg/api/{Api,TlSchema}.java` - the whitelisted methods and the
constructor closure they reach: currently 34 methods pulling in 864 of the 1582
constructors the two files define between them (1546 + 38, less `message` and
`vector`, which appear in both). The output is deterministic and gitignored;
`tools/bootstrap.ps1` regenerates it.

`tools/verify-tl-ids.py` is a narrower check and does not validate the schema.
It confirms that the ~38 MTProto handshake constructor ids hand-written in
[`src/tg/tl/Tl.java`](../src/tg/tl/Tl.java) match these files, because those are
the only ids in the project that are not generated.
