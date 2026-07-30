# Toolchain

| Component | Version | Source |
|---|---|---|
| JDK 8 | Temurin 8.0.492+9 | `winget install EclipseAdoptium.Temurin.8.JDK` |
| Python | 3.8+ | system installation |
| MicroEmulator | 2.0.4 | Maven Central, pinned in `tools/sdk.lock.json` |
| ProGuard | 7.4.2 | GitHub release, pinned |
| Bouncy Castle `BigInteger` | `bc-java@31a2228b` | GitHub raw, pinned |
| Sun WTK | 2.5.2_01 | optional, manual Oracle download |
| Ant | not used | plain PowerShell instead |

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

**Reference path - WTK `preverify.exe`.** Available once `WTK_HOME` is set.
Building the same sources through WTK's preverifier provides a differential
test between the reference toolchain and ProGuard.

> **Emulator-only validation.** The generated JAR has not been installed or run
> on a physical Java ME device.

## Bootclasspath: two modes

`tools/_env.ps1` picks one and `build.ps1` prints which is active.

**`wtk`** - `cldcapi11.jar` + `midpapi20.jar`. Exact: `javac` itself rejects any
API outside CLDC 1.1 / MIDP 2.0.

**`fallback`** (current) - `microemu-cldc.jar` + `microemu-midp.jar` supply
`javax.microedition.*`; JDK 8's `rt.jar` supplies `java.lang`, `java.io`,
`java.util`. MicroEmulator does not ship a CLDC `java.lang`, because it runs on
a J2SE VM, so there is no free replacement for that part.

`rt.jar` is a large superset of CLDC 1.1, so in fallback mode the compiler is
not the enforcement mechanism - **`tools/check-api.py` is**. It parses the
constant pool of every compiled class and rejects:

* a referenced class not in `config/cldc11-midp20-api.txt`;
* a member on the deny list - `System.nanoTime`, `Math.pow`, `Vector.add`,
  `Integer.bitCount`, `String.split`, and `Integer.valueOf(int)` which is what
  autoboxing compiles to;
* a class file whose major version exceeds 47.

The class allow-list is complete against the specs. The member deny-list is
curated: it covers the known traps, not every member of every class. Installing
WTK upgrades this from "good" to "exact" - which is why it stays worth doing.

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

## Known toolchain notes

* `scoop`'s `main` bucket on this host is broken (0 manifests). Irrelevant -
  the build uses `winget` and direct pinned downloads.
* ProGuard has no `-version` flag; `bootstrap.ps1` no longer asks for one.
* `sdk/proguard-7.4.2.zip` is 31 MB. It is gitignored; only the pin is committed.
