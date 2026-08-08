# Emulator notes

## Persistent RMS profiles

MicroEmulator stores its configuration and RMS below `.microemulator` in the
host user directory. `tools/run-emulator.ps1` exposes isolated named states:

```powershell
# Existing default RMS state.
./tools/run-emulator.ps1 -Target tg -EmulatorProfile default

# Separate clean RMS state; later launches with the same name reuse it.
./tools/run-emulator.ps1 -Target tg -EmulatorProfile demo
```

`default` intentionally leaves the JVM's `user.home` unchanged, so introducing
this feature cannot move, clear, or shadow the state created by older versions
of the script. Every non-default name gets a separate JVM home at
`local/microemulator/<name>/`; MicroEmulator then creates its `.microemulator`
configuration and all RecordStores only below that directory. (`--id` is not
used: the GUI in MicroEmulator 2.0.4 processes it after the configuration path
has already been cached.) Switching profiles is therefore just choosing the
corresponding command; do not use the emulator's Record Store Manager to switch
application states.

The launcher also copies the selected `dist/*.jar` to a content-addressed file
under `build/emulator/<profile>/` before starting it. MicroEmulator holds its
classpath JAR open on Windows; staging prevents an open emulator window from
blocking the next build or the launch of another profile.

## What an emulator pass does and does not prove

MicroEmulator runs the MIDlet on the host's J2SE VM. That makes it excellent for
logic - protocol framing, crypto, TL parsing, state machines - and worthless as
evidence about the handset.

**Not evidence of:**

| Question | Why the emulator cannot answer it |
|---|---|
| Does the JAR pass verification? | The desktop JVM does not run the CLDC verifier, so a missing or malformed `StackMap` goes unnoticed. |
| Is memory use acceptable? | Desktop heap is effectively unbounded; `Runtime.totalMemory()` bears no relation to the phone. A host `-Xmx` narrows this a little - see below - but not to the sizes that matter. |
| Will `socket://` be permitted? | There is no AMS security policy, no untrusted-MIDlet permission prompt. |
| Is it fast enough? | 2048-bit `modPow` is 12 ms on this desktop and will be orders of magnitude slower on a 208 MHz ARM without a JIT. |
| Do the keys work? | Key codes and soft keys are vendor specific; MicroEmulator invents its own. |
| Will installation work? | No AMS, no JAD parsing, no JAR size limit, no signing policy. |

### Bounding the heap with `-Xmx`

MicroEmulator has no heap option of its own, but it runs the MIDlet on the host
JVM, so `Runtime.totalMemory()`, `freeMemory()` and `OutOfMemoryError` all follow
whatever the host was given. `-JavaArgs` on `run-emulator.ps1` and
`smoke-emulator.ps1` passes it through:

```powershell
./tools/smoke-emulator.ps1 -SkipBuild -ArtifactName tg -JavaArgs -Xmx12m
./tools/run-emulator.ps1 -Target tg -EmulatorProfile small -JavaArgs -Xmx24m
```

That is enough to watch the client's own heap probe measure a constrained
ceiling and the budgets in `tg.mem.MemoryBudget` follow it. It is **not** a CLDC
heap. `-Xmx` bounds the whole host process - AWT font metrics, MicroEmulator's
own objects, the harness - so it cannot reach the low single megabytes where the
interesting budget floors live: below about 4 MB the harness stops fitting, which
says nothing about the client. Everything under that is covered by
`tgtest.MemoryBudgetTest`, which installs a ceiling directly. CI runs the smoke
test at 16 MB and 12 MB.

The WTK emulator narrows the first, third and sixth of these - it is the
reference MIDP implementation and does run OTA provisioning. A physical-device
session has since been performed on one handset, which confirmed the third and
fifth rows the hard way: raw sockets to ports 80 and 443 were refused to an
unsigned MIDlet, and the AMS ordered its Options menu by command *type*, burying
every `Command.OK` beneath the `Command.SCREEN` entries.

## Automated smoke test

```powershell
.\tools\smoke-emulator.ps1                 # both shipped variants
.\tools\smoke-emulator.ps1 -ArtifactName tg-min
```
```bash
pwsh -File tools/smoke-emulator.ps1
```

It runs headless (`-Djava.awt.headless=true`), so no display is needed - but
`J2SEFontManager` builds AWT font metrics as soon as the device is installed, so
a minimal Linux image still needs `fontconfig` and a font package.

`tools/smoke-emulator.ps1` starts a **packaged** `dist/*.jar` inside
MicroEmulator's MIDP runtime and asserts that the MIDlet reaches a screen, that
commands really change screens, that the menu-ordering rule below holds, and
that no thread of ours outlives `destroyApp`. It runs in CI after the build
steps and again during a release.

This is the only automated check that runs the artifact which ships. Everything
in the desktop suite executes `build/desktop/classes`, which ProGuard never
touched, so a keep rule that stopped covering the code, a stripped resource or a
broken preverification pass would otherwise reach a handset before anything
noticed. The obfuscated variant is checked too, because it is a different
ProGuard configuration and can break on its own.

The run is offline - it never presses Connect - so no network, Telegram account
or RMS profile is involved.

### Exact packaged RC E2E

`tools/drive-emulator.ps1` normally drives desktop production classes, which is
right for diagnostics but cannot prove that an obfuscated RC behaves the same.
The two-account release gate instead runs:

