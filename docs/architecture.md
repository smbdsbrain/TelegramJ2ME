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
| `probe` | `tg.app.ProbeMidlet` | ~110 KB | platform, heap, RMS, entropy, keys, sockets, images, pause/resume |
| `crypto` | `tg.app.CryptoMidlet` | ~118 KB | crypto vectors, modPow and PBKDF2 benchmarks, entropy |
| `tg` | `tg.app.TgMidlet` | ~428 KB, ~312 KB with `-Release` | messenger with connection settings/diagnostics |

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

Every subsystem is written as if the heap were small. It is measured now — about
5 MB on both handsets tested — and `tg.mem.MemoryBudget` is the one place a size
literal is allowed to live. Everything else asks it.

The client measures its own heap once, on first launch, with `HeapProbe` on a
background thread while nothing else is running; Connect is gated on that
finishing, because the probe deliberately allocates until the VM refuses and
whichever thread asks for memory at the wrong moment is the one that receives the
`OutOfMemoryError`. The result is rounded to a 64 KB grain and kept in `tgkeys`,
so later launches read a number instead of repeating the measurement. An attempt
counter is written before the probe and cleared after, so a handset where the
probe does not come back stops being asked after two tries.

Budgets are the reference profile — the values validated on those two handsets —
scaled down by `ceiling / 4 MiB`, clamped to a floor, and never scaled up. At or
above 4 MB the client behaves exactly as it did before any of this existed, which
is the only configuration hardware has ever confirmed; a handset that
over-reports its heap cannot talk the client into a buffer the VM will not give
it. Budgets that bound a single allocation are additionally capped at half the
largest contiguous block the probe obtained, because total free heap and largest
block are different numbers and fragmentation is how an honest ceiling still
refuses a big buffer.

Three kinds of number, treated differently on purpose:

* **Rejection thresholds** on network-controlled lengths — the packet caps, the
  inflate ceiling, the photo byte cap. Lowering one frees nothing: `Abridged`
  allocates the *declared* length and the cap only decides whether to throw
  first. Their floors come from what the protocol actually sends, because a limit
  under the largest legitimate packet is a disconnect, not a saving.
* **Retention budgets** — caches and list caps. These genuinely return memory,
  and scale proportionally.
* **Window budgets** — the slice of a conversation that is laid out. Different
  from a retention cap in that it bounds *work already done* rather than what is
  kept: see below.
* **Transient allocation caps** — the photo pixel budget. The budget sets a
  ceiling; whether a particular decode fits *right now* is a separate question,
  answered by `MemoryPressure`.

`MemoryPressure` is what acts before the wall rather than at it. `freeMemory()`
alone is the wrong number on CLDC — the heap grows on demand, so what is free
understates what is available — and the measured ceiling is what turns
`totalMemory()` and `freeMemory()` into `headroom = ceiling - used`. Every
allocation large enough to matter asks for room first: opening a chat, the
request that produces the largest response the client inflates, and each of the
three image decodes — the full photo, a dialog-list avatar, an inline thumbnail.
If there is not enough it sheds, in order of what each is measured to be worth:
the cached full-screen photo (~300 KB), the conversation's decoded thumbnails
(~190 KB), the avatar cache (~150 KB), the emoji sheet (49 KB). That is about
690 KB, roughly 13% of a 5 MB heap — enough to get a chat open, not enough to
rescue a 2 MB decode, which is why a photo that cannot fit is refused with both
numbers instead of attempted.

The image decodes ask with a bound on how far the ladder may run, and stop at the
first level. Two reasons. The levels below it are the avatar cache and the open
conversation's thumbnails, so an unbounded shed there clears the cache the caller
is filling and the client does the same work twice. And a decoration should not
be able to evict something the user asked for: dropping a cached photo nobody is
looking at is a fair trade for an avatar, dropping the emoji sheet the next paint
needs is not. What a bounded call still gets is the part that matters — a
`fits()` and a collect, without which a dozen small decodes in a row are refused
because of each other's garbage rather than because of the heap.

