# Samsung GT-C3592 — a second handset, and what it broke

The second device this client has run on, and the first one that found real
bugs rather than confirming existing measurements.

It reports itself as `SAMSUNG-GT-C3592` — the dual-SIM variant of the C3590, so
match on that string rather than the name on the box. Everything below was read
off the device by `probe.jar`, `crypto.jar` and the client's own diagnostics,
not from a specification sheet.

The session was run over a mobile data connection through an MTProxy.

```
microedition.platform      = SAMSUNG-GT-C3592
microedition.configuration = CLDC-1.1
microedition.profiles      = MIDP-2.0
microedition.encoding      = ISO8859-1
microedition.locale        = ru-RU
User-Agent                 = SAMSUNG-GT-C3592/1.0 NetFront/4.2
                             Profile/MIDP-2.0 Configuration/CLDC-1.1 UNTRUSTED/1.0
```

Optional APIs present: raw TCP, server sockets, UDP, HTTPS, RMS, JSR-75
FileConnection, JSR-82 Bluetooth, JSR-135 MMAPI, JSR-120 SMS, GameCanvas.
Absent: JSR-179 Location, JSR-184 M3G.

---

## Memory: not the constraint

The client used to die when a conversation was opened, and heap exhaustion was
the obvious suspect. It was wrong.

```
totalMemory          = 5 242 860      (5.0 MB)
freeMemory at start  =  4 880 088
largestSingleAlloc   = 5 040 984      (4 922 KB in one block)
totalAllocated       = 5 021 696      (4 904 KB across 613 chunks)
hitOutOfMemory       = true           only at 4.9 MB
```

That is the same class of heap as the OT-810D, which every memory budget in the
project was already sized against, and it hands out a single 4.9 MB block.

The emoji sprite sheet was the other suspect, on the theory that a 256×160
sheet costs ~160 KB decoded at 32bpp and is loaded on the first paint of a chat
screen. Measured:

```
emoji sheet heapCost = 49 472 (48 KB), decoded in 52 ms
```

48 KB out of 5 MB explains nothing. Both memory theories are dead, and the real
cause turned out to be in the transport — see below.

## RMS

```
sizeAvailable  = 5 956 847
readBack       = identical
largestRecord  = 65 536 bytes
```

`largestRecord` equals the probe's own search ceiling, so the true maximum may
be higher. What matters is that it is at least 64 KiB, which is exactly the
per-record cap the conversation cache already uses.

## The platform cannot decode JPEG

```
PNG control:   PASS 8x8 13ms
JPEG optional: FAIL java.lang.IllegalArgumentException
```

`Image.createImage(byte[])` refuses a valid baseline JPEG here. PNG, which
MIDP 2.0 mandates, works.

**This does not affect the client**, and the reason is worth stating plainly:
every JPEG path goes through the decoder bundled in `tg.ui.JpegDecoder`, not
through the platform. The only `Image.createImage` call on a live code path
loads the emoji sheet, which is a PNG. A decision taken long before this handset
was tested turns out to be the only reason images work on it at all.

Read the probe's JPEG result as a platform capability, not a client one.

## Crypto

```
sha256 64k       = 156 ms   (410 KB/s)
aes-ige enc 16k  = 233 ms   ( 68 KB/s)
aes-ige dec 16k  = 156 ms   (102 KB/s)

modPow  256-bit  =    25 ms
modPow  512-bit  =   169 ms
modPow 1024-bit  =  1230 ms
modPow 2048-bit  =  9458 ms
```

Observed in a real handshake, which needs two 2048-bit exponentiations:

```
two 2048-bit modPow in 19915 ms
handshake complete in 24080 ms
```

That independently reproduces the ~19 s handshake measured on the OT-810D.

Two consequences worth planning around. A first connection to any data centre
costs about half a minute, once, before the key is persisted. And AES-IGE at
102 KB/s means a 512 KB photo spends about five seconds in decryption alone,
before any decoding.

The pq factorisation that precedes the handshake varies with the factors: 324 ms
in one run, 8 252 ms in another. Both are normal.

---

