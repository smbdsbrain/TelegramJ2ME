# Architecture

## The one structural idea

Everything above `tg.mt.MtLink` is packet-oriented and written in the CLDC 1.1
subset. TCP-like routes retain `tg.io.Transport` as their byte-stream seam;
HTTP implements `MtLink` directly:

```
                       tg.app / tg.ui          MIDP only, device only
                            |
   tg.api  ->  MtClient -> MtLink -> framing/session/crypto/TL
                              |                    |
                        HttpLink              Transport
                                          /            \
        tg.plat.MidpTransport    tgtest.SeTransport
        javax.microedition.io    java.net.Socket
              (device)               (desktop)
```

Consequences:

* The MTProto handshake can be developed and debugged against a real Telegram
  data centre from a desktop JVM, with a real debugger and real stack traces,
  before a handset exists. The bytes on the wire are produced by the same code
  that ships.
* `tg.crypto`, `tg.tl` and `tg.mt` have no MIDP dependency, so they are ordinary
  unit-testable Java.
* Direct, obfuscated2, MTProxy/FakeTLS and HTTP share the same MTProto session
  and API stack.

## Packages

| Package | Contents | MIDP? |
|---|---|---|
| `tg.app` | MIDlet lifecycle, composition root | yes |
| `tg.ui` | `lcdui` screens | yes |
| `tg.plat` | MIDP adapters: sockets, RMS, capability probing | yes |
| `tg.diag` | log ring, crash persistence | RMS only |
| `tg.io` | `Transport` contract, hex, byte helpers | no |
| `tg.crypto` | SHA-1, SHA-256, AES, AES-IGE, RNG, entropy | no |
| `tg.crypto.bigint` | vendored Bouncy Castle `BigInteger` | no |
| `tg.tl` | TL serialization and constructor dispatch | no |
| `tg.mt` | MTProto transport, session, auth key | no |
| `tg.api` | Telegram API layer | no |

The root package is `tg` rather than something descriptive because every class
name lands in the constant pool of every class that references it, and many
Java ME runtimes impose strict JAR-size limits.

## Build targets

Three MIDlets, one source tree, ProGuard keeps deciding what each JAR contains.

| Target | Entry point | Size | Purpose |
|---|---|---|---|
| `probe` | `tg.app.ProbeMidlet` | ~91 KB | platform, heap, RMS, entropy, keys, sockets, images, pause/resume |
| `crypto` | `tg.app.CryptoMidlet` | ~103 KB | crypto vectors, modPow and PBKDF2 benchmarks, entropy |
| `tg` | `tg.app.TgMidlet` | ~399 KB, ~291 KB with `-Release` | messenger with connection settings/diagnostics |

`config/proguard-common.pro` deliberately has **no** blanket
`-keep class * extends MIDlet`: that would keep every entry point in every
target and drag the crypto stack into `probe.jar`, which exists precisely
because the first install on an unknown 2011 handset should be tiny.

## Verification chain for crypto

`tg.crypto.SelfTest` holds the vectors in CLDC-only code so the *same* checks
can run at several layers, each answering a different question:

| Where | Answers |
|---|---|
| the desktop harness (`tools/test.sh` / `.ps1`) | is the algorithm right |
| the shipped `dist/crypto.jar` on a desktop JVM | did ProGuard's shrink and preverify change behaviour |
| MicroEmulator | does it survive a MIDP runtime |
| a physical device | does it survive a vendor VM and AMS - run once, on one handset, and it did |

A failure at a later stage that passed an earlier one localises the bug to the
toolchain rather than the mathematics - which is worth a great deal when the
alternative is debugging a failed `auth_key` handshake over GPRS with no
debugger.

Desktop tests additionally diff against oracles the device cannot have:
`java.math.BigInteger`, `java.security.MessageDigest` and `javax.crypto`. AES-IGE
has no oracle - no mainstream library implements it - so `AesIgeTest` contains a
second, deliberately naive implementation written straight from the definition
and compares against that.

## Memory discipline

The heap is unmeasured, so every subsystem is written as if it were small.

* `Diag` holds a fixed 100-line ring; lines are truncated, hex dumps capped.
* `Sha1`/`Sha256` allocate their block and schedule buffers once per instance;
  `update()` and `digest()` allocate nothing.
* `Aes` computes its tables at class init rather than embedding ~2 KB of
  literals, and uses the byte-oriented round functions instead of the 8 KB
  T-table variant.
