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

That means the pairing is asserted, not proven: nothing can verify that these
files really are layer 223, because the number is not in them. Getting it wrong
is not a build error - the server would simply interpret requests against a
layer we are not speaking. When you refresh the schema, update `Layer.java` in
the same commit.

What *is* checked is that the assertion stays internally consistent and that
someone finds out when Telegram moves; see [Re-verifying](#re-verifying).

## Re-verifying

`tools/check-schema-drift.py` does all of this. Offline it checks the files
against the hashes, counts and layer recorded above - which catches a
half-finished refresh with no network at all - and `--online` additionally asks
Telegram:

```powershell
python tools/check-schema-drift.py            # repository self-consistency
python tools/check-schema-drift.py --online   # + core.telegram.org
```

It exits `0` when everything agrees, `10` on drift, `20` when upstream could not
be reached, `30` when upstream answered with something unparseable and `40` when
this repository disagrees with itself. The offline check runs in the ordinary
test suite; the online one runs weekly from
[`.github/workflows/schema-drift.yml`](../.github/workflows/schema-drift.yml)
and on manual dispatch, and it maintains one deduplicated issue. Neither ever
edits `Layer.java` or the files here.

By hand, the sha256 above is the whole check, because the files are unmodified
downloads:

```powershell
Get-FileHash schema/api.json, schema/mtproto.json -Algorithm SHA256
```

The URLs always serve the *current* layer, which is exactly why these files are
pinned here rather than fetched at build time, and why `tools/sdk.lock.json`
does not carry them: a lock entry against a moving URL would break every clone
the day the layer changes.

Last confirmed identical to the live URLs: 2026-08-08.

### The layer number and the JSON move separately

`https://core.telegram.org/api/config.json` reported layer **225** on
2026-08-08, while both JSON schema URLs still served byte-for-byte what is
pinned here at layer 223. The machine-readable schema lags the layer number,
sometimes by weeks.

So a layer difference on its own is not something to act on: raising
`Layer.LAYER` to 225 while generating from a layer-223 document would claim a
layer whose constructor ids this build does not have, which is the exact failure
the pinning exists to prevent. Wait for the JSON to change, or take the
difference from the `.tl` text schema deliberately and by hand.

## Upgrading the layer

Not automated, and not a step in any build. A new layer can change the
constructor id of a type already parsed, and the symptom on a handset with no
debugger is a client that quietly misreads responses.

On a branch:

1. Download both files from the URLs above, unmodified.
2. Update the table and the `## Layer` heading here: hashes, constructor and
   method counts, retrieved date.
3. Set `LAYER` in [`src/tg/mt/Layer.java`](../src/tg/mt/Layer.java) in the same
   commit.
4. `./tools/bootstrap.ps1` to regenerate, then `python tools/verify-tl-ids.py`
   for the hand-written MTProto ids and `python tools/check-schema-drift.py` for
   the record above.
5. `./tools/test.ps1`, then `./tools/build.ps1 -Target tg` normal and `-Release`,
   and record the JAR size delta - the generated closure grows with the schema.
6. Sign in and send a message against a real account before merging. The
   desktop suite cannot tell you that the server accepted the layer.

## What is generated from this

`tools/generate-tl.py` reads both files plus
[`config/tl-whitelist.txt`](../config/tl-whitelist.txt) and emits
`generated/tg/api/{Api,TlSchema}.java` - the whitelisted methods and the
constructor closure they reach: currently 37 methods pulling in 882 of the 1582
constructors the two files define between them (1546 + 38, less `message` and
`vector`, which appear in both). The output is deterministic and gitignored;
`tools/bootstrap.ps1` regenerates it.

`tools/verify-tl-ids.py` is a narrower check and does not validate the schema.
It confirms that the ~38 MTProto handshake constructor ids hand-written in
[`src/tg/tl/Tl.java`](../src/tg/tl/Tl.java) match these files, because those are
the only ids in the project that are not generated.
