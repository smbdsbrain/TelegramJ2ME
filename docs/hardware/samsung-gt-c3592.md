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
- Entropy has not been measured here. `Entropy.estimatedBitsPerGather` is still
  pinned to the OT-810D figure and needs a full power-cycle series on this
  device before anything is claimed for it.
- Key codes and key-press timing have not been recorded.
- The true RMS record ceiling is above 64 KiB but unmeasured.