```powershell
.\tools\rc-e2e.ps1 -ArtifactName TelegramJ2ME-1.0.0-rc1
.\tools\rc-e2e.ps1 -ArtifactName TelegramJ2ME-1.0.0-rc1-min
.\tools\rc-slow-e2e.ps1
```

Its driver class imports only the kept MIDlet entry point and standard MIDP UI,
then puts the exact artifact JAR first on the classpath. It therefore survives
renaming of every implementation class. The receiver stays connected while the
sender sends and edits; a reflection check looks for the marked text and the
rendered `edited` line without depending on private member names. Usernames are
exchanged only through ignored private files and never printed. This is still
emulator evidence, not handset evidence.

`rc-slow-e2e.ps1` repeats both exact packaged variants with `-Xmx32m` while
`MidpTransport` fragments socket reads into 1024-byte chunks, paces writes, and
waits 10 ms at each I/O boundary. Shaping is inactive unless the E2E JVM
property is set.
Its extra reaction flow opens the local palette, starts the remote actor list,
presses Back while that request is still in flight, and immediately toggles a
reaction. A delayed palette, invisible loading view, `Finishing ... first`
alert, stuck status, or transport desynchronisation fails the run.
Use `-DelayMs 20 -ChunkBytes 512` for a harsher manual GPRS-like stress run;
the release gate keeps the calibrated profile above so unrelated live Telegram
timeouts do not masquerade as a deterministic UI regression.

### The menu-ordering rule it enforces

MIDP only promises to honour a command's priority *within* one command type;
where the types land relative to each other is the handset's business. A real
handset was measured building its Options menu type by type with `SCREEN` ahead
of `OK`, which put the primary action of every screen at the *bottom* of the
menu. So every command that shares a menu must share a type, leaving priority in
charge: primary actions are `Command.SCREEN` priority 1, diagnostics sort last,
and only `BACK`/`EXIT`/`CANCEL` are exempt because handsets map those to a
dedicated key.

## Running

```powershell
.\tools\run-emulator.ps1 -Target probe            # MicroEmulator
.\tools\run-emulator.ps1 -Target probe -UseWtk    # Sun WTK, needs WTK_HOME
.\tools\run-emulator.ps1 -Target probe -UseWtk -Ota
```
```bash
pwsh -File tools/run-emulator.ps1 -Target probe
```

Unlike the smoke test this one opens a window, so on Linux it needs an X or
Wayland session; `-Headless` runs `org.microemu.app.Headless` instead.

`-Ota` drives WTK's `-Xjam` provisioning flow against `dist/probe.jad`, which is
the closest desktop approximation of installing over the air.

## Observations

### MicroEmulator 2.0.4

* Must run on JDK 8. A current JDK fails on AWT/security APIs removed after 8.
* The default device is a generic portrait handset. The showcase uses a 320x240
  landscape canvas, so layout impressions from the default skin do not transfer;
  `KeyScreen` reports the actual canvas size at runtime.
* Sockets are real host sockets, so `tools/echo-server.py` on `127.0.0.1:7777`
  is reachable from the "Raw TCP" screen.

#### A blank white screen means a missing dependency

**Symptom:** the emulator window opens, the device skin is drawn, and the screen
stays white. No error appears anywhere obvious.

**Cause:** MicroEmulator's `MIDletClassLoader` rewrites bytecode with ASM as it
loads each class. If ASM is absent it throws

```
java.lang.NoClassDefFoundError: org/objectweb/asm/ClassVisitor
    at org.microemu.app.classloader.MIDletClassLoader.findClass
```

**on stderr only** - stdout shows a perfectly normal-looking `openJar` line, so a
launcher that captures just stdout reports success while nothing runs.

`asm-3.1.jar` is now pinned in `tools/sdk.lock.json` (the version comes from
`org.microemu:microemu:2.0.4`'s own POM). Classpath order also matters:
`microemu-injected` must come **after** `microemu-javase`, or MicroEmulator
refuses with "Wrong Injected class detected".

Lesson recorded because it generalises: when the emulator shows nothing, read
stderr first. `tools/run-emulator.ps1` no longer swallows it.

#### Launching

Passing the JAR makes MicroEmulator display its launcher list and wait for a
keypress. Putting the JAR on the classpath and naming the MIDlet class starts it
straight away, which is what `run-emulator.ps1` now does.

#### Text input: TextBox is simplest; Form TextFields need D-pad focus

A `TextField` inside a `Form` has to be focused and then activated before it
accepts input, and how that activation happens is device specific. In
MicroEmulator 2.0.4 the initially focused field shows a caret and accepts keypad
input. Up/Down moves focus between fields. Clicking another field with the
desktop mouse does not move MIDP focus, so mouse-only testing gives the false
impression that the fields are inert.

A `TextBox` is still the simplest choice for a single value because it is
editable the moment it is shown. Multi-value screens such as profile editing
and Settings can use a `Form` with `TextField` items, provided D-pad traversal
is tested on the intended runtime.

### Sun WTK 2.5.2_01

Not installed yet. Fill in when it is:

```
installed on Win11 :
installer issues   :
emulator launches  :
OTA (-Xjam) works  :
preverify.exe diff :
```

## Desktop harness instead of an emulator

For anything above `tg.io.Transport`, prefer `./tools/test.ps1` over an
emulator. It compiles the same `src/` and swaps `tgtest.SeTransport`
(`java.net.Socket`) for the MIDP one, giving a real debugger, real stack traces
and no UI in the way - while still exercising the exact code that ships.
