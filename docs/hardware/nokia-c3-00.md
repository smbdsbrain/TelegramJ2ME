# Nokia C3-00 — a 2 MB handset, and a bug that was never where it looked

The third device this client has run on, the first from a third vendor, and the
first to reach a working session before anything about it had been measured.

It reports itself as `NokiaC3-00/08.70`. Everything below was read off the
device by `probe.jar` and the client's own diagnostics, not from a
specification sheet.

The session was run over Wi-Fi through an MTProxy. A later session, on
2026-08-06, added the seeding-barrier and handshake figures below and is marked
where it applies.

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

Every cold boot starts about fifteen years in the past. Three consequences, and
only one of them is handled.

For seeding, the probe already detects it and charges the wall clock at zero.

For the protocol it is unhandled: `msg_id` carries unix seconds and the server
rejects anything more than 300 s ahead or 30 s behind, the time offset is
learned only from a server reply, and **the resume path never runs a
handshake** — so a restored session on a freshly booted phone sends its first
encrypted message with a fifteen-year-old `msg_id` and depends on
`bad_msg_notification` to recover. That recovery gets one retry, which a fresh
session usually spends on `bad_server_salt`. Not fixed; recorded.

**And it makes the MTProxy route impossible until the clock is set**, which is
what this handset actually spent an evening demonstrating. A FakeTLS
`ClientHello` carries the client's unix time XORed into the last four bytes of
the client random — `tg.io.FakeTlsTransport` — and the proxy checks it against
its own clock. A hello fifteen years stale is not recognised as the proxy's own
traffic, so the proxy relays the connection to the real host the secret names
(`ok.ru` here) and that host answers with a genuine TLS alert:

```
mtproxy FAIL invalid FakeTLS handshake record, type 0x15 (alert),
version 3.3, length 2 [fatal illegal_parameter(47)], expected type 0x16
```

Confirmed by construction, not by inference: hardcoding this handset's own
timestamp (`1314895348`, 2011-09-01) into the hello on a desktop reproduces the
alert byte for byte, and setting the phone's clock made the same build connect
on the first attempt. The message names the alert - which is what the C3-00's
previous session bought - but not the cause, and the cause is one the client can
detect. Recorded as open.

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

## The seeding barrier, measured here