## The bug this handset found: one socket at a time

The platform refuses a second concurrent socket:

```
task dialog avatar failed | ConnectionNotFoundException: socket open: failed
```

Worse, the *attempt* corrupted the connection already in use. From the device's
own log, 105 ms apart:

```
103.994 net connect socket://…        second socket, for an avatar
108.480 task messages.getHistory started
164.049 E task dialog avatar failed | ConnectionNotFoundException: socket open: failed
164.154 E task messages.getHistory failed | IOException: invalid FakeTLS application record
```

`invalid FakeTLS application record` is a desynchronised stream, not a protocol
error. Every attempt to load an avatar took the live connection down with it, so
`messages.getHistory` failed and the conversation rendered empty — and before
the failure was caught at all, the MIDlet simply died, which is where the
original "system error" came from.

The cause was in the client, not the handset: the media path opened a **new**
connection for every file, including files on the data centre the session was
already talking to. That is now fixed — a file on the session's own data centre
reuses the existing connection, which is what MTProto multiplexing is for. On
this device that made avatars and photos work for the first time:

```
media over the existing dc2 session
task dialog avatar ok in 2468 ms
task dialog avatar ok in 2024 ms
task dialog avatar ok in  546 ms
```

A file on a **different** data centre still needs its own connection, and here
there is no way around it. Two settings cover that case:

- **Single socket mode** — a file on another data centre pauses the session for
  the duration of the transfer rather than failing. Only for a photo the user
  explicitly opened; background work is never allowed to take the session down.
- **Chat avatars** — off disables the whole avatar path. The client also retires
  it automatically for the rest of a session once the platform has refused a
  socket.

## Text encoding

`microedition.encoding` is `ISO8859-1`, which is why this project has always
converted UTF-8 by hand instead of using `String.getBytes()`.

One place had been missed. Crash entries were written to RMS through the
platform encoding, so a crash naming a Cyrillic chat came back as `?????` — the
characters were destroyed on the way in and no careful reading afterwards could
recover them. Fixed, with the entry size cap now backing off to a character
boundary so a truncated entry cannot end mid-sequence.

## Transport quirks seen on the wire

```
W net DELAY option rejected: IOException
W carrier padded a plaintext frame with 81 bytes, past the documented 15
```

`SocketConnection.DELAY` is not settable here; harmless, the client carries on.
The padding observation is more interesting: something between the handset and
the server pads plaintext handshake frames well past the 15 bytes the protocol
documents. The client tolerates it. Worth knowing before assuming a malformed
frame means a bug.

Reports uploaded from this handset always arrive `Transfer-Encoding: chunked`
and never carry a `Content-Length`, whatever the MIDlet sets. Individual POSTs
also drop with no pattern related to size — two 480-byte uploads failed between
successful ones twice as large — so anything talking to this device needs
retries rather than assumptions.

---

## RNG seeding: 21 bits per gather, and why

Measured 2026-08-04 by `probe.jar`, menu **Entropy measure**, build `d57b9c9`
(`-Env test`, unobfuscated — the same build the OT-810D series was read from, so
the two are directly comparable).

```
== VERDICT ==
jitter 2.125 bits/sample
  x 10 samples per gather()
LOWER BOUND = 21 bits/gather
256 bits needs 13 gather(s)
NOT SUFFICIENT AS ONE GATHER
```

**The per-sample entropy is fine. The clock is the problem.**

```
-- a. clock --
changes = 110 in 1500 ms
min delta = 12 ms  <- tick
max delta = 77 ms
distinct deltas = 4
top: 13(102) 12(6) 77(1)
reads per tick = 7111
COARSE (>= 10 ms)
```

`Entropy.gather()` samples jitter for a fixed 120 ms. At a 12 ms tick that window
holds **10 samples**; the OT-810D's 4 ms tick held 26. Per sample the two devices
are nearly equal — 2.125 bits here against 2.250 there — so the whole 58 → 21
collapse is the tick, not the quality of the noise.

