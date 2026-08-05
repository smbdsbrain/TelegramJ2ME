# Nokia C3-00 — a 2 MB handset, and a bug that was never where it looked

The third device this client has run on, the first from a third vendor, and the
first to reach a working session before anything about it had been measured.

It reports itself as `NokiaC3-00/08.70`. Everything below was read off the
device by `probe.jar` and the client's own diagnostics, not from a
specification sheet.

The session was run over Wi-Fi through an MTProxy.

```
microedition.platform      = NokiaC3-00/08.70
microedition.configuration = CLDC-1.1
microedition.profiles      = MIDP-2.1
microedition.encoding      = ISO-8859-1
microedition.locale        = ru-RU
microedition.commports     = USB1
```

**Every optional API the probe checks is present**: raw TCP, server sockets,
UDP, HTTPS, RMS, JSR-75 FileConnection 1.0, JSR-82 Bluetooth 1.1, JSR-135 MMAPI
1.2, JSR-179 Location 1.0, JSR-120 SMS, JSR-184 M3G 1.1, PIM 1.0, GameCanvas.
Nothing absent. That makes it the richest of the three, and the first where a
`file://` export would be possible at all.

---

## Memory: 2 MB, and it works

Series 40 6th edition was expected to be more generous than the 2011 Alcatel.
It is the opposite — this is the tightest handset measured, by a factor of two
and a half.

```
totalMemory          = 2 097 152      (2.0 MB)
freeMemory at start  = 1 959 132
largestSingleAlloc   = 1 959 084      (1 913 KB in one block)
totalAllocated       = 1 949 696      (1 904 KB across 238 chunks)
hitOutOfMemory       = true
```

Against 5.0 MB on both earlier devices. Note the largest single block is 1 913
KB of a 2 048 KB ceiling: **the constraint here is total size, not
fragmentation**, so a budget that fits at all will get its allocations.

The client measured this itself on first launch and sized to it:

```
heapCeiling = 2 097 152 (2048 KB)     heapSource = stored
viable      = true
packet = 524 288    inflate = 851 968
photoBytes = 262 144   photoPixels = 153 600
dialogs = 60/20     history = 60/15
peers = 250   avatars = 8   thumbs = 6   screens = 8
chatWindow = 1 screens   prefetch = 7 messages/10 dialogs
```

Half of what the same build takes on a 5 MB budget — packet 1 MB, dialogs
120/40, avatars 16, a 3-screen chat window. It then loaded dialogs, history and
avatars, opened chats and sent messages with **`sheds = 0 freeing 0 KB`**: it
never had to release a cache under pressure. The budget was set correctly rather
than optimistically, which is the claim the whole dynamic-budget mechanism
exists to make.

A second suspect was cleared on the way: the emoji sprite sheet costs
`heapCost = 32 612 (31 KB)`, decoded in 48 ms — cheaper than the 48 KB measured
on the GT-C3592, and 1.5% of this heap.

## The reported bug was not the bug

The device arrived with a clear symptom: **sign-in did not survive a restart**,
on a build where it survived fine on both other handsets. That read as a
persistence failure, and it was not one. The client's own log:

```
loaded stored auth_key dc2 prod id=... sha1=...
resumed with stored key for dc2
route preflight help.getConfig = 1164 bytes      <- the route works
W request failed without replay: timed out waiting for a reply to msg ...
W authorization check failed: timed out waiting for a reply to msg ...
```

RMS returned the same key every launch and `help.getConfig` completed. What
failed was the *next* RPC. `checkAuthorization()` caught every `IOException` —
including the 60-second reply timeout — returned null, and the UI answered null
with the phone-number box, with a good `auth_key` still in storage.

A client defect, not a device quirk. It is latent on any link slow or lossy
enough to drop one request; this was simply the first handset slow enough to
expose it. Socket opens through the proxy took **9.0 s, 18.3 s and 10.1 s**, and
one connect task took **83.7 s** — and since this was Wi-Fi, that latency
belongs to the proxy path rather than the radio.

Fixed by making the check three-valued: only the server can say a session is
dead, and silence now falls back to cached dialogs and Retry rather than to a
login screen.

## Storage: 512 KiB per record store

```
sizeAvailable  = 524 288        (exactly 512 KiB)
readBack       = identical
largestRecord  = 65 536         the probe's own search ceiling, so a floor
```

512 KiB looked at first like a suite-wide quota, which would have put the
client's cache budget — 256 KiB avatars + 192 KiB history + dialogs + crash log
— over the line. It is not. Filling one store and watching another settles it:

