# Building TelegramJ2ME

Everything is driven from the command line. There is no IDE project, no Ant, no
Maven and no Gradle — the build is a set of PowerShell scripts in `tools/` that
call `javac`, ProGuard, `jar` and a few Python helpers.

The scripts run on **Windows, Linux and macOS**. On Linux and macOS there are
`.sh` wrappers so you never have to type `pwsh` yourself:

```bash
./tools/bootstrap.sh              # JDK 8 check + pinned SDK downloads
./tools/build.sh -Target probe    # -> dist/probe.jar + dist/probe.jad
./tools/test.sh                   # desktop test suite
```
```powershell
.\tools\bootstrap.ps1
.\tools\build.ps1 -Target probe
.\tools\test.ps1
```

The wrappers are three lines of `exec pwsh -File …`. All the logic lives in the
`.ps1` files, because two implementations of the same 450-line build would drift
from each other within a release. Scripts without a wrapper are run directly:

```bash
pwsh -File tools/smoke-emulator.ps1
pwsh -File tools/audit-public.ps1
```

> One wrapper limitation worth knowing: `pwsh -File` passes every argument as a
> literal string, so an array parameter cannot be given as `-ArtifactName a,b`
> from a shell. Only `smoke-emulator.ps1` takes one, and it has no wrapper.

---

## Prerequisites

| Tool | Why |
|---|---|
| **JDK 8** | JDK 9+ removed `-source 1.3` / `-target 1.1`, which CLDC needs |
| **Python 3.8+** | build-side API check, TL generator, dev servers |
| **PowerShell 7** | the build scripts (preinstalled on nothing; see below) |

**Windows**

```powershell
winget install --id EclipseAdoptium.Temurin.8.JDK --exact
winget install --id Microsoft.PowerShell --exact
winget install --id Python.Python.3.12 --exact
```

Windows ships *Windows PowerShell 5.1*, which also works, but 7 is what CI uses.

**Debian / Ubuntu**

```bash
sudo apt install openjdk-8-jdk python3
# PowerShell 7 is not in the Ubuntu archive; use Microsoft's:
wget -q https://packages.microsoft.com/config/ubuntu/24.04/packages-microsoft-prod.deb
sudo dpkg -i packages-microsoft-prod.deb && sudo apt update && sudo apt install powershell
```

