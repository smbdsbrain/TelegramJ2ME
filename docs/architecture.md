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

Two MIDlets, one source tree, ProGuard keeps deciding what each JAR contains.

| Target | Entry point | Size | Purpose |
|---|---|---|---|
| `probe` | `tg.app.ProbeMidlet` | ~172 KB | platform, heap, RMS, entropy, seeding barrier, crypto vectors and benchmarks, keys, sockets, images, pause/resume |
| `tg` | `tg.app.TgMidlet` | ~464 KB, ~339 KB with `-Release` | messenger with connection settings/diagnostics |

There used to be a third, `crypto`, holding the vectors and the modPow and
PBKDF2 benchmarks, kept separate so the first install on an unknown handset
could stay small. It is folded into `probe`: a device session meant installing
two suites and uploading two sets of reports, and the figures that have to be
read together — what a gather is worth here, how many gathers the seeding
barrier then takes, what the modPow it precedes costs — were split across both.
The probe is correspondingly larger and no longer the smallest artifact this
project can produce; `tools/build-size-ladder.ps1` can no longer pad it down to
its 64 KiB and 128 KiB rungs.

`config/proguard-common.pro` deliberately has **no** blanket
`-keep class * extends MIDlet`: that would keep every entry point in every
target and drag the client into `probe.jar`.

## Verification chain for crypto

`tg.crypto.SelfTest` holds the vectors in CLDC-only code so the *same* checks
can run at several layers, each answering a different question:

| Where | Answers |
|---|---|
| the desktop harness (`tools/test.sh` / `.ps1`) | is the algorithm right |
| the shipped `dist/probe.jar` on a desktop JVM | did ProGuard's shrink and preverify change behaviour |
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
  benchmark - runs on its own `Thread` and posts its result back to the display
  thread through `UiDispatcher`; see *One thread owns the screen* below.
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

**A refused submission is an outcome, and every caller handles it.** `Worker`
runs one operation at a time and answers `false` rather than queueing, because
Telegram operations share one connection and one session — two of them at once
would interleave their `msg_id`s and `seq_no`s. That answer used to be discarded
by eighteen of the twenty-nine call sites, and the shape of the loss was always
the same: `showBusy` sets the display without touching the navigation stack, so a
busy screen whose callback never comes has no way out at all; a status line stays
at `reacting...`; `checkPassword` had already cleared the password box for a
submission it never made, and `refreshPhotoReferenceAndOpen` had already cleared
the latch that decides what Retry does.

A refusal is deliberately **not** routed through the failure callback: callers
use that to tell the user the server refused them, which is a different and
worse statement. Nor is a queue introduced — an unbounded one is not available on
this heap, and a bounded one would still have to decide what to drop. Instead
every call site reads the answer and undoes exactly what it did: pagination and
background paths clear their in-flight or scheduled flag so the next viewport
event retries, the avatar worker releases the cache slot it claimed, and a
foreground action restores its screen and says what did not happen.
`Worker.busyWith` names the task holding it — never null, because it can finish
between the refusal and the message — and `TgMidlet.showRefused` supplies only
the wording and the landing, never the undo. `SourceGuardTest` fails the build if
a new call site ignores the answer.

### One thread owns the screen

The rule, in full:

> Application model, navigation stack and `lcdui` mutations happen on the display
> thread, and so do ordinary `Worker.submit` calls. Blocking I/O does not. A
> producer on any other thread posts through `UiDispatcher` first.

`lcdui` is documented as thread safe for mutating a `Displayable`, and that is
true and beside the point. Safety per method call says nothing about a
*transition*: "replace the dialog array, rebuild the list, then swap the screen"
is three calls, and a second transition landing between any two of them leaves
the client showing one account's dialogs under another account's title. What
removes that is a single owning thread, not a lock per widget.

`UiDispatcher` is one method, `post`. `DisplayDispatcher` implements it with
`Display.callSerially` and is the only file in `src/` allowed to name that call —
`SourceGuardTest` enforces it. The desktop suite substitutes `TestDispatcher`,
a queue it drains by hand, which is the only way to assert an ordering that on a
device is decided by the AMS.

