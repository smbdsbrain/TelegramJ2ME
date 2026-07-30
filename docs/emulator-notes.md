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
| Is memory use acceptable? | Desktop heap is effectively unbounded; `Runtime.totalMemory()` bears no relation to the phone. |
| Will `socket://` be permitted? | There is no AMS security policy, no untrusted-MIDlet permission prompt. |
| Is it fast enough? | 2048-bit `modPow` is 12 ms on this desktop and will be orders of magnitude slower on a 208 MHz ARM without a JIT. |
| Do the keys work? | Key codes and soft keys are vendor specific; MicroEmulator invents its own. |
| Will installation work? | No AMS, no JAD parsing, no JAR size limit, no signing policy. |

The WTK emulator narrows the first, third and sixth of these - it is the
reference MIDP implementation and does run OTA provisioning. A physical-device
validation pass would still be required; none has been performed.

## Running

```powershell
./tools/run-emulator.ps1 -Target probe            # MicroEmulator
./tools/run-emulator.ps1 -Target probe -UseWtk    # Sun WTK, needs WTK_HOME
./tools/run-emulator.ps1 -Target probe -UseWtk -Ota
```

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