Substitute your release for `24.04`. See
[Microsoft's install guide](https://learn.microsoft.com/powershell/scripting/install/install-ubuntu)
for other distributions.

**Fedora / RHEL**

```bash
sudo dnf install java-1.8.0-openjdk-devel python3 powershell
```

**macOS**

```bash
brew install --cask temurin@8 powershell
```

If the JDK does not land somewhere `tools/_env.ps1` looks, point `JDK8_HOME` at
it and the scripts will use that:

```bash
export JDK8_HOME=/usr/lib/jvm/java-8-openjdk-amd64
```
```powershell
$env:JDK8_HOME = "C:\Program Files\Eclipse Adoptium\jdk-8.0.492.9-hotspot"
```

Everything else — MicroEmulator, ProGuard, the Bouncy Castle `BigInteger`
source — is downloaded by `bootstrap` from the pins in
[tools/sdk.lock.json](../tools/sdk.lock.json), SHA-256 verified, and never
committed.

### Optional: Sun WTK 2.5.2_01

The reference MIDP/CLDC toolkit. Not required, but it upgrades two things from
"approximated" to "exact": the compile-time API surface and the preverifier.

1. [Oracle Java ME archive](https://www.oracle.com/java/technologies/java-archive-downloads-javame-downloads.html)
   (free Oracle account needed)
2. install `sun_java_wireless_toolkit-2.5.2_01-win.exe`, or on Linux the
   `-linux.bin` installer — it is 32-bit, so it needs `lib32z1` / `glibc.i686`
3. point `WTK_HOME` at it and re-run bootstrap:
   `setx WTK_HOME "C:\WTK2.5.2_01"` / `export WTK_HOME=$HOME/WTK2.5.2_01`

The build detects it automatically and switches to WTK's `cldcapi11.jar` /
`midpapi20.jar` and its `preverify` binary. See [toolchain.md](toolchain.md).

---

## Bootstrap

```bash
./tools/bootstrap.sh
```

It resolves JDK 8, downloads and verifies the pinned artifacts, unpacks
ProGuard, checks the MicroEmulator jars really carry the MIDP API the build
compiles against, regenerates `src/tg/crypto/bigint/BigInteger.java` from the
vendored Bouncy Castle source, generates the TL layer into `generated/tg/api/`,
and confirms your credentials file is gitignored.

`generated/` is gitignored and a fresh clone has no TL layer, so **`src/` does
not compile until bootstrap has run**.

The two generated-and-committed files (`BigInteger.java`, `ServerKeys.java`) are
byte-identical on every platform. CI enforces that: after bootstrap,
`git status --porcelain` must be empty.

---

## What builds

| Artifact | Size | Contents | Use |
|---|---|---|---|
| `dist/probe.jar` | ~172 KB | `ProbeMidlet` + diagnostics + the crypto stack | first install on unknown hardware: platform, heap, RMS, entropy, seeding barrier, crypto vectors and benchmarks, keys, network |
| `dist/tg.jar` | ~464 KB | full client | the messenger |
| `dist/tg.jar` with `-Release` | ~339 KB | full client, optimised + obfuscated | when the handset caps install size |

```bash
./tools/build.sh -Target probe
./tools/build.sh -Target tg -Env production
./tools/build.sh -Target tg -Env production -Release   # smaller, obfuscated
```

Each JAR also carries the licence texts of the third-party code it actually
contains — `emoji-OFL.txt` and the Bouncy Castle licence (vendored `BigInteger`)
in both, plus Apache 2.0 in `tg` (the pdf.js-derived `JpegDecoder`). Compiled
classes carry no comments, so the attribution in the source headers would not
otherwise reach anyone who installs the JAR.

`probe.jar` used to exclude the crypto stack entirely, which kept it around
110 KB; the vectors and benchmarks lived in a third `crypto` target. They are one
suite now — one install and one **Upload all** per handset session — and the
probe carries `BigInteger` as a result. The cost is that it is no longer small
enough for the lowest rungs of `tools/build-size-ladder.ps1`: a handset with a
64 KiB or 128 KiB install cap can only be measured with an older probe release.
`probe.jar` still excludes the client, so nothing above `tg.crypto` and
`tg.plat` is in it.

Every build preverifies (`-microedition`), so both variants carry the CLDC
`StackMap` the handset's verifier demands. `-Release` additionally drops
[config/proguard-debug.pro](../config/proguard-debug.pro), which is the file
that otherwise holds `-dontoptimize` / `-dontobfuscate`.

`-ArtifactName` renames the pair and rewrites `MIDlet-Jar-URL` to match, which
is how releases produce versioned filenames:

```bash
./tools/build.sh -Target tg -Env production -ArtifactName TelegramJ2ME-0.2.0
pwsh -File tools/run-emulator.ps1 -Target tg -ArtifactName TelegramJ2ME-0.2.0
```

### The two build profiles

Both compile the *same* `src/` tree.

* **device** — `javac -source 1.3 -target 1.1` against the CLDC/MIDP
  bootclasspath, then `check-api.py`, then ProGuard `-microedition`
  (preverify + shrink), then JAR + JAD with an exact `MIDlet-Jar-Size`.
* **desktop** — plain JDK 8, plus `test/`. Because everything above
  `tg.io.Transport` is pure CLDC-subset Java, the crypto, TL and MTProto layers
  can be driven against a real Telegram data centre from a desktop JVM, with a
  real debugger, before any handset is involved.

`tg.io.Transport` remains the byte-stream seam: `tg.plat.MidpTransport` on the
device, `tgtest.SeTransport` on the desktop. `tg.mt.MtLink` is the packet seam
shared by TCP framing, FakeTLS and request/response HTTP.

### Why `check-api.py`

Without WTK the device build has to fall back to JDK 8's `rt.jar` for
`java.lang`/`java.io`/`java.util`, which is a huge superset of CLDC 1.1. A
`StringBuilder`, a `System.nanoTime()` or an autoboxed `Integer` would compile
cleanly and then fail on the phone. `tools/check-api.py` reads the constant pool
of every compiled class and rejects anything outside
[config/cldc11-midp20-api.txt](../config/cldc11-midp20-api.txt).

---

## Test

```bash
./tools/test.sh                # all 42 suites
./tools/test.sh -Filter bigint # substring match on the suite name
```

42 hand-registered suites in `test/tgtest/AllTests.java` cover crypto,
serialization, transport, persistence, authorization, content, UI logic and the
memory budgets.
There is no JUnit and no reflection — the registry is explicit so the same cases
can later be linked into an on-device self-test MIDlet unchanged.

```bash
pwsh -File tools/smoke-emulator.ps1
```

starts each *packaged* JAR, including the obfuscated one, in MicroEmulator's
MIDP runtime and navigates between screens. It is the only automated check that
the artifact which actually ships still runs. It is headless, so it needs no
display, but on a minimal Linux box it does need fonts installed
(`fontconfig` + e.g. `fonts-dejavu-core`) or AWT font metrics will fail.

```bash
pwsh -File tools/smoke-emulator.ps1 -ArtifactName probe
```

drives the probe suite instead, through its own harness: it walks the menu, runs
the seeding barrier and the crypto vectors out of the packaged JAR and prints
what the barrier sized itself to on this machine's clock. The gather count is a
measurement, so nothing asserts a particular value — what is asserted is that it
terminates inside its own bounds and credits what it claims.

`-JavaArgs` passes JVM options through, which is how the memory budgets get
exercised against a real artifact:

```bash
pwsh -File tools/smoke-emulator.ps1 -SkipBuild -ArtifactName tg -JavaArgs -Xmx12m
```

MicroEmulator runs the MIDlet on the host JVM, so a bounded host heap is what the
client's own probe measures. Read the result with the caveats in
[emulator-notes.md](emulator-notes.md#bounding-the-heap-with--xmx).

The smoke test stops before the network on purpose. To exercise what comes after
it — connect, sign in, open a chat — without a person at the keyboard:

```bash
pwsh -File tools/drive-emulator.ps1 -Scenario probe -EmulatorProfile check
pwsh -File tools/drive-emulator.ps1 -Scenario route -Mode Auto -Env production
```

```bash
pwsh -File tools/drive-emulator.ps1 -Scenario minheap \n     -ChatTitle "<chat>" -Pictures off -Remeasure -JavaArgs "-Xmx3m"
```

`drive-emulator.ps1` presses the same commands by label inside MicroEmulator's
MIDP runtime and records the diagnostic log, which is otherwise readable only on
the Log screen of a running emulator. It follows the ring rather than reading it
once at the end, so a run that produces more than a hundred lines still reports
totals instead of whatever survived in the buffer — the verdict prints
`lostLines=0` when nothing was missed, and `-NoDiagTail` switches the recorder
off, which is the control for whether a number belongs to the client or to the
observer. `oomBy=[...]` names the task behind every `OutOfMemoryError`.

The `minheap` scenario prints one verdict
line per run — what still worked at that heap — so a sweep of `-Xmx` values
reads as a table; `-Pictures off` repeats it with dialog avatars and inline
thumbnails disabled. `-Remeasure` is required for any constrained run: a profile
otherwise carries the ceiling of whichever JVM first wrote it, which under a
smaller `-Xmx` is a lie in the direction that matters. Unlike the smoke harness it registers a
record store, so auth keys and the stored heap measurement persist across runs
exactly as they do in the GUI. Every scenario except `probe` contacts real
Telegram servers; `-Scenario login` additionally needs a phone number and a file
to read the sign-in code from.

`-SkipBuild` drives whatever is in `build/desktop/classes`, and the data centres
are compiled in — so a run that follows `test.ps1` or `smoke-emulator.ps1`, both
of which build `-Env test`, would otherwise present a stored production session
to the test data centres and report a signed-out account. The driver compares the
build against `-Env` and stops with `WRONG BUILD` instead.

See [emulator-notes.md](emulator-notes.md) for what an emulator pass does and
does not prove.

---

## Run it in an emulator

```bash
pwsh -File tools/run-emulator.ps1 -Target probe
pwsh -File tools/run-emulator.ps1 -Target tg -EmulatorProfile clean
```
```powershell
.\tools\run-emulator.ps1 -Target probe
```

This one is a Swing application, so on Linux it needs an X or Wayland session.
`-Headless` runs `org.microemu.app.Headless` instead. `-UseWtk` launches the Sun
emulator if `WTK_HOME` is set.

---

## Credentials

`api_id` / `api_hash` come from [my.telegram.org](https://my.telegram.org)
(see [obtaining_api_id](https://core.telegram.org/api/obtaining_api_id)).

```bash
mkdir -p secrets && cp config/telegram.yaml.example secrets/telegram.yaml
# then fill in api_id and api_hash
```
```powershell
Copy-Item config/telegram.yaml.example secrets/telegram.yaml
```

The build reads that file and emits `generated/tg/app/Secrets.java`. Both
`secrets/` and `generated/` are gitignored, so the values reach the JAR without
ever reaching git — bootstrap verifies that and fails if the secrets file is not
ignored.

`TG_API_ID` / `TG_API_HASH` environment variables take precedence over the file.
That is how CI injects repository secrets without writing them to the runner's
disk; see [releasing.md](releasing.md).

Builds without credentials still succeed — `Secrets.CONFIGURED` becomes `false`
and API-layer calls fail — so forks and pull requests need no setup.

**Never** commit `api_id`, `api_hash`, a phone number, or an `auth_key`.

Which data centres a build talks to is a build flag, not a config value: an
`auth_key` is bound to one environment, so flipping it at runtime with a stale
key in RMS would fail confusingly.

```bash
./tools/build.sh -Target tg                    # -Env test (default)
./tools/build.sh -Target tg -Env production
```

A test build also carries the **test server key modulus**, so it cannot
complete a handshake against production. Through an MTProxy it will reach
production anyway - the proxy decides the destination, not the build - and the
failure surfaces as an opaque key mismatch that names no environment.

Two things make that harder to hit. The build prints `env=` in its header and
warns when a non-probe target is built for test. And a test build installs
under the MIDlet name `TelegramJ2ME (test)`: because MIDlet suite identity is
name plus vendor, it lands **alongside** a production install rather than
replacing it, so it can neither overwrite the real app nor inherit its record
stores.

For a session on a real handset, always pass `-Env production`.

### A default MTProxy for device builds

Typing a base64 proxy secret on a numeric keypad after every reinstall is not a
workflow. An optional `secrets/proxy.yaml` supplies one at build time:

```yaml
link: tg://proxy?server=...&port=443&secret=...
```

It is used **only when the handset has nothing stored**. Anything entered in
Settings is persisted and keeps winning, so reinstalling cannot silently
override a choice made on the device.

A build's own proxy goes to the **front** of the Auto chain, not in place of it:
Auto still falls back to direct, obfuscated and HTTP behind it. That matters on
the first launch, which is the one launch with nothing stored to fall back on -
a proxy that happens to be down must cost one failed attempt, not the whole
connection. Whichever route completes the connection preflight is the one
persisted, so the order corrects itself after a single launch in either
direction.

Like every other value under `secrets/`, it never reaches `src/` - the build
writes `generated/tg/app/DevProxy.java`, and both directories are gitignored and
rejected by `tools/audit-public.ps1`, which also harvests the individual query
parameters of a link so a leak of the bare secret is caught too. Without the
file, `CONFIGURED` is false and the build ships no proxy.

DC addresses themselves are public and live in
[src/tg/mt/Dc.java](../src/tg/mt/Dc.java), under git — but only as bootstrap
entries. The authoritative list comes from `help.getConfig`.

---

## Live testing against real servers

Protocol work runs against Telegram's test data centres, which need no account:

```bash
pwsh -File tools/live.ps1 handshake    # req_pq_multi .. dh_gen_ok
pwsh -File tools/live.ps1 config       # encrypted session + help.getConfig
pwsh -File tools/live.ps1 obfs-config  # direct obfuscated2
pwsh -File tools/live.ps1 http-config  # MTProto over HTTP
pwsh -File tools/test-local-mtproxy.ps1   # classic, dd and ee local-proxy E2E (needs Docker)
```

Anything past authorization needs a real account; see Telegram's official
[authorization documentation](https://core.telegram.org/api/auth):

```bash
./tools/build.sh -Profile desktop -Env production
pwsh -File tools/live.ps1 login '<international-number>'
pwsh -File tools/live.ps1 dialogs
pwsh -File tools/live.ps1 send "hello"
pwsh -File tools/live.ps1 updates -Env production 120
```

These are deliberately kept out of `test.sh`: they touch the network and a real
account, so they are not part of a routine test run.

---

## Before pushing

```bash
pwsh -File tools/audit-public.ps1
```

It checks the complete would-be commit set, verifies that private directories
are excluded, looks for common credential formats and confirms that exact values
from local secret files do not appear elsewhere. Match contents are never
printed. It also rejects a hardcoded home directory — any absolute path under
`C:\Users` or `/home` with a user name in it — so keep new scripts
repo-relative. (This paragraph originally spelled those paths out and the audit
correctly rejected its own documentation.)

It also warns about commits that were pushed and later force-pushed away. A
clean working tree does not unpublish those: GitHub keeps serving the orphaned
commit at `/commit/<sha>` with nothing in the UI to suggest it exists, so
rewriting history is not a way to retract a file that has already been pushed.

---

## Repository layout

```
src/tg/            device code - CLDC 1.1 subset only
    app/           MIDlet lifecycle, composition root
    ui/            lcdui screens
    plat/          MIDP adapters: sockets, RMS, capability probing
    diag/          log ring, crash persistence
    io/            Transport contract, byte helpers
    crypto/        SHA-1/256/512, HMAC, PBKDF2, AES/CTR/IGE, RNG, bigint
    tl/            TL serialization
    mt/            MTProto transport, session, auth key
    api/           Telegram API layer
test/tgtest/       desktop-only harness (runs the same src/)
tools/             build, bootstrap, dev servers, TL generator
config/            ProGuard configs, CLDC API allow-list, app.properties
schema/            Telegram TL schema, layer 223 + upstream provenance
third_party/       vendored BigInteger, emoji sprite, JPEG decoder + licences
```

---

## Writing a build script

If you touch anything in `tools/`, four rules keep it working on all three
platforms. `tools/_env.ps1` provides the helpers.

| Do | Not |
|---|---|
| `Join-RepoPath "build" "device"` | `Join-Path $RepoRoot "build\device"` |
| `-join $PathSep` | `-join ";"` |
| `"javac$ExeSuffix"` | `"javac.exe"` |
| `Get-PythonCommand` | `Get-Command python` |

The first is the one that bites: `Join-Path` does not split a backslash on
Linux, so `Join-Path $RepoRoot "build\device"` silently produces a single file
*named* `build\device` instead of a path. Nothing errors; the build just writes
to the wrong place.

Two smaller ones: quote `-D` arguments to `java` (`"-Djava.awt.headless=true"`)
because PowerShell splits an unquoted one at the first dot, and use
`[IO.Path]::GetTempPath()` rather than `$env:TEMP`, which does not exist outside
Windows.