`Worker` delivers both callbacks through the dispatcher, so a callback may touch
navigation and `lcdui` directly. It stays busy while the result is queued and
releases itself on the display thread one statement before the callback body.
That ordering is the fix for a real gap: the old code released on the worker
thread *before* the transition, so between the release and the transition being
applied a keypress could be admitted and act on state the previous result had
not written yet. A callback can still chain the next task — connect submits
`getDialogs`, sign-in does the same, a chat open submits its history fetch from
inside the callback before it — but nothing else can get in, because everything
else would have to arrive on the thread that is currently inside the callback.

If dispatching itself throws, the worker is released and the result is lost.
That is the deliberate trade: a page the user can ask for again, rather than a
worker that refuses every action for the rest of the session.

### Which of them may overlap

"One operation at a time" is true of a `Worker`, not of the client. Four threads
can be doing Telegram work at once, and that is by design:

| Owner | Thread | May overlap with | Why |
|---|---|---|---|
| `TgMidlet.worker` | one per task | everything below | The foreground operation. Serial with itself, which is what the refusal contract is about. |
| `TgMidlet.avatarWorker` | one per task | `worker` | A second `Worker` on purpose: a decorative avatar must never refuse a chat open, and the two multiplex over the same connection. It is also the reason an avatar callback re-reads the account id — a logout can land during its download. |
| `ReadQueue` drain | one, long-lived | `worker` | Read acknowledgements are fire-and-forget and must not consume the foreground worker; a queued one waits its turn rather than being overwritten. |
| `UpdateSync` | one serial worker | `worker` | The `MtClient` reader only enqueues unsolicited bodies. Parsing, difference RPCs and state persistence cannot run on that reader, because it is what delivers their `rpc_result`. |
| `TgMidlet.syncWorker` | one per task | `worker` | The snapshot refresh that reconciles the retained window after an update burst. Nobody asked for it, so it must never take the worker out from under a keypress - sharing one meant every chat open, search and jump during a sync was refused with "Finishing updates.snapshotRefresh first". Same connection, no second socket. |
| Thumbnail decoder | one at a time, latched | `worker` | Decoding is CPU, not network. Guarded by a generation counter so results for a chat the reader has left are dropped. |
| Heap probe, draft autosave, snapshot-refresh wait | one each | — | None of them talks to Telegram. The snapshot wait polls `worker.isBusy()` and then posts its submission to the display thread rather than submitting from where it waited. |

A further worker is not admissible without adding a row here first. The refusal
contract above only means something against a written-down list of what is
allowed to be in flight beside it.

### A result can be correct and still not belong here

Owning the thread decides *when* a result is applied, not *whether* it should
be. A `messages.getDialogs` on GPRS takes seconds, and in those seconds the user
can log out, sign in as someone else, or open two other chats. The result then
arrives correct and complete, about a world that is gone.

`AsyncScope` is two counters and a captured peer. A submission takes a token;
its callback asks the token whether the capture still holds before it touches
anything.

| Counter | Bumped by | Asked by |
|---|---|---|
| session | `finishLoggedOut`, `changeNumber`, and the first `loadDialogs` after a logout — i.e. whenever a *different* authorization becomes the live one | everything; it is the outer guard |
| chat | binding a *different* conversation as the open one, through the single `bindOpenPeer` assignment point | anything that writes into the open transcript |

Two decisions inside that are load-bearing. Closing a chat does not bump: a
token holds a peer and a peer never matches a null current one, so closing is
already covered. And rebinding to the *same* chat does not bump either — every
pop back onto a conversation rebinds it, and the dialog list closes the chat on
the way past, so a reader who backs out and comes straight in would otherwise
lose the page already on its way to them. A detour *through* another chat does
bump, and that page is dropped: it was requested against paging offsets the
second visit does not share.

**Durable and screen halves are decided separately.** A stale result still
finishes and its durable half still counts. An outbox row that reached RMS stays
there whether or not the composer it came from is still open — `enqueueMessage`
committed it and started the drain before the callback existed. A delete already
happened on the server; what the guard stops is `removeOpenMessage` running
against the wrong transcript, because message ids are unique per peer and not
globally. The draft of a sent message is cleared against the *captured* peer, so
it happens even if the reader moved on — otherwise that draft comes back as a
second copy.

**Latches are session-scoped, content is chat-scoped.** `historyPageInFlight`
and `dialogPageInFlight` are released by any callback from the current session,
including one whose chat has changed, because nothing else would release them
and paging would stick. Only a result from a previous account leaves them alone,
and `finishLoggedOut` clears both.