* `AesIge` reuses four 16-byte buffers and supports `out == in`, because
  MTProto decrypts in place.
* `Rng` dispenses from a single 32-byte block and zeroes bytes as it hands them
  out.
* `TcpLogSink` drops lines rather than blocking the subsystem being diagnosed.

Anything that consumes a network-controlled length must validate it **before**
allocating. That rule has no exceptions and applies to every TL vector, string
and byte array once `tg.tl` exists.

## Threading

CLDC has no `java.util.concurrent`. The rules are:

* `lcdui` callbacks (`commandAction`, `paint`) must not block. Anything that can
  take more than a few milliseconds - a connect, the heap probe, the modPow
  benchmark - runs on its own `Thread` and writes results back into the screen.
* `Diag` is fully synchronized; the network thread and the UI thread both log.
* `MtClient` has one writer/maintenance loop and one reader. The writer is the
  sole owner of message encryption, `seq_no` allocation and outbound framing;
  the reader decrypts and routes `rpc_result` through a bounded waiter table.
* `Session` has independent TX/RX AES/SHA workspaces. Salt and `MsgIdGen` state
  are synchronized; individual crypto objects remain thread-confined.
* HTTP is still request/response: its link queues POST responses for the reader,
  while the writer emits short `http_wait` polls when otherwise idle.
* `Telegram` owns foreground lifecycle and reconnect. Unexpected failures fail
  current RPCs without replay, then retry the route chain with bounded backoff.
  Only persisted outbox messages are replayed, always with their original
  `random_id`.
* `UpdateSync` has its own serial worker. The `MtClient` reader only enqueues
  unsolicited bodies; parsing, difference RPCs and state persistence must never
  run on that reader because it delivers their `rpc_result`.

## Durable user state

Authorization/config remains in `tgkeys`. Reliability state uses two separate
stores so one corrupt or full queue cannot damage the auth key:

* `tgoutbox`: one versioned, bounded UTF-8 binary record per queued/failed
  message. A successful RPC deletes it; ambiguous transport failures retain it.
* `tgdrafts`: one UTF-8 record per peer, updated every three seconds while the
  compose screen is visible and on lifecycle/navigation boundaries.
* `tgupdates`: one versioned account-bound record containing
  `pts/qts/date/seq` and a bounded table of 128 per-channel `pts` cursors.

Outbox is capped at 64 messages and the compose UI at 1000 characters. Permanent
RPC errors remain visible until the user retries or deletes them.

## Security posture, stated honestly

* The crypto primitives match published vectors, including through the shipped
  JAR.
* The `Rng` construction is a standard hash DRBG and is sound.
* **The seeding is measured, and one gather is not enough.** A Java ME runtime
  has no hardware RNG, so `tg.crypto.Entropy` scrapes what it can. On the one
  handset measured — an Alcatel OT-810D, 2026-07-31, full figures in
  [docs/hardware/alcatel-ot810d.md](hardware/alcatel-ot810d.md) — that is about
  **58 bits per `gather()`**: jitter only, at a 99% bound, after a
  serial-correlation discount, with identity hashes and heap readings counted at
  zero. A 2048-bit DH secret needs roughly five times that, and **seeding from
  several gathers is still unimplemented** - `Rng()` calls `gather()` once.
  Until it exists, and on any runtime other
  than the one measured, generated keys are development keys. See
  [MTProto security guidelines](https://core.telegram.org/mtproto/security_guidelines).
* **The wall clock contributes nothing across cold boots on that handset.** It
  loses the RTC when the battery is removed. Seven launches produced no repeated
  seed anyway — two of them started at the same millisecond and still diverged —
  so jitter and the allocator's identity hashes carry the pool. This is a
  separate finding from the bit count and does not fold into it.
* **Key-press timing is a supplement, not the mechanism.** `Entropy`'s notes
  planned the auth_key path around collecting keyboard interaction. Measured on
  the same handset that is 3 bits per press - 86 presses for 256 bits, against
  600 ms of busy-looping for the same from jitter. Human motor noise remains the
  easiest source to defend, so fold it in where it is free; just do not build
  the seeding on it.
* Server-provided randomness must never be the sole source of DH secret entropy.
* DH parameter validation is mandatory before an `auth_key` is accepted; it is
  not a step to skip for a green demo.
