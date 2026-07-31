# Contributing

The most valuable contribution right now is not code — it is a
[device report](https://github.com/smbdsbrain/TelegramJ2ME/issues/new?template=device-report.yml).
Exactly one handset has ever run this client, so every model anyone tries is new
information, whether it works or not.

If you do want to change code, here is what you need to know.

## Getting set up

[docs/building.md](docs/building.md) has the per-platform prerequisites. In
short: JDK 8, Python 3, PowerShell 7, then

```bash
./tools/bootstrap.sh
./tools/test.sh
```
```powershell
.\tools\bootstrap.ps1
.\tools\test.ps1
```

Bootstrap generates the TL layer into `generated/`, which is gitignored — a
fresh clone does not compile until it has run.

You do **not** need Telegram credentials to build or to run the test suite.
Builds without them succeed with `Secrets.CONFIGURED = false`, which is why pull
requests from forks work.

## Before opening a pull request

```bash
./tools/test.sh                            # 27 suites
./tools/build.sh -Target tg                # the device profile, incl. check-api
pwsh -File tools/smoke-emulator.ps1        # runs the packaged JARs
pwsh -File tools/audit-public.ps1          # secret and private-path audit
```

CI runs all of these on both Windows and Linux, plus a reproducibility gate:
after bootstrap, `git status --porcelain` must be empty.

## Device code lives inside CLDC 1.1 / MIDP 2.0

Everything under `src/` compiles for a KVM on a phone from 2011, not for a
desktop JVM. `tools/check-api.py` reads the constant pool of every compiled
class and fails the build on anything outside
[config/cldc11-midp20-api.txt](config/cldc11-midp20-api.txt).

Things that compile fine and are still rejected:

* `StringBuilder` — use `StringBuffer`
* `System.nanoTime()` — use `System.currentTimeMillis()`
* autoboxing — it compiles to `Integer.valueOf(int)`, which CLDC 1.1 lacks
* `String.split`, `Math.pow`, `Vector.add`, `Integer.bitCount`
* generics, enums, for-each, varargs — the device profile is `-source 1.3`

`test/` is different: it is desktop-only, compiled at `-source 1.6`, and may use
whatever the JDK offers. That asymmetry is the point — the tests can diff
`tg.crypto` against `java.security` and `tg.crypto.bigint.BigInteger` against
`java.math.BigInteger`.

## Memory is not free

The one measured handset had 5 MB of Java heap, and no assumption beyond that is
safe. Every subsystem is written with an explicit bound — 32 KiB download
chunks, a 100-line log ring, a 12-entry avatar cache, a 64-message outbox — and
new ones should be too. See [docs/architecture.md](docs/architecture.md).

## Never commit secrets

`api_id`, `api_hash`, a phone number or an `auth_key` must never reach git.
`secrets/` and `generated/` are gitignored; `tools/audit-public.ps1` checks the
whole would-be commit set and never prints what it matches.

Note that force-pushing does not retract anything already pushed — GitHub keeps
serving the orphaned commit at `/commit/<sha>`. If a credential is pushed, treat
it as disclosed and rotate it.

## Generated files that *are* committed

Three files are generated but tracked, because their inputs are pinned and their
output is deterministic:

* `src/tg/crypto/bigint/BigInteger.java` — from `tools/port-bc-bigint.py`
* `src/tg/mt/ServerKeys.java` — from `tools/fetch-server-keys.py`
* `res/emoji.png` — from `tools/generate-emoji.py`

Regenerate them rather than editing them by hand; CI fails if a bootstrap on a
clean tree produces a diff.

## Writing a build script

`tools/` runs on Windows, Linux and macOS. Four rules, all provided for by
`tools/_env.ps1`:

| Do | Not |
|---|---|
| `Join-RepoPath "build" "device"` | `Join-Path $RepoRoot "build\device"` |
| `-join $PathSep` | `-join ";"` |
| `"javac$ExeSuffix"` | `"javac.exe"` |
| `Get-PythonCommand` | `Get-Command python` |

The first one is the trap: `Join-Path` does not split a backslash on Linux, so
the "path" becomes a single file name and the build silently writes to the wrong
place. See the end of [docs/building.md](docs/building.md).

## Style

Match the surrounding code. Comments in this project explain *why* — the
constraint, the measurement, the standard that forced a decision — and there are
a lot of them for a reason: most of what is unusual here is unusual because of
something a 2011 handset does.

Commit messages follow `type(scope): summary` (`fix(mt):`, `docs:`, `test:`).