Where a request already had a better guard than a generation, it kept it:
`photoToken` is replaced per open and cancelled on logout, `composer` is a
session object compared by reference, `thumbnailGeneration` is per batch rather
than per chat. `avatarGeneration` was deleted — it moved on exactly one event, a
logout, which is what the session counter is.

Counters are signed ints compared for equality, not persisted. A bump costs a
logout or opening a different chat; 2^31 of those is 68 years at one a second.

### Coming back later costs one thread, not one per caller

`new Thread(sleep(delay); doTheThing()).start()` works exactly once. A
`FLOOD_WAIT` is per message, so a queue of them asked for it once per message —
a sleeping thread with a stack each, on a handset whose whole Java heap measured
2 MB, all waking to run the same drain against the same store.

`DelayedWake` is one waiter and one pending deadline. The **earliest** deadline
wins: a later one is dropped rather than postponing a retry legitimately due
sooner, and an earlier one wakes the waiter to re-read it. `cancel` drops the
deadline and the waiter exits — but a wake already past its deadline check
cannot be cancelled, so callbacks have to be safe to run once late. Both are:
the outbox drain re-checks the account epoch and the store, and the snapshot
refresh re-checks the worker.

Two users. `Telegram.scheduleOutboxDrain` cancels on logout, on store
replacement, on the wipe and on `pause()`. `TgMidlet.scheduleSnapshotRefresh`
no longer spins on `worker.isBusy()` at all: it tries the submission, and a
refusal — an ordinary outcome, because the user can act at any moment —
schedules one wake to try again, backing off 250 ms → 4 s.

CLDC has no monotonic clock, and one of the three handsets resets its clock to
2011 on every power cycle. A backward jump would turn a two-second wait into a
fourteen-year one, so a single `wait` is capped at 30 s and the deadline re-read
afterwards.

### Proving a store survives a power cut

A store that survives one has to be written in an order where every
interruption leaves the old value or the new one, never half of either. Nothing
about that is visible from a passing test: a correct implementation and a broken
one have the same happy path and only diverge on a phone that lost battery
between two RMS calls.

`tgtest.FaultyRecords` manufactures that. It is a complete in-memory
`RecordStore` model behind MicroEmulator's `RecordStoreManager` — the seam
`EmulatorRecords` already used, so none of it reaches the JAR and `src/` gains
no indirection. It can:

* fail the *n*-th call of any primitive on any store, or every call;
* fail **after** the change as well as before — "the write landed and the call
  reported failure" is a real outcome and the one implementations mishandle;
* `restart()`, which drops every open handle and keeps the records, because a
  handset that lost power did not call `closeRecordStore`;
* vary enumeration order (ascending / descending / insertion), since the
  specification promises none and code that resolves duplicates by "whichever
  came out first" is wrong on some handset;
* corrupt: truncate, flip a bit, duplicate a record, garble it.

The assertion it exists to support: **after a restart a critical store exposes
old-valid or new-valid state, never a false success and never half of either.**
`RmsAuthKeyStore` is the first store held to it — refused write keeps the old
key, a write that landed but reported failure is still readable, an interrupted
cleanup leaves a duplicate and the newest record wins regardless of enumeration
order, and an unreadable store still reports "unreadable" rather than "empty".

`RmsFaultTest` ends by running a deliberately unsafe delete-then-add through the
same injector and asserting that it loses both values. A fault injector that
cannot fail a wrong implementation is not evidence that the right one is right.

### The record header every durable store carries

`RecordEnvelope` — 24 bytes: magic, schema version, account id, environment
flag, CRC-32 of the payload. It exists so four things a record can be are
distinguishable before anything acts on it: this build's, an older build's,
another account's or the other environment's, and damaged. Without it the last
two look like a valid record whose fields happen to decode, which is how a
truncated draft becomes a message sent to the wrong chat.

An account id of **0 means unbound, and matches** rather than excluding. A
message queued before the client knows who it is signed in as must not vanish
the moment it finds out; binding is defence in depth behind the logout wipe, not
the mechanism that separates accounts. `TgMidlet.cacheAccountId` pushes the
current id into the outbox and draft stores, so it tracks sign-in and logout
without a lifecycle of its own.

