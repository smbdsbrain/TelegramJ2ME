# Nokia E6-00 — fast S60 hardware and a one-hour FakeTLS trap

The fourth physical handset this client has run on, the first S60 device, and
the first whose login failure was caused by a historically correct but now
wrong time-zone table.

It reports itself as
`NokiaE6-00/111.140.0058/sw_platform=S60;sw_platform_version=5.3;java_build_version=2.3.24`.
Everything below came from `probe-e6-00.jar`, the client's own diagnostics and
a real successful production login on 2026-08-12, not from a specification
sheet.

```
microedition.platform      = NokiaE6-00/111.140.0058/.../java_build_version=2.3.24
microedition.configuration = CLDC-1.1
microedition.profiles      = MIDP-2.1
microedition.encoding      = ISO-8859-1
microedition.locale        = ru-RU
```

Every optional API the probe checks is present: raw TCP, server sockets, UDP,
HTTPS, RMS, JSR-75 FileConnection 1.0, JSR-82 Bluetooth 1.1, JSR-135 MMAPI 1.2,
JSR-179 Location 1.0, JSR-120 SMS, JSR-184 M3G 1.1, PIM 1.0, the Sensor API 1.2
and GameCanvas. The runtime exposes two USB and 64 Bluetooth comm ports.

---

## End-to-end verdict

After correcting the time zone, production Telegram works through the FakeTLS
MTProxy. The physical handset has successfully:

- signed in and resumed the resulting session;
- loaded the dialog list and opened a chat;
- loaded history, thumbnails and avatars;
- marked messages read;
- sent text messages;
- sent reactions;
- opened and authorized an auxiliary DC1 connection, then began DC4 setup.

The client's uploaded diagnostic ring independently records the operations that
fit in its bounded tail:

```
auth.signIn                 = 1 149 ms, signed in
messages.getDialogs         = 1 292 ms
messages.getHistory         =   370 ms
messages.sendReaction       =   333 ms
connection state            = online
updates state               = live, state ready
route                       = mtproxy/faketls
```

Text-message sending was observed on the handset; it had scrolled out of the
diagnostic ring by the time the report was uploaded.

## Memory: at least 8 MB, with room still left

The probe reached its own ceiling without reaching the VM's:

```
totalAllocated       = 8 388 608      (8192 KB across 1024 chunks)
largestSingleAlloc   = 8 388 608      (8192 KB in one block)
lowestFree           = 6 096..6 412
hitOutOfMemory       = false
```

Two runs agree. The honest result is **at least 8 MB of allocatable Java heap
and an 8 MB contiguous allocation**, not an 8 MB maximum. The client later
measured and restored this budget:

```
heapCeiling = 9 371 648 (9152 KB)     heapSource = stored
heapBlock   = 8 323 072
packet = 1 048 576    inflate = 2 097 152
photoBytes = 524 288  photoPixels = 307 200
dialogs = 120/40      history = 120/30
headroom = 8 352 KB
sheds = 0 freeing 0 KB
```

The E6 is comfortably above every client budget. The emoji sheet costs only
18–25 KB and decodes in 63–66 ms.

## RMS: about 30.48 MiB per record store

```
sizeAvailable                = 31 959 040
readBack                     = identical
largestRecord                = 65 536 bytes
wrote into a second store    = 131 072 bytes
first store headroom changed = 0
VERDICT                      = PER RECORD STORE
```

As on the C3-00, 64 KiB is the probe's search ceiling and therefore only a
lower bound on the record limit. The useful finding is the shape: consuming
128 KiB in a second store did not move the first store's reported headroom.

The live client restored a marker from its previous launch, found its key and
update stores, and reported `key store: no write failures`. It persisted the
new DC1 auth key created during the captured session. Storage is not a
constraint.

## The login failure: Moscow is GMT+4 in this firmware

The phone arrived with FakeTLS failing before MTProto:

```
invalid FakeTLS handshake record, type 0x15 (alert), version 3.3,
length 2 [fatal illegal_parameter(47)], expected type 0x16
```

The release was not broken. Firmware `111.140.0058` labels its Moscow / Saint
Petersburg zone as **GMT+4**, matching Russia's old permanent-summer-time rule.
Modern Moscow is GMT+3. A locally plausible wall-clock display therefore still
made `System.currentTimeMillis()` one hour wrong.