```
witness before             = 524 288
wrote into a second store  = 131 072 B
witness after              = 524 288
headroom dropped           = 0
VERDICT: PER RECORD STORE
```

128 KiB written into one store moved an untouched store's headroom by exactly
zero. Every record store gets its own ~512 KiB, the largest single store the
client wants is half that, and nothing needs changing — no `MIDlet-Data-Size`,
no storage-derived budget. `getSizeAvailable()` alone could not have answered
this: an implementation with one shared pool that subtracts only the current
store looks identical.

A real session occupies about 52 KB across eight stores, of which the auth key
is 2 KB.

Storage survives power cycles: eleven probe launches, each reading back the
marker the previous one wrote.

## The clock resets to 2011 on every power cycle

Within a running session this is the best clock of the three: a **1 ms** tick,
`Thread.sleep(250)` never returning early, worst overshoot 4 ms, no backwards
step observed.

Across a power cycle it does not survive, and does not resync:

```
#1 t=1785963672422    <- set by hand
#2 t=1314856820964    <- ~2011
#3..#9 t=13148568xxxxx
clock went BACKWARDS 5x  =>  CLOCK RESETS AT BOOT
```

Every cold boot starts about fifteen years in the past. Two consequences, and
only one of them is handled.

For seeding, the probe already detects it and charges the wall clock at zero.
For the protocol it is unhandled: `msg_id` carries unix seconds and the server
rejects anything more than 300 s ahead or 30 s behind, the time offset is
learned only from a server reply, and **the resume path never runs a
handshake** — so a restored session on a freshly booted phone sends its first
encrypted message with a fifteen-year-old `msg_id` and depends on
`bad_msg_notification` to recover. That recovery gets one retry, which a fresh
session usually spends on `bad_server_salt`. Not fixed; recorded.

## Entropy: the finest clock measured, and what that costs per sample

Two runs, both after a full power cycle:

```
clock tick       = 1 ms, FINE, 322 reads per tick
jitter           = 1.125 – 1.375 bits/sample
gather()         = 120 samples  ->  135 – 165 bits
256 bits needs 2 gathers ("NOT SUFFICIENT AS ONE GATHER")
hashCode         = 7.875 bits/allocation, 8.000 bits/call
freeMemory       = 1 distinct value across 256 idle reads
```

The third data point, and it sharpens the relationship rather than just
extending it:

| Handset | tick | samples/gather | bits/sample | bits/gather |
|---|---|---|---|---|
| Alcatel OT-810D | 4 ms | 26 | 2.250 | 58 |
| Samsung GT-C3592 | 12 ms | 10 | 2.125 | 21 |
| Nokia C3-00 | 1 ms | 120 | 1.125 – 1.375 | 135 – 165 |

`gather()` spends a fixed wall-clock budget, so sample count tracks 1/tick — but
**per-sample entropy falls as the tick gets finer**, by half between 4 ms and
1 ms. A coarse clock lumps more scheduler noise into each delta; a fine one
spreads it across more readings that are individually less surprising. The net
still favours the fine clock heavily, but the relationship is not linear and
should not be extrapolated.

`freeMemory` returning one distinct value across 256 idle reads confirms that
charging it at zero is accurate rather than merely conservative.

**This handset also answers a question the other two could not.** Its clock
resets to the same value every boot, so eight cold-boot launches began from the
same wall clock — a ten-second spread that is boot-to-launch delay, not elapsed
time — and produced eight different seeds, no collisions. The GT-C3592's
twelve battery-out launches could not show this because its clock advanced
between them. Eight samples is not a strong statistical claim, but it is the
first direct evidence that the non-clock sources contribute anything across a
power cycle. Details in issue #2.

Key-press timing is **3.000 bits/press, 86 presses for 256 bits** over 50
presses and 18 distinct keys — identical to the OT-810D. `gcd(deltas) = 1 ms`,
and here that genuinely matches the measured tick rather than meaning "no
quantum found".

## Display: 52 pixels the AMS keeps, and gives back on request

```
canvas normal     = 320x188
canvas fullscreen = 320x240
gain              = +0 px wide, +52 px tall
numColors         = 16 777 216   isColor = true
fonts (h/base/'M')  small 18/14/11   medium 21/17/14   large 26/21/17
hasRepeatEvents   = true
hasPointerEvents  = false
isDoubleBuffered  = true
```

The panel is 320×240 and a MIDlet gets 320×188 by default. `setFullScreenMode`
recovers all 52 pixels, and **nothing in `src/` calls it** — with the 18 px
small font that is ten body lines where thirteen would fit.