The CRC catches accidental damage — a torn write, a flipped bit, a short read.
It is not tamper detection: anyone who can edit a record can recompute it in the
same edit, and RMS on these handsets has no encryption and no access control
beyond "other MIDlet suites cannot read it".

### What the three durable stores now guarantee

**Every mutation happened or reported failure.** `add`, `save` and `remove` read
back and compare before returning; a `setRecord` that was acknowledged into a
buffer and never landed used to be indistinguishable from success. A failed
drain now fires the outbox listener, so the screen cannot keep showing "sent"
for a row the store would not release.

**Replacement is add-then-delete, everywhere.** Interrupted, that leaves two
records rather than none, and the reader keeps the higher record id — the newer
write, because RMS never reuses an id. The outbox resolves duplicates by
`random_id`, drafts by peer, the update cursor by there being only one.

**`random_id` is never regenerated.** It is Telegram's deduplication key: the
same value resent is the same message, a new one is a second copy in the
conversation. Recovery, migration and retry all re-encode the message object
rather than rebuilding it.

**`SENDING` does not survive a restart.** It means a request is on the wire, and
after a restart none is. Those rows come back `QUEUED` under the same
`random_id`, so a request Telegram did receive is deduplicated rather than
delivered twice.

**Damaged rows are removed, not skipped.** A skipped one held one of the outbox's
sixty-four slots until the installation was deleted, and a store full of records
it cannot read reports itself full.

**A damaged update cursor resets rather than reading as absent.** Believing a
wrong `pts` is worse than having none — it tells Telegram the client has seen
updates it has not, and the difference is never requested. `lastLoadWasReset()`
separates "nothing stored" from "something was, and it could not be trusted".

Legacy records (bare `TGO2`, `TGD2` and `UpdateStateCodec` output) are still
read. The outbox migrates them into the current format on first sweep, keeping
`random_id`; drafts and the cursor are rewritten by the next save. An upgrade
that silently dropped the user's unsent messages would not be acceptable for
either.

## Durable user state

Authorization/config remains in `tgkeys`. Reliability state uses two separate
stores so one corrupt or full queue cannot damage the auth key:

* `tgoutbox`: one versioned, bounded UTF-8 binary record per queued/failed
  message. A successful RPC deletes it; ambiguous transport failures retain it.
* `tgdrafts`: one UTF-8 record per peer, updated every three seconds while the
  compose screen is visible and on lifecycle/navigation boundaries. Keyed on the
  chat the composer was *opened for*, not on the chat that is open — see below.
* `tgupdates`: one versioned account-bound record containing
  `pts/qts/date/seq` and a bounded table of 128 per-channel `pts` cursors.

Outbox is capped at 64 messages and the compose UI at 1000 characters. Permanent
RPC errors remain visible until the user retries or deletes them.

**A composer belongs to one chat, for one session.** Reply used to be a bare
`Message replyTarget` field beside the reused compose `TextBox` and the mutable
`openPeer`, and nothing tied the three together. Pressing Send on an empty box —
the one exit that cleared nothing — left reply mode armed, so the next Write, in
whatever chat the user had walked to by then, came up holding the first chat's
message id and sent it there. `tg.app.ComposerState` captures the peer and the
message id together when the composer opens, and the send path reads the message
from it rather than from `openPeer`. Every exit — Back, a blank Send, an accepted
enqueue, landing on the chat list, logging out — ends at the same `closeComposer`,
so there is no path left that can forget a field.

The state is an immutable value in a `volatile` field, `null` when no composer is
open, which makes the reference itself the session token: the outbox callback
compares by identity before cleaning up, so a composer the user reopened during a
round trip is not closed — and its draft not erased — by the previous send. That
is also what keys the draft: `saveDraftNow` reads the session, not `openPeer`,
because it runs on the autosave thread while a background callback can move the
open chat under it. Only the id is kept, never the `Message`, so the reply label
stays correct and short after the message it answers has been evicted from the
retained history window. A refused `Worker.submit` leaves the text and the reply
target exactly where they were, and says so.