FakeTLS puts the client's Unix time into the last four bytes of the authenticated
ClientHello random. The proxy rejects a hello stamped an hour away before any
MTProto exchange can learn a server-time offset. Selecting the firmware's
**Kaliningrad GMT+3** entry produced the correct Unix time, and the same client
and proxy logged in successfully.

The production handshake then measured the phone only 54 seconds behind the
server and installed `offset=54s`. This agrees with the earlier collector
comparison, which put uploaded reports roughly 50–55 seconds behind receipt.
The one-hour error was gone.

The operational rule is stronger than “set the clock”: **check the date, the
displayed time and the numeric GMT offset**. Old firmware can show the right
city and a believable clock while still producing the wrong Unix timestamp.

Within one process the clock itself is excellent:

```
currentTimeMillis tick = 1 ms
Thread.sleep(250) x8   = 251/251/251 ms
early returns          = 0
backwards steps        = 0
```

Whether the date and zone survive a full power cycle has not been measured.

## Network: two sockets work; direct port policy is destination-sensitive

Two simultaneous echo connections work without damaging the first:

```
first  = OK in 463 ms
second = OK in 267 ms
first after second = still usable
```

`Single socket mode` can remain optional. The real client also opened auxiliary
DC1 and DC4 FakeTLS connections while the primary DC2 session remained live.

Connect-only probes to the test DC's numeric address produced:

| Raw endpoint | Result |
|---|---:|
| `149.154.167.40:80` | `SecurityException`, port restricted |
| `149.154.167.40:443` | `SecurityException`, port restricted |
| `149.154.167.40:5222` | connected in 116 ms |
| `149.154.167.40:8443` | connected in 155–195 ms |

This is evidence about `socket://` to that numeric Telegram endpoint, not a
global statement that every destination on port 443 is impossible. Separately,
the real hostname-based MTProxy route worked on port 8443 after the time-zone
correction. The exact host-versus-literal-IP boundary still needs a controlled
probe.

The direct bootstrap route currently uses a numeric address on port 443, so on
this firmware it needs either an allowed-port MTProxy or another fallback.

## Display: fullscreen trades width for height

The E6 reports 24-bit colour, repeat events, pointer events and pointer motion,
and a double-buffered Canvas. Font metrics are large but consistent:

```
small  = 32 px high, baseline 26, 'M' width 20
medium = 37 px high, baseline 30, 'M' width 23
large  = 41 px high, baseline 34, 'M' width 26
```

The Canvas geometry is the unusual result:

```
normal     = 640x377
fullscreen = 558x480
change     = -82 px wide, +103 px tall
```

Fullscreen gains about 11% total area but makes the Canvas 12.8% narrower. It
does not simply remove a bottom bar; the firmware relocates UI chrome to the
side. A client-wide `setFullScreenMode(true)` therefore needs an E6-specific
layout check rather than being treated as a free 103-pixel gain.

The ordinary client UI was usable: chat navigation, composing and reactions
all worked. Key codes, key timing and background-socket behaviour remain
unmeasured.

## Text and images

The platform default is ISO-8859-1 and `String.getBytes()` loses Cyrillic and
emoji, as on the other measured handsets. Every client-owned path tested is
correct:

```
Utf8 encode/decode        = PASS
RMS UTF-8 round trip      = PASS
report compose/redact     = PASS
platform String.getBytes = LOSSY (expected)
```

MIDP PNG decoding passes. Native JPEG decoding rejects a valid baseline JPEG
with `IllegalArgumentException: bad image format`. This does **not** break the
client: Telegram JPEG paths use the bundled `tg.ui.JpegDecoder`, not the
platform decoder. Thumbnails and avatars decoded successfully in the real
session.

## Crypto and a real auth-key handshake

All 22 packaged crypto vectors passed. Two benchmark runs were stable:

| Operation | Run 1 | Run 2 |
|---|---:|---:|
| SHA-256, 64 KiB | 40 ms | 35 ms |
| AES-IGE encrypt, 16 KiB | 82 ms | 84 ms |
| AES-IGE decrypt, 16 KiB | 87 ms | 87 ms |
| 256-bit modPow | 17 ms | 18 ms |
| 512-bit modPow | 62 ms | 66 ms |
| 1024-bit modPow | 445 ms | 432 ms |
| 2048-bit modPow | 3 275 ms | 3 314 ms |