An avatar is priced from its own JPEG frame header (`JpegDecoder.dimensions`) and
a thumbnail from the size the stripped payload states (`StrippedJpeg.decodeCost`),
so the number checked is the decode that is actually about to happen. Before the
download there is no size to work from at all — the server chooses it — so the
dialog list asks a cheaper question first, `MemoryBudget.avatarDecodeCost()`,
which is a measurement of what Telegram actually serves rather than a share of
the heap.

This is the only code in the client that calls `System.gc()`, and only after
headroom has already said the work will not fit. Never on the `MtClient` reader
thread, where a collect delays every pending RPC; the protection there is the
smaller budget, not a shed. Nor on the lcdui thread for anything speculative: the
dialog list's pre-flight is the side-effect-free `fits()`, and the collect happens
on the worker that is about to allocate.

### Scrolling a conversation

A chat is a virtualised list. `ChatScreen` holds every retained `Message` — a
reply has to be able to quote one that is nowhere near the viewport — but wraps
only those within `MemoryBudget.layoutWindowScreens()` screens either side of
it. Wrapping is the expensive half: five parallel arrays keyed by display line
plus a String per line, and before the window that cost was proportional to how
far back the reader had ever scrolled rather than to the screen.

Scroll position drives the network. `ChatScreen.ViewportListener` reports every
movement, and when fewer than `historyPrefetchMargin()` retained messages remain
above the viewport the client asks for another page. The margin is wide because
a `messages.getHistory` round trip on GPRS is measured in seconds and the reader
should not watch it happen. The same trigger works forwards: reading far enough
back slides the newest messages out of the retained window, so coming down again
fetches them a second time rather than stranding the reader in the past.

Three thresholds, deliberately spread apart so that scrolling across a boundary
cannot become a request per keypress: the window extends three screens either
side, it is rebuilt at one screen from an edge, and the retained set is trimmed
around whatever is being read rather than from the end. The `Older` command
survives as a manual nudge for a slow link; there is no longer a limit to reach.

Measured by driving the packaged client against a real account through a
picture-heavy channel: eighty screens of scrolling in both directions, with the
laid-out line count flat at its opening value, one history request per few
screens rather than per keypress, none at all on the way back down, and no
memory shed at any point.

### Scrolling the chat list

The same shape one screen up, with one difference that changes the mechanism.

`DialogListScreen` precomputes nothing — `paint` draws `visibleRows()` of an
array — so there is no layout to virtualise. What needed bounding was the array
itself. A row weighs a measured 431 bytes, so the list a reader has scrolled
past is the cost, and an account of 1690 chats is more of it than any share of a
4 MiB heap can hold. `TgMidlet` therefore keeps a *window* of
`MemoryBudget.maxDialogs()` rows around the reader and drops what they have gone
past. Memory stops depending on how far anybody scrolled: 1690 chats cost the
same as 60.

The window is three pages, sized by the slack scrolling needs rather than by how
much of an account it covers — the viewport, a prefetch margin at each end, and
a page of room so that an arriving page does not provoke the next one. Coverage
stopped being its job the moment the list could be scrolled past it. A smaller
window costs round trips on the way back up and nowhere else; going down,
requests track pages scrolled whatever it is set to.

The difference is direction. `messages.getHistory` takes one `offset_id` and
pages either way; `messages.getDialogs` takes `(offset_date, offset_id,
offset_peer)` and pages **downwards only**, so a run dropped off the top cannot
simply be asked for again. Instead, each time a run is dropped the client
records the single dialog that was sitting immediately above it — that dialog is
a valid offset, so the run comes back in one request rather than by paging from
the start of the list. The restore points are a bounded stack; past its depth
the way back to the very top is given up rather than the memory, and `Refresh`
still returns there.

Two orderings have to be given up in exchange, and both are deliberate. Below
the top of the list the periodic update snapshot may change what rows *say* but
not where they are (`PageMerge.restate`), because the newest page is not
adjacent to a window at row four hundred and splicing them would render a
contiguous list with an invisible hole in it. For the same reason a chat that
receives a message is not promoted while the window has scrolled: it has moved
to row zero, which is not somewhere the window can put it.