**A read mark belongs to one chat too, and is a maximum rather than a first
answer.** `Mark all read` used to take the retained dialog's `topMessageId` and
fall back to `openHistory[0].id`. Both of those are *windows*: the chat list
scrolls past a row and drops it, and the retained history slides off its newest
end while reading backwards — which is the whole reason a separate high-water
mark exists. So the one path a reader triggers deliberately was the one that
marked read up to wherever they had scrolled to, leaving everything after it
unread. `tg.app.ReadMark.highest` is now a numeric maximum over the mark, the
dialog row and everything retained, and nothing below 1 counts as a message.
Marking with an id above what is on screen is safe when it came from the server,
because `messages.readHistory` takes the maximum of it and the cursor it already
holds; an invented id is not.

The mark is bound to its peer for the same reason the composer is. It only ever
rises — an older page must not walk it backwards — so the value alone never says
when it stops applying, and `restoreScreen` adopts whichever `ChatScreen` is
topmost on the navigation stack, which can hold two: opening a forwarded
message's source pushes a second chat over the first. Back out of it and a bare
`int` still held the channel's mark while the chat underneath was open. The kind
and id are captured with the value, `newestKnownIdFor` answers 0 for anyone
else's, and every peer change goes through one `rebindReadMark`.

**The acknowledgement queue holds one entry per chat, not one in total.** It was
a single slot, and a producer that found it occupied by another conversation
replaced the entry. The window for that is not a scheduling accident — it is a
whole `readHistory` round trip: the drain takes the slot, empties it, and only
then goes to the network. Read a message in one chat, walk back to the list,
open another, and the first was still bold on every other device, with nothing
left to retry it. `tg.app.ReadQueue` coalesces by peer — the cursor is monotonic
and the server takes the maximum anyway — which is what keeps its bound of eight
conversations off the common path. The bound is small enough to reach, so
reaching it drops the oldest, the chat the reader has left rather than the one
on screen, and counts and logs the drop instead of assuming it away. The drain
flag lives in the queue because "empty" and "nobody is draining" have to be one
decision under one lock.

For the same reason a chat that has scrolled out of the retained dialog list is
no longer skipped wholesale by `mergeMessage`. The missing row means the reader
scrolled past it, not that they are not sitting in it; the dialog bookkeeping
has nowhere to go and is still skipped, but the transcript and the
acknowledgement do not need a row.

**Logging out erases the account, and says what it could not erase.** The
cleanup used to be split in two, and neither half had the whole list:
`Telegram.logOut` cleared the auth key of the data centre it happened to be
talking to, and `TgMidlet` cleared three caches in `try`/`catch` blocks that
logged and carried on. What stayed behind was the auth key of every *other* data
centre, the media import markers, the home-DC pointer and the stored account id
— and nothing told the user when a delete had failed. `tg.api.AccountWipe` is
now the single list of what belongs to an account, `tg.api.WipeReport` is what it
answers with, and every component is attempted even after one of them refuses.

**The markers are the part that made leftovers a leak.** Downloading a file from
another data centre imports this account's authorization into it and records
`imported.<env>.<dc>` so the import is not repeated.
`MediaAuthorization.needsImport` believes that record. A marker that outlived its
account therefore told the *next* account not to import its own, and its file
requests travelled on the previous account's session.

**`tgkeys` is swept by name, never deleted.** The keys share one record store
with the proxy, the theme, the log level and the measured heap ceiling, so
`deleteRecordStore` is not available: a logout that costs someone their proxy is
a logout they will avoid. `AuthKeyStore.clearEntries` takes exact names and name
prefixes, and answers only after looking again — RMS on an unknown handset is
exactly where a delete returns without deleting.

**Keys are swept by prefix, not walked by data centre.** The authoritative data
centre list arrives from `help.getConfig`, and a photo can name one this build
has no built-in address for; downloading it stores a key under that number. So
"delete the keys" cannot mean "walk the numbers we know". The sweep matches
`AuthKey.entryPrefix`, the same expression the store files a key under, and the
prefix names the environment — so the other environment's key, which belongs to
a different account on the same handset, is left alone.

**A session ended from another device is the same event.** The
`AUTH_KEY_INVALID` branch of `verifyAuthorization` used to clear one key and the
signed-in flag, leaving the drafts and the caches for whoever signed in next. It
runs the same erasure now.