A real production DC1 authorization measured:

```
entropy barrier          = 3 gathers, 405/256 bits, 393 ms
pq factorisation         = 1 924 ms
two 2048-bit modPow      = 7 653 ms
handshake complete       = 12 970 ms
auth key persisted       = yes, seeding version 1
```

The pair of real exponentiations is a little slower than twice the isolated
benchmark but in the same class. This is about 2.7 times faster than the
C3-00's 34.5-second exchange. A later DC4 exchange independently measured
7 598 ms for the two exponentiations; its completion line had fallen past the
uploaded ring boundary.

PBKDF2-HMAC-SHA512 ×100000, which matters only for cloud-password login, was
not run because Upload all deliberately excludes it.

## Entropy: the production barrier is stable, the long probe is not

The standalone entropy measurement was run three times:

| Run | Headline | Samples outside calibration |
|---|---:|---:|
| Upload all #1 | 0 bits/gather | 1 849 / 1 994 |
| Manual run | 229 bits/gather | 8 / 1 969 |
| Upload all #2 | 0 bits/gather | 1 904 / 1 999 |

The two zero results do not show a frozen clock. Their 200 ms calibration
learned ranges of `104..527` and `263..580`, then the longer pass ran mostly
above those ranges. Conservatively folding every out-of-range value into the
end bucket made that bucket hold 92–95% of the distribution and drove the
estimate to zero. The manual run learned `206..801`, clamped only eight samples
and measured 2.250 bits per sample.

The actual `AuthKeySeeding` path does not use that fixed calibration band. Four
probe outcomes and two real production handshakes all reached the target in
three gathers:

| Source | Samples | Credited bits | Time |
|---|---:|---:|---:|
| Probe | 327 | 531 / 256 | 388 ms |
| Probe | 360 | 675 / 256 | 390 ms |
| Probe | 335 | 921 / 256 | 404 ms |
| Probe | 360 | 945 / 256 | 383 ms |
| Client, DC1 | 360 | 405 / 256 | 393 ms |
| Client, DC4 | 358 | 939 / 256 | 387 ms |

That is direct evidence that the production barrier terminates consistently on
this runtime. It is also evidence that the long probe's calibration is not
stationary under this S60 JVM. The correct documentation is **not** one fixed
bits-per-gather number: fix or redesign the calibration before using the
standalone zero/229 figures as a security claim.

Five application launches produced five different seed digests and no
collision, but these were not documented full power cycles. Held-object identity
hashes are a sequential counter; dropped objects were all distinct. Heap
readings contribute at most drift and are conservatively charged at zero by the
barrier.

## Diagnostics and remaining work

The successful run reported an online connection, live updates, 8.35 MB of
headroom, no cache shedding and no key-store write failure. Its crash store held
three earlier task failures: one mistyped login code, then `auth.cancelCode`
being interrupted by `destroyApp` and its late callback finding the toolkit
closed. Those are retained operational errors, not a VM or hardware crash; no
unexplained failure occurred during the successful session.

Still open:

- whether the date, time zone, sockets and seed log survive full power cycles;
- PBKDF2 ×100000 for 2FA cost;
- key codes and key timing for the physical QWERTY keyboard;
- background socket behaviour;
- the actual heap ceiling, largest RMS record and largest installable JAR;
- a controlled hostname-versus-literal-IP port-443 test;
- repeated entropy runs after correcting the calibration instability.

## What this changes

- Nokia E6-00 is not near the client's memory or CPU floor; it is the fastest
  measured handset and supports concurrent media/session sockets.
- Production sign-in, persistence, chats, messages, reactions, media and live
  updates are now hardware-verified on S60 5.3.
- The direct numeric-IP port-443 route is not usable under the observed AMS
  policy, while 5222 and 8443 are.
- A city-name time-zone setting is not sufficient preparation for FakeTLS on
  old firmware. The numeric GMT offset must be checked.
- Fullscreen is a layout trade, not a pure size gain.
- The standalone entropy probe needs a calibration fix for JVMs whose spin
  range changes between its short and long passes; the production barrier is a
  separate measurement and reached its target in every reported run.