```
-- b. jitter spins --
n = 1545 in 19993 ms
range 337..8309 step 16
distinct 111 lvls 8
p_max = 100 per 1000
H_raw = 3.250
H_99% = 3.000
Hc = 1.875 pair/2 = 1.375
serial correlation detected;
discounted by pair/2 over Hc.
lag1 repeats = 1 per 1000
headline H = 2.125
gather() takes 10 samples
=> 21 bits per gather()
```

Two ways this figure is more solid than the OT-810D's, and one way it is not.
There is **no `clamped` line** — every sample landed inside the calibrated range,
where the alcatel lost 5.9% to an end bucket. Serial correlation is milder
(`lag1 repeats = 1 per 1000` against 4). Against that, the measurement filled its
whole 20 s budget to collect 1545 samples and still ran the same MCV estimator,
which assumes IID; the pair check is a discount, not the full SP 800-90B non-IID
track.

**Identity hash codes are a real source here, unlike on the OT-810D.**

```
-- c. hashCode --
held 256: distinct 256
 stride -109822092 in 1/255
 => H 7.875 per allocation
dropped 256: distinct 256
 => H 8.000 per call
```

Allocate-and-discard still yields 256 distinct codes, so the `new Object()`,
`Thread.currentThread()` and digest hash codes inside `gather()` do contribute.
On the alcatel the dropped case collapsed to ≤1 distinct and was worth nothing.
It is still charged at **zero** in the 21 above, deliberately — which is another
reason to read 21 as a floor.

```
-- e. cross-restart --
launches recorded = 12
digest collisions = 0
clock advances across
launches: spread 347579 s
```

The series was run the same way as the alcatel's: **battery out between
launches**. Twelve launches, no repeated seed.

**But the wall clock does not come back at a fixed value here, and that weakens
the test rather than strengthening it.** On the OT-810D the RTC did not survive
battery removal, so every cold boot started from the same hand-set `00:00` — two
launches began at the same millisecond and their digests still diverged, which
isolated jitter and the allocator as the sources actually carrying the pool. On
this handset the clock advances monotonically across the whole series, so time is
restored on boot, whether from an RTC that survives the battery or from the
operator's network. Every launch therefore started from a *different* clock, and
the wall clock alone would explain the absence of collisions.

So: twelve cold boots, zero collisions, and no evidence here about the non-clock
sources. The alcatel's identical-clock result remains the stronger of the two,
and the corresponding experiment on this device would need the clock pinned by
hand — which the firmware may simply overwrite from the network.

### f. Key-press timing

50 presses over 15 seconds, 16 distinct keys.

```
n = 50 presses over 15 s
delta 90..1022 ms
mean 310 ms
distinct deltas = 33
gcd(deltas) = 1 ms
H_raw = 2.750
d & 15 -> 2.375
d & 31 -> 2.750
distinct keys = 16
usable = 2.750 bits/press
256 bits needs 94 presses
```

**2.750 bits per press against the OT-810D's 3.000** — much closer than the
jitter figures, because the keyboard does not depend on the clock tick the way
`gather()` does. As on the alcatel, `gcd = 1 ms` means no single quantum was
found rather than millisecond resolution: this device's tick alternates 12/13 ms,
so sums of intervals reach every integer. And with 49 intervals there is no 99%
bound here either, so this is a point estimate at a deliberate typing pace, not a
floor.

The economics still land where the alcatel put them, even though jitter is nearly
three times worse here. A gather costs ~130 ms for 21 bits — about **162 bits per
second of blocking, with nobody involved**. Key presses at the observed 310 ms
mean interval yield about **9 bits per second and require the user to sit there**:
94 presses, roughly half a minute of deliberate typing, for one key. Jitter
remains the mechanism and key timing remains a supplement to fold in where it is
free.

### Consequence for the auth-key barrier

`tg.crypto.AuthKeySeeding` folds in `GATHERS = 5`, sized from the OT-810D's 58
bits against a 256-bit target. On this handset five gathers are worth about
**105 bits, not 256**, and the probe's own verdict says the target needs 13.
Raising the count is tracked in issue #2; it is not a change this measurement
alone should make, because the right number is a function of the slowest clock
among supported devices and only two have been measured.

