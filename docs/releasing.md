# Releasing

Releases are built by [.github/workflows/release.yml](../.github/workflows/release.yml)
on a `v*` tag and published as JAR/JAD pairs on the GitHub Releases page.

## One-time repository setup

Two Actions secrets, from [my.telegram.org](https://my.telegram.org):

```powershell
gh secret set TG_API_ID    --body "1234567"
gh secret set TG_API_HASH  --body "..."
```

`tools/_env.ps1` reads these ahead of `secrets/telegram.yaml`, so the workflow never
writes credentials to the runner's disk.

They are not optional for a release. Without them the build still succeeds, but
`Secrets.CONFIGURED` is `false` and the published client cannot talk to Telegram at
all. CI (`ci.yml`) deliberately runs without them, which is why pull requests from
forks work.

> A third-party client necessarily ships its `api_id`/`api_hash` inside the binary —
> anyone can extract them from a published JAR. That is inherent, not a mistake, but
> the pair is bound to the Telegram account that registered the application, so
> abuse of the released client lands on that account.

## Cutting a release

1. Bump `$AppVersion` in [tools/build.ps1](../tools/build.ps1). It is the single
   source of truth: it becomes `MIDlet-Version` in the JAD and `BuildInfo.VERSION`
   in the JAR.
2. Commit it.
3. Tag and push:

   ```powershell
   git tag v0.2.0
   git push origin main --tags
   ```

The workflow refuses to publish if the tag and `$AppVersion` disagree. That check
exists because the JAD is what the phone believes: a mistyped tag would otherwise
install a MIDlet whose version contradicts the release it came from.

## What gets published

| Asset | Build |
|---|---|
| `TelegramJ2ME-<version>.jar` / `.jad` | `-Target tg -Env production` |
| `TelegramJ2ME-<version>-min.jar` / `.jad` | the same, plus `-Release` (optimise + obfuscate) |
| `SHA256SUMS.txt` | checksums for all four files |

`-Env production` is what makes the build talk to real data centres; the default is
Telegram's test DCs, which hold no real accounts. An `auth_key` is bound to one
environment, so this cannot be corrected at runtime.

Before publishing, the workflow reruns the desktop suite and then re-reads each
generated JAD to confirm `MIDlet-Jar-URL` resolves to a file that exists and that
`MIDlet-Jar-Size` matches its byte length exactly. A one-byte disagreement makes the
handset abort the install with an error that names nothing useful.

## Dry run

`workflow_dispatch` on the Actions page runs the whole build and uploads the
artifacts, but skips the publish job. Use it to download and install the exact files
a release would contain before committing to a tag.

## Before the first release of a new variant

`run-emulator.ps1` accepts `-ArtifactName`, so the obfuscated build can be launched
directly:

```powershell
./tools/build.ps1 -Target tg -Env production -Release -ArtifactName tg-min
./tools/run-emulator.ps1 -Target tg -ArtifactName tg-min
```

Worth doing by hand — sign in, open a dialog, send a message. Obfuscation is checked
statically (no `Class.forName` on project classes, resources loaded by literal path,
TL dispatch is a `switch` on an int constructor id), but static checks are not a run.
