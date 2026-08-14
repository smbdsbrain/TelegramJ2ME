# Toolchain

| Component | Version | Source |
|---|---|---|
| JDK 8 | Temurin 8.0.492+9 / OpenJDK 8u492 | `winget install EclipseAdoptium.Temurin.8.JDK` · `apt install openjdk-8-jdk` · `brew install --cask temurin@8` |
| PowerShell | 7.x (Windows PowerShell 5.1 also works) | `winget install Microsoft.PowerShell` · [Microsoft's Linux packages](https://learn.microsoft.com/powershell/scripting/install/installing-powershell-on-linux) · `brew install --cask powershell` |
| Python | 3.8+ | system installation, `python3` or `python` |
| MicroEmulator | 2.0.4 | Maven Central, pinned in `tools/sdk.lock.json` |
| ProGuard | 7.4.2 | GitHub release, pinned |
| Bouncy Castle `BigInteger` | `bc-java@31a2228b` | GitHub raw, pinned |
| Sun WTK | 2.5.2_01 | optional, manual Oracle download |
| Ant | not used | plain PowerShell instead |

Windows, Linux and macOS are all supported build hosts; see
[building.md](building.md) for the per-platform commands. Nothing in the device
pipeline is Windows-specific — it is `javac`, ProGuard, `jar` and Python, all of
which are JVM or interpreter code. Verified: Windows and Linux produce JARs with
identical entry lists and class counts for all four targets, including the
obfuscated `-Release` build.

They are *not* byte-identical, because `jar` stamps each entry with a
modification time. Determinism in this project means the same inputs produce the
same classes, not the same archive bytes.

---

## Why JDK 8 specifically

CLDC class files are Java 1.1 format. JDK 8's `javac` still accepts
`-source 1.3 -target 1.1`; JDK 9 removed both. The build resolves JDK 8
explicitly through `JDK8_HOME` or a scan of the usual install roots
(`tools/_env.ps1`).

Verified: `tg/crypto/bigint/BigInteger.class` is `CAFEBABE minor=3 major=45`,
i.e. class format 45.3, which is what a KVM expects.

## Preverification

CLDC's verifier requires a `StackMap` attribute on every method; a plain `javac`
class file does not have one.

**Primary path - ProGuard `-microedition`.** Freely downloadable, scriptable,
and it shrinks and obfuscates in the same pass. `tools/build.ps1` greps the
output for a `StackMap` attribute and warns loudly if it is absent, because a
silently unpreverified JAR would only fail on the handset.

**Reference path - WTK `preverify`.** Available once `WTK_HOME` is set. Building
the same sources through WTK's preverifier provides a differential test between
the reference toolchain and ProGuard.

> A JAR produced by this toolchain has since been installed and run on a
> physical Java ME handset, where the client completed the MTProto handshake,
> signed in, listed dialogs and sent a message over GPRS. That is one device on
> one network; it establishes that the packaging is correct, not that it is
> correct for every AMS.

## Bootclasspath: two modes

`tools/_env.ps1` picks one and `build.ps1` prints which is active.

**`wtk`** - `cldcapi11.jar` + `midpapi20.jar`. Exact: `javac` itself rejects any
API outside CLDC 1.1 / MIDP 2.0.

**`fallback`** (current) - `microemu-cldc.jar` + `microemu-midp.jar` supply
`javax.microedition.*`; JDK 8's `rt.jar` supplies `java.lang`, `java.io`,
`java.util`. MicroEmulator does not ship a CLDC `java.lang`, because it runs on
a J2SE VM, so there is no free replacement for that part.

`rt.jar` is a large superset of CLDC 1.1, so in fallback mode the compiler is
not the enforcement mechanism - **`tools/check-api.py` is**. The build runs it
once on javac output and again on ProGuard's final class tree, because bytecode
optimization can introduce calls that were absent from the source. It parses
the constant pool of every compiled class and rejects:

* a referenced class not in `config/cldc11-midp20-api.txt`;
* a member on the deny list - `System.nanoTime`, `Math.pow`, `Vector.add`,
  `Integer.bitCount`, `String.split`, and `Integer.valueOf(int)` which is what
  autoboxing compiles to;
* a class file whose major version exceeds 47.

The class allow-list is complete against the specs. The member deny-list is
curated: it covers the known traps, not every member of every class. Installing
WTK upgrades this from "good" to "exact" - which is why it stays worth doing.

Release optimization excludes `code/simplification/object`: ProGuard otherwise
rewrites CLDC-safe wrapper constructors to Java SE `Integer.valueOf(int)` and
`Long.valueOf(long)` factories. Those overloads do not exist on CLDC 1.1; this
was observed as a physical Nokia C3-00 `NoSuchMethodError` and is also blocked
by the post-ProGuard API scan.

## Build outputs

```
build/device/classes        javac output
build/device/preverified    ProGuard output, StackMap present
dist/<target>.jar           JAR with MIDP manifest
dist/<target>.jad           descriptor, MIDlet-Jar-Size exact
```

Determinism: nothing time-varying is baked in. `generated/tg/app/BuildInfo.java`
carries the version and the git hash only - deliberately no timestamp, so the
same source produces the same JAR.

## Emulators

**MicroEmulator 2.0.4** (default) runs the MIDlet on the desktop JVM. Fast, real
sockets, good enough to drive MTProto. It proves nothing about heap limits, the
AMS socket permission policy, JAR verification, timing, or key codes - see
`docs/emulator-notes.md`.

**Sun WTK emulator** (`-UseWtk`) is the reference MIDP implementation and
supports OTA provisioning (`-Xjam`, exposed as `-Ota`), which exercises the JAD
install path before a real phone does.

Run under JDK 8: MicroEmulator 2.0.4 was built against JDK 1.6 and a current JDK
trips over removed AWT and security APIs.

The automated smoke test runs MicroEmulator headless, so it needs no display -
but it does need fonts, because `J2SEFontManager` builds AWT font metrics as
soon as the device is installed. On a minimal Linux image install `fontconfig`
and at least one font package.

## Known toolchain notes

* `scoop`'s `main` bucket on this host is broken (0 manifests). Irrelevant -
  the build uses `winget` and direct pinned downloads.
* ProGuard has no `-version` flag; `bootstrap.ps1` no longer asks for one.
* `sdk/proguard-7.4.2.zip` is 31 MB. It is gitignored; only the pin is committed.
* `tools/render-showcase.ps1` rasterises text through AWT, which is
  platform-dependent. The committed screenshots were rendered on Windows;
  regenerating them on Linux produces visually different PNGs. Review before
  committing a cross-OS rerun.