Measured 2026-08-06, `probe.jar` and `tg.jar` 0.7.1 `build 637f97a`, after the
barrier stopped using a compiled-in gather count and started sizing itself from
what it measures while collecting (issue #2).

**From the probe**, three runs of the new **Seeding barrier** item, which runs
the real `tg.crypto.AuthKeySeeding` against a throwaway pool:

```
gathers  = 3            gathers  = 3            gathers  = 3
samples  = 330          samples  = 349          samples  = 349
credited = 371 / 256    credited = 523 / 256    credited = 523 / 256
elapsed  = 526 ms       elapsed  = 393 ms       elapsed  = 393 ms
           175 ms/gather          131 ms/gather          131 ms/gather
```

**From the client**, four handshakes across the same evening, all three gathers
and all inside 410 ms:

```
auth-key entropy barrier: 3 gathers, 258/256 bits from 345 samples in 405 ms
auth-key entropy barrier: 3 gathers, 315/256 bits from 360 samples in 405 ms
auth-key entropy barrier: 3 gathers, 500/256 bits from 286 samples in 404 ms
auth-key entropy barrier: 3 gathers, 514/256 bits from 343 samples in 394 ms
```

Two things are worth reading off that.

**The probe's verdict and the barrier's decision agree, and disagree by exactly
one gather for a stated reason.** The same session's verdict says
`jitter 1.500 bits/sample x 120 samples => 180 bits/gather, 256 bits needs 2
gather(s)`. The barrier takes three, because two gathers are about 230 samples
and `MinEntropy.MIN_SAMPLES_FOR_CONFIDENCE` is 256 — below which this project
states no bounded figure at all. On this handset the sample floor decides the
count, not the bit target; on the GT-C3592 it will decide it even more strongly.

**The credited total varies by a factor of two between runs on one device** —
258 to 523 bits from a nearly constant sample count. That is the confidence
bound and the correlation discount reacting to the run, and it is the reason the
old approach could not have been rescued by measuring more carefully once: there
is no single number to write down, even for one handset.

### What it costs against the handshake it precedes

A full production sign-in, first launch, no stored key, through the MTProxy to
dc2:

```
20.395 I connected to dc2 <mtproxy>:8443
20.948 I auth-key entropy barrier: 3 gathers, 315/256 bits from 360 samples in 409 ms
20.967 I -> req_pq_multi
31.495 I pq 2986605143785885507 = 1642857299 * 1817933393 in 10288 ms
54.323 I two 2048-bit modPow in 21837 ms
55.492 I handshake complete in 34544 ms, auth_key dc2 prod id=... sha1=a0e8a3d9
55.532 I persisted auth_key dc2 prod id=... sha1=a0e8a3d9
56.629 I task connect ok in 41341 ms
```

**409 ms of seeding against a 34 544 ms exchange: 1.2%.** The earlier estimate
for this device, derived from two modPows, was ~1.3% of a ~22 s floor; the real
handshake is half as long again, and the barrier is proportionally cheaper. The
prediction it replaced — five gathers, unchanged everywhere — would have cost
about 650 ms here for less than the client now measures it needs.

Note what is *not* in that log: the pq factorisation took 10.3 s and the two
modular exponentiations 21.8 s. The seeding is the cheapest part of generating a
key on this handset by a factor of fifty.

## Crypto: the fastest hash and the slowest big integer, on one device

All 22 FIPS 180-4 / FIPS-197 / OpenSSL vectors pass in 500 ms, after this
toolchain compiled, preverified and shrank the code.

```
SHA-256, 64 KB             =     68 ms   (941 KB/s)
AES-IGE encrypt, 16 KB     =    271 ms   (59 KB/s)
AES-IGE decrypt, 16 KB     =    300 ms   (53 KB/s)
modPow  256-bit            =     24 ms
modPow  512-bit            =    192 ms
modPow 1024-bit            =   1 413 ms
modPow 2048-bit            =  10 934 ms
PBKDF2-HMAC-SHA512 ×100000 = 263 235 ms  (4 min 23 s)
```

| | Alcatel OT-810D | Samsung GT-C3592 | Nokia C3-00 |
|---|---|---|---|
| SHA-256 64 KB | 166 ms | 156 ms | **68 ms** |
| AES-IGE enc 16 KB | 535 ms | 233 ms | 271 ms |
| AES-IGE dec 16 KB | 323 ms | 156 ms | 300 ms |
| modPow 2048-bit | 5 944 ms | 9 458 ms | **10 934 ms** |
| PBKDF2 ×100000 | 189 875 ms | — | **263 235 ms** |

SHA-256 is **2.4× faster** than on either earlier handset, and the 2048-bit
modPow is the **slowest of the three**. That is a signature, not a
contradiction: `Sha256` works in `int`, while `Sha512` is written entirely in
`long` and the big-integer Montgomery inner loop is built on 32×32 → 64 products
held in `long`. This runtime is quick at 32-bit integer work and expensive at
64-bit `long` work, and every figure above follows from that. Worth knowing
before optimising anything here — a `long` is not a free abstraction on this
device.

modPow scales by a factor of 7.4–8.0 per doubling, which is the O(n³) expected
of schoolbook multiplication with square-and-multiply. Nothing anomalous in the
implementation.

**The 2FA path costs four and a half minutes.** Telegram's SRP derives its
password hash with PBKDF2-HMAC-SHA512 at 100 000 iterations, so an account with
a cloud password blocks the handset for 263 seconds at sign-in. The OT-810D's
3 min 10 s was already poor; this is worse, and it is the largest single
uninterruptible wait anywhere in the client.

A full DH handshake was never run on this device — the client resumed a stored
key throughout — so unlike the other two there is no measured figure. Two
2048-bit modPows put a floor of ~22 s on it before any protocol or network cost.

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

That last sentence has a consequence the client had to be changed for. A
`Canvas` whose selection can only be taken with `Canvas.FIRE` cannot be used on
this handset - the key never arrives, so the screen opens and does nothing.
Every selectable Canvas here therefore carries soft-key commands as well as key
handling; the reaction palette was the one that did not, and it presented as a
palette a reader could open and not use. It now has Select, Up and Down.

Anything new that reads the navigation cluster needs the same treatment. The
d-pad is a convenience on the other two handsets and absent on this one.

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

## 1.0 RC upgrade observations (2026-08-09)

Series 40 names the on-card RMS files from the installed JAR basename, not just
the MIDlet Name/Vendor pair. Installing `TelegramJ2ME-1.0.0-rc1.jar` therefore
created a fresh RMS namespace beside the existing `c3-00-proxy` stores. Building
the same signed identity as `c3-00-proxy.jar` restored the saved authorization
and caches without copying or rewriting any RMS file. The legacy basename is
therefore part of the upgrade contract for this handset.

That first upgrade run also exposed a CLDC audit hole: `Character.isWhitespace`
compiled on the desktop but is absent from this VM, and opening the chat list
failed with `NoSuchMethodError`. The call was replaced with a local CLDC-safe
predicate and the audit parser was fixed so inline `#` comments can no longer
silently empty its forbidden-member table.

A later device pass found two application races. Viewport-triggered
`messages.getHistory/older` occupied the foreground worker, so choosing a
reaction—or opening another chat while the page was still in flight—was refused
with `Finishing messages.getHistory/older first`. Automatic older/newer paging
now uses the maintenance worker; a pressed `Older` command remains foreground.
An edit could also be accepted without changing the sender's visible row when
the unsolicited update won the race with the edit RPC result and made the
second copy look like duplicate `pts`. Cursor deduplication remains intact, but
the matching authoritative edit in the local RPC result is now republished to
the transcript. Both fixes have deterministic coverage and await a repeat of
this handset pass.

The repeat exposed the same ownership error one step earlier: cached history
made the chat immediately interactive while its initial `messages.getHistory`
refresh still occupied the foreground worker. A reaction selected in that
window was refused with `Finishing messages.getHistory first`. Initial/cached
history refresh now shares the maintenance lane too; if that lane is occupied,
one chat-scoped retry waits without presenting a foreground error.

The next repeat reached the asynchronous reaction RPC but first failed while
loading the allowed-reaction policy. The crash RMS preserved the primary error:
the proxy sent a well-formed FakeTLS application-data record whose wire length
was exactly 16,640 bytes. The receiver incorrectly applied TLS's 16,384-byte
plaintext ceiling to `TLSCiphertext`; TLS 1.3 permits another 256 bytes of
ciphertext expansion. Rejecting that boundary closed the session, so the
following `messages.sendReaction` surfaced only the secondary `not connected`.
The carrier now keeps the 16 KiB outbound plaintext split but accepts incoming
records through 16,640 bytes and deterministically rejects 16,641.

That transport fix exposed a separate reaction-flow defect rather than curing
it. Opening the palette still waited for the global catalog and chat policy,
and a message with existing reactions focused `View reactions` instead of the
first emoji. Worse, the detail request's intended `Loading...` screen was never
pushed, so it ran invisibly on the foreground worker and the next Select showed
`Finishing messages.getMessageReactionsList first`. The picker now opens from
the bounded local catalog, initially focuses an emoji, and the explicit detail
view shows a Back-able loading screen while its maintenance-lane request waits
or runs.

`No report sink in this build` in Diagnostics is expected for the production
candidate. It means the private development report-upload endpoint was not
embedded; it does not mean the MIDlet is using Telegram's test DC.

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

- **A wrong clock kills the MTProxy route with an unactionable error.** The
  FakeTLS hello embeds the client's unix time, the proxy refuses it, and what
  reaches the user is `illegal_parameter(47)`. The client knows its own build
  date and could say "this phone's clock is fifteen years slow" instead. See
  above; this is the single most expensive thing to diagnose on this handset.
- **A restored session never establishes a time offset**, which matters most on
  exactly this device: it boots in 2011 every time. The resume path skips the
  handshake where the offset is normally learned, and the single corrective
  retry is usually spent on the server salt.
- **The 52 pixels are still being given away.** Calling `setFullScreenMode` is a
  client-wide UI change and needs checking on the OT-810D first — a handset that
  draws its own soft-key labels in that band would lose them.
- ~~**The full handshake and the seeding barrier have never run here.**~~ Both
  ran on 2026-08-06: a production sign-in generated a key in 34 544 ms behind a
  409 ms barrier, and the probe's own barrier item ran three times. See above.
  What is still unrecorded is the *resume* path on this device — a relaunch that
  loads the stored key and runs no barrier at all.
- **`long` arithmetic is disproportionately expensive here**, and both the
  big-integer layer and SHA-512 are built on it. Whether a 16-bit-limb big
  integer would beat the current 32-bit one on this device is an open question,
  and one that only matters if this class of handset is a target.
- **Largest installable JAR is unmeasured**, as on both other devices; use
  `tools/build-size-ladder.ps1`.
- **The true RMS record ceiling is above 64 KiB but unmeasured** — the probe's
  search stops at its own ceiling, so the figure is a floor on all three devices.
- **Background socket behaviour is untestable here**: this handset offers no way
  to background a running MIDlet, so the user cannot trigger `pauseApp` at all.
  An incoming call presumably still can; that has not been tried.
- The key-timing figure has no confidence bound — 49 intervals, below the
  256-sample floor — and was taken at a deliberate pace by one person.