**What survives on purpose.** Connection and proxy settings, the theme, the log
level, the measured heap ceiling, the entropy log and the crash log. The crash
log is the one uncomfortable entry: its tail of the `Diag` ring has been observed
naming a chat. It stays because it is the only record of an RMS failure that
survives the restart such a failure presents as — see the open follow-up on
redacting what reaches it.

**The socket is deliberately left open.** `invoke` refuses when there is no
client, and the sign-in that follows a logout goes straight to `auth.sendCode`
without reconnecting, so closing here would answer the user's next keypress with
"not connected". What has to stop is writing, not talking: `Telegram` carries an
account epoch that the outbox drain checks under the same lock the erasure takes,
and `TgMidlet` answers 0 from `cacheAccountId` from the moment a logout starts,
which turns every dialog-cache, history-cache and avatar-cache write into a
no-op. The avatar worker is a second `Worker`, and `Worker` clears its busy flag
before running a callback, so this concurrency is real rather than theoretical.

**Reading the auth key answers with an outcome, not a key or nothing.** A store
that will not open, a record that will not decode and an entry that was never
written are three different states, and the connect path answers the third by
running a handshake and writing the result over whatever is there — so folding
all three into one null made a transient RMS failure cost the session.
`tg.mt.AuthKeyLoad` names which it was (`FOUND`, `NOT_FOUND`, `CORRUPT`,
`IO_ERROR`), and the length and hex form are validated before the value is
decoded so the answer describes the damage. A damaged record is no longer
deleted while being read: it is the only description anyone will ever get of
what the handset did, and destroying it is not the store's decision to make.
The client still regenerates on a bad read rather than refusing to start — the
loss is one session, and a handset that cannot start is worse — but it now says
so in the log and the crash log instead of reporting a first launch.

**A stored key records how it was seeded.** Strengthening key generation does
nothing for a key that is already in RMS, and until this field existed the two
were the same 512 hex characters. `tg.mt.AuthKeyRecord` is the durable value:

```
<512 hex>          no version recorded  -> AuthKey.SEEDING_UNKNOWN_LEGACY
p<n>:<512 hex>     seeding version n    -> p1: is the measured barrier
```

The version lives *inside* the one value rather than in a settings string beside
it, because it has to fail in the same direction as the key: a half-completed
pair of writes would otherwise label a key with a path it never took, which is
worse than no label. It is written, read back and verified by the same operation
that stores the key.

`AuthKey.fromHandshake` is the only production path allowed to mark a key as
currently seeded — it is the one that crossed `tg.crypto.AuthKeySeeding` — and
`tgtest.SourceGuardTest` refuses any other caller in `src/`. The plain
constructor means *unknown*, so a key that arrives unmarked is never presented as
current.

**Provenance names the path, not a strength.** A key whose barrier hit a cap
before reaching its 256-bit target is still `SEEDING_CURRENT`: it took the
current path, and what the barrier measured is reported separately by
`Handshake.Result`. Nothing in the UI states an entropy figure.

**Raising the version is how a future improvement is deployed.** Bump
`AuthKey.SEEDING_CURRENT`, write the new seeding path, and every smaller version
falls under `AuthKey.seedingNeedsReauth` on its own — existing sessions start
recommending a re-sign-in without anything else changing. Two rules make that
safe in both directions:

* a version *larger* than this build's comes from a build that knows more; it is
  used as it is, reported as neither current nor legacy, and written back
  unchanged rather than clamped;
* a legacy key is re-saved in the bare form, never as an explicit `p0:`. The
  record is already exactly as informative, and a handset downgraded to an older
  build still finds its session.

**Nothing is deleted on the client's initiative.** A legacy key stays usable and
stays signed in. The start screen carries one line recommending — not demanding —
an ordinary log out and sign-in, and `Diagnostics → -- security --` states the
version permanently, for every outcome including "no key stored". No screen
claims a key was compromised, because nothing here is evidence that one was.

## Security posture, stated honestly

* The crypto primitives match published vectors, including through the shipped
  JAR.
* The `Rng` construction is a standard hash DRBG and is sound.
* **The seeding is measured, and one gather is not enough — so the auth-key path
  no longer takes one.** A Java ME runtime has no hardware RNG, so
  `tg.crypto.Entropy` scrapes what it can, and what that is worth is a property
  of the handset's clock. `tg.crypto.AuthKeySeeding` is the barrier every
  permanent key crosses: before the first nonce is drawn, `tg.mt.Handshake` folds
  in a domain-separating context naming the dc, the environment and the media
  role, then gathers repeatedly until the run has produced its target. Resuming a
  stored key runs none of it.