Exactly 52 pixels is also what the GT-C3592 loses. Two vendors, the same number;
worth assuming a third will do it too.

This also corrects an assumption that predated the device: "320×240, the same
geometry as the OT-810D, so one UI layout serves both" is true only in
full-screen mode. In the default mode this handset is 320×188 and the Alcatel is
not.

## Sockets: two at once, and the first survives

```
first  : OK in 3 391 ms
second : OK in 205 ms
first after second: still usable (5 B echoed)
VERDICT: concurrent sockets work.
```

A straight divergence from the GT-C3592, where the second `Connector.open`
throws *and corrupts the connection already in use* — the reason media runs over
the session connection and `Single socket mode` exists. That constraint is a
property of that handset, not of Java ME, and the setting is right to stay
opt-in. (The 205 ms second open against 3 391 ms for the first is a DNS cache,
not a socket cost.)

Direct connection failed the same way it fails on both earlier devices: port 443
is refused to an untrusted MIDlet, so the session ran over an MTProxy on a high
port.

## Keys: the key code *is* the character

The keyboard is Cyrillic, and letter keys report the Unicode code point:

```
1073 = б    1074 = в    1080 = и    1084 = м    1089 = с
1090 = т    1091 = у    1099 = ы    1100 = ь
```

`getKeyName()` returns the letter itself. So for letter keys there is a rule
rather than a table: `keyCode` is the character. The probe previously printed the
character only for codes 32..126, which hid this behind bare numbers; it now
prints it for any printable code point.

One oddity: **`1080` (`и`) reports game action `DOWN`**, and every other letter
reports none. The client's Canvas screens navigate by `getGameAction`, so
pressing that letter on a message list scrolls it — harmless, because text entry
happens in a MIDP `TextBox`, but worth knowing.

The soft keys, call and end keys never reach the MIDlet at all: the AMS owns
them for the Options menu and for call handling. Neither does the navigation
cluster, in two separate captures.

## Image decoding

```
PNG control    : PASS 8x8 in 1 ms
JPEG optional  : FAIL IllegalArgumentException
```

Platform JPEG fails on all three handsets, which is why the client carries
`tg.ui.JpegDecoder`. This is a platform capability, not a client one.

## Text encoding

`microedition.encoding` is `ISO-8859-1`, as on both other devices, so
`String.getBytes()` destroys anything outside Latin-1 — measured here as
`café привет 👋` becoming `café ?????? ??`. The project's own `Utf8`, RMS
records and the report path all round-trip it byte for byte. Nothing on this
handset falls back to the platform default.

## What this changes

- **The dynamic memory budget is load-bearing, and now proven at half the heap
  it was designed against.** A 2 MB device runs this client with no cache
  shedding at all. The budgets are not merely tuned for 5 MB hardware.
- **`Single socket mode` stays opt-in.** One handset refusing a second socket
  was not a Java ME rule.
- **A transient network failure must never be reported as a logged-out
  account.** Fixed; the storage evidence that would have shown this in one
  reading is now in the client's own diagnostics.
- **Storage is not a constraint on Series 40.** 512 KiB per record store, and
  the client uses about a tenth of one.
- **`setFullScreenMode` is worth 28% of the vertical space here** and the same
  52 pixels on the GT-C3592.

## Still open

- **A restored session never establishes a time offset**, which matters most on
  exactly this device: it boots in 2011 every time. The resume path skips the
  handshake where the offset is normally learned, and the single corrective
  retry is usually spent on the server salt.
- **The 52 pixels are still being given away.** Calling `setFullScreenMode` is a
  client-wide UI change and needs checking on the OT-810D first — a handset that
  draws its own soft-key labels in that band would lose them.
- **No crypto benchmark on this device.** `crypto.jar` was not run, so the
  2048-bit modPow time, the AES-IGE and SHA-256 throughput and the full
  handshake cost are all unmeasured — and the seeding barrier has never run
  here, since the client resumed a stored key throughout.
- **Largest installable JAR is unmeasured**, as on both other devices; use
  `tools/build-size-ladder.ps1`.
- **The true RMS record ceiling is above 64 KiB but unmeasured** — the probe's
  search stops at its own ceiling, so the figure is a floor on all three devices.
- **Background socket behaviour is untestable here**: this handset offers no way
  to background a running MIDlet, so the user cannot trigger `pauseApp` at all.
  An incoming call presumably still can; that has not been tried.
- The key-timing figure has no confidence bound — 49 intervals, below the
  256-sample floor — and was taken at a deliberate pace by one person.