The fetch trigger is `PageMerge.below`/`above` against the **unfiltered** window,
because `PageMerge.filter` narrows what is displayed and the bottom of three
matches is not the bottom of anything. `More` survives as a manual nudge. The
header counts the reader's position against the server's total — `912/1690`,
from `messages.dialogsSlice` — rather than counting what is held against itself.

Measured by driving the packaged client against a real account of 1690 chats:
ninety screens down reached row 630 with the window never exceeding 120 rows and
twelve requests spent, then a hundred and ten screens back up returned to row
zero on fifteen restore requests, with the reader's row never moving under them
and nothing shed.

### What the client actually needs

Measured by driving the packaged client under a constrained heap, against a real
account, with the free heap reduced in steps. "Free" is what remained for the
MIDlet after the runtime loaded, which is the share a handset's AMS also decides
— not the figure on a spec sheet.

| free heap | with pictures | with pictures off |
|---|---|---|
| above ~2.2 MB | everything, no pressure | everything, no pressure |
| ~2.0 MB | the shed ladder starts firing | no pressure |
| ~1.7 MB | photos refused; avatars and chat fine | photos still decode |
| ~1.5 MB | avatars stop loading | chat still usable |
| ~1.1 MB | sign-in fails | sign-in fails |

Turning pictures off does not move the sign-in floor — the handshake decodes no
images, so both modes fail at the same ~1.1 MB. What it moves is everything above
that. It is worth roughly 480 KB at rest, and at ~1.7 MB free it is the
difference between a photo that opens and one the client refuses: the memory the
thumbnails were holding is the memory the photo needed. That is what those two
settings are for, and why they sit on the first Settings screen rather than
buried.

Below 2 MB of measured heap the start screen warns that signing in may not be
possible. That threshold is measured, not reasoned: at a 1536 KB ceiling the
connect task runs out of memory before the dialog list, and at 3584 KB the whole
client works. Nothing between the two was reachable on the host used for the
sweep, so the warning sits nearer the proven failure. It stays a warning and
never a refusal — a handset that under-reports would otherwise be locked out of
an app that might have run on it.

Record-store limits (`RmsAvatarCache`, `RmsConversationCache`, the outbox) are
deliberately *not* derived from any of this. They bound persistent storage, which
is measured separately and is a different resource.

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
* **The seeding is measured, and one gather is not enough — so the auth-key path
  no longer takes one.** A Java ME runtime has no hardware RNG, so
  `tg.crypto.Entropy` scrapes what it can. On the one handset measured — an
  Alcatel OT-810D, 2026-07-31, full figures in
  [docs/hardware/alcatel-ot810d.md](hardware/alcatel-ot810d.md) — that is about
  **58 bits per `gather()`**: jitter only, at a 99% bound, after a
  serial-correlation discount, with identity hashes and heap readings counted at
  zero. That is the right cost for a nonce, a padding block or a `random_id`, and
  short of a 2048-bit DH secret. `tg.crypto.AuthKeySeeding` is the barrier every
  permanent key now crosses: `tg.mt.Handshake` folds in five further separated
  gathers, under a domain-separating context naming the dc, the environment and
  the media role, before the first nonce is drawn. Resuming a stored key runs
  none of it.
  **This is not a claim of 5 × 58 bits.** Consecutive gathers sample the same
  scheduler on the same idle handset and nothing here demonstrates that they are
  independent; five is a sizing rule against a 256-bit target, not an addition.
  On any runtime other than the one measured the sources are still unquantified,
  so generated keys there remain development keys. See
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
  the seeding on it. Nothing currently feeds key timing into the application
  `Rng` — `Entropy.fromUserInput` is used only by the probe screen — so "where it
  is free" is a direction, not a shipped behaviour.
* Server-provided randomness must never be the sole source of DH secret entropy.
* DH parameter validation is mandatory before an `auth_key` is accepted; it is
  not a step to skip for a green demo.