### The barrier itself, running on this handset

A full production sign-in, `tg` 0.7.0 `-Env production` through an MTProxy to
dc2. First launch, no stored key:

```
66.892 I auth-key entropy barrier: 5 gathers in 713 ms
66.892 I -> req_pq_multi nonce=<redacted>
90.891 I two 2048-bit modPow in 19521 ms
92.577 I handshake complete in 25685 ms, auth_key dc2 prod id=... sha1=6b9a2ef4
92.771 I persisted auth_key dc2 prod id=... sha1=6b9a2ef4
```

**713 ms, against 674 ms for the same five gathers on a desktop JVM.** The
barrier is clock-bound, not CPU-bound — `collectJitter` spends a fixed wall-clock
budget rather than doing work — so it does not get more expensive on slow
hardware. Everything around it does: the two 2048-bit modular exponentiations on
the line above took 19 521 ms, and on an earlier run pq factorisation took
7723 ms against 7 ms on the desktop, about 1100× slower. **The barrier is 2.7% of
the exchange it precedes.**

Note also that `handshake complete in 25685 ms` excludes the barrier: the seeding
cost is carried separately in `Handshake.Result.entropyMillis`, so the published
exchange timing still means the exchange.

Reconnecting later in the same run, after the key had been written to RMS: same
key id, `resumed with stored key for dc2`, **no barrier line** — route to live
session in 2.3 s. One barrier per key generated, not per connection.

Then a full relaunch of the MIDlet, which is the case that matters for startup
cost:

```
 0.000 I client 0.7.0 build 2b8aa96 env production
 0.207 I heap: stored ceiling 5056k, block 4736k
 9.151 I connected to dc2 <mtproxy>:8443
 9.255 I loaded stored auth_key dc2 prod id=... sha1=6b9a2ef4
 9.268 I resumed with stored key for dc2
12.835 I task connect ok in 8847 ms
```

Cold process start — `heapSource = stored` confirms it was not the first launch —
and the key came back out of RMS with the same id. **No `entropy barrier` line
appears anywhere in that run.** Connect completed in 8847 ms against 31 627 ms
for the launch that had to generate the key. The seeding barrier is paid once
per key and never again.

The `<redacted>` on the nonce — and on the server salt elsewhere in the same log —
is the on-device redaction in `tg.plat.Report` doing its job: the barrier's own
diagnostics carry only a count and a duration, and the report path strips
everything else before it leaves the handset.

---

## What this changes

- Memory budgets sized for ~5 MB are correct for this handset too. No economy
  mode is needed, and the one sketched before these measurements would have
  been solving a problem that does not exist.
- The bundled JPEG decoder is load-bearing, not an optimisation.
- Media transfers must reuse the session connection wherever the data centre
  allows it. That is now the default everywhere, not a workaround for this
  device.
- A first visit to a data centre costs ~30 seconds of key generation. Anything
  that can trigger one in the background needs to be deliberate about it.

## Still open

- Largest JAR this device will install has not been measured; use
  `tools/build-size-ladder.ps1`.
- **The seeding barrier does not reach its 256-bit target here.** 21 bits per
  gather × 5 gathers ≈ 105. `Entropy.estimatedBitsPerGather` still reports the
  OT-810D's 58 on purpose — it is one device's figure, not a fleet minimum — so
  sizing `AuthKeySeeding.GATHERS` against the slowest supported clock is issue
  #2 and not something this measurement should do on its own.
- **Cold-boot determinism is untested against a fixed clock here.** The twelve
  battery-out launches produced no repeated seed, but this handset restores the
  time on boot, so each one started from a different wall clock and the result
  says nothing about the non-clock sources. The OT-810D's identical-clock
  experiment has no equivalent here yet, and may not be reproducible if the
  firmware re-syncs time from the network.
- Key codes and canvas size have not been recorded; `Keys` was not run.
- The key-timing figure has no confidence bound — 49 intervals, below the
  256-sample floor — and was taken at a deliberate pace by one person.
- The true RMS record ceiling is above 64 KiB but unmeasured.