* **How many gathers is measured at run time, not compiled in.** The fixed 120 ms
  window inside `gather()` collects however many samples the clock tick allows,
  so the yield tracks the tick — by an order of magnitude across three handsets:

  | Handset | tick | samples | bits/sample | bits/gather | gathers the barrier takes |
  |---|---|---|---|---|---|
  | Alcatel OT-810D | 4 ms | 26 | 2.250 | 58 | ~10, about 1.3 s (projected) |
  | Samsung GT-C3592 | 12 ms | 10 | 2.125 | 21 | ~26, about 3.4 s (projected) |
  | Nokia C3-00 | 1 ms | 120 | 1.125–1.375 | 135–165 | **3, 394–526 ms (measured)** |

  Full figures per device under [docs/hardware/](hardware/). A constant sized
  from any one of them was wrong on the other two in opposite directions —
  five gathers is ~290 bits on the first, ~105 on the second and ~675 on the
  third — which is
  [issue #2](https://github.com/smbdsbrain/TelegramJ2ME/issues/2). Note that
  per-sample entropy is **not** constant across devices, as the first two
  suggested: it roughly halves between a 4 ms and a 1 ms tick, so a tick→bits
  formula would have been the same mistake one level down.

  `tg.crypto.JitterYield` therefore observes the samples the barrier is folding
  in — the same ones, through `Entropy.gather(sink)`, not a second measurement —
  and applies the estimator `tg.plat.EntropyProbe` publishes: most-common-value
  min-entropy at a 99% confidence bound, discounted when adjacent samples prove
  dependent, with everything that is not jitter charged at zero. Seeding stops
  when the run holds **256 samples and 256 credited bits**; the sample floor is
  `MinEntropy.MIN_SAMPLES_FOR_CONFIDENCE`, below which this project states no
  bound at all, and on the two coarse-clock handsets it is the floor that decides
  the count.
* **What the barrier refuses, and what it only reports.** A gather that comes
  back empty or short aborts the key, as does a run that credits zero bits —
  the frozen clock, which the probe calls "seeding is NOT SAFE". A slow clock
  that never reaches 256 bits inside the caps (64 gathers or 8 s) is different:
  the key is generated, `Handshake.Result` carries the shortfall, and the log
  says so at warning level. Refusing there would lock a working handset out of
  signing in over a bound the client cannot improve.
  **None of this is a claim of `gathers × bits-per-gather`.** Samples are pooled
  across gathers into one estimate, so repetition between them raises `p_max` and
  buys more gathers rather than being summed — but nothing here demonstrates that
  consecutive gathers are independent, and on any runtime not yet measured,
  generated keys are development keys. See
  [MTProto security guidelines](https://core.telegram.org/mtproto/security_guidelines).
* **The barrier costs the same on a handset as on a desktop, per gather.**
  Measured at 713 ms for five gathers on the GT-C3592 against 674 ms on a desktop
  JVM, because `collectJitter` spends a fixed wall-clock budget rather than doing
  work. Everything around it does not. On the Nokia C3-00, where the sizing has
  been measured end to end, a production sign-in spent **409 ms on seeding inside
  a 34 544 ms handshake — 1.2%** — of which 10 288 ms went to the pq
  factorisation and 21 837 ms to the two modular exponentiations. The GT-C3592,
  sized against its own clock, is projected at ~26 gathers or about 7.7% of its
  exchange. And it is paid once per key: a later relaunch of the client on that handset loaded the
  same key out of RMS and connected in 8847 ms with no barrier at all, against
  31 627 ms for the launch that generated it.
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
  the seeding on it. The GT-C3592 gives 2.750 bits/press, so 94 presses for one
  key — while a gather there is worth ~162 bits per second of blocking against
  the keyboard's ~9, and needs nobody present. Nothing currently feeds key timing
  into the application `Rng` — `Entropy.fromUserInput` is used only by the probe
  screen — so "where it is free" is a direction, not a shipped behaviour.
* Server-provided randomness must never be the sole source of DH secret entropy.
* DH parameter validation is mandatory before an `auth_key` is accepted; it is
  not a step to skip for a green demo.
