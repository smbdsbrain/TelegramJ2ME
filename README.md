# TelegramJ2ME

**Telegram for Java ME feature phones.** A real MTProto 2.0 client that runs on
the handset — no server, no proxy service, no web wrapper.

[![CI](https://github.com/smbdsbrain/TelegramJ2ME/actions/workflows/ci.yml/badge.svg)](https://github.com/smbdsbrain/TelegramJ2ME/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/smbdsbrain/TelegramJ2ME?sort=semver)](https://github.com/smbdsbrain/TelegramJ2ME/releases/latest)
[![License: WTFPL](https://img.shields.io/badge/license-WTFPL-blue.svg)](LICENSE)

It installs as an ordinary `.jar` on a **MIDP 2.0 / CLDC 1.1** handset and signs
in to your Telegram account. Cryptography, authorization keys, TL serialization
and Telegram state all live on the phone. Nothing relays your messages through
anyone else's machine.

In practice that means a late feature phone — roughly 2008 onwards — or a
high-end handset from a few years before that. See
[what your phone needs](#what-your-phone-needs).

<p align="center">
  <img src="docs/screenshots/dialog-list.png" width="320" alt="TelegramJ2ME chat list on a 320x240 Java ME screen">
  <img src="docs/screenshots/weekend-chat.png" width="320" alt="TelegramJ2ME group chat with emoji on a feature phone">
  <img src="docs/screenshots/j2me-club-dark.png" width="320" alt="TelegramJ2ME dark theme conversation">
</p>

<p align="center"><sub>Real application UI at 320×240, scaled 2×. Every name and conversation is fictional.</sub></p>

> [!WARNING]
> TelegramJ2ME is an independent, early-stage project. It is not affiliated with
> or endorsed by Telegram, and it should not yet be treated as a
> security-audited everyday client.

---

## It runs on a real phone

Not just in an emulator. A physical MIDP 2.0 handset — a 2011-era candybar with
about 5 MB of Java heap and **no Wi-Fi at all, only GPRS** — has run the whole
thing end to end:

- the full `req_pq_multi` … `dh_gen_ok` authorization handshake, with both
  2048-bit modular exponentiations computed on the phone's own CPU;
- MTProto 2.0 encryption, salt adoption, gzip inflate and keepalive over 2G;
- the auth key persisted to RMS and reused on the next start;
- **sign-in, the dialog list, and sending a message.**

The first connection is slow — a 2048-bit Diffie-Hellman on a CPU from 2011 is
measured in seconds, not milliseconds — but it only happens once, and after that
the client is talking to Telegram over a GPRS link like any other MTProto
client.

A second handset — a Samsung GT-C3592, also GPRS-only — now runs it too, and it
is interesting because it broke things the first one never
did. Its platform cannot decode JPEG at all, and it refuses to hold two sockets
at once, which is what a naive media download needs. Both are handled;
[the measurements are written up in full](docs/hardware/samsung-gt-c3592.md),
including the numbers that killed a memory theory the project had been carrying
for months. A third, the Nokia C3-00, runs the client in a 2 MB heap and exposed
slow-link session recovery and soft-key-only navigation constraints; its
[measurements are recorded separately](docs/hardware/nokia-c3-00.md). A fourth,
the Nokia E6-00, has at least 8 MB of heap and much faster crypto, but its old
Moscow GMT+4 time-zone table makes FakeTLS fail until a correct GMT+3 zone is
selected; the [E6-00 hardware report](docs/hardware/nokia-e6-00.md) records that
trap and its unusual fullscreen geometry.

That is **four devices on a handful of networks**, and it establishes nothing about yours.
Installing it and reporting what happens is genuinely the most useful thing
anyone can do for this project right now.

> One finding worth knowing before you start: some handsets refuse `socket://`
> to ports 80 and 443 for an unsigned MIDlet — `Target port denied to untrusted
> applications` — while permitting other ports. That is a policy, not a bug. If
> the direct routes are refused that way, configure an **MTProxy on a high port**
> in Settings *before* the first connection attempt.

---

## What works

**Account**
- Sign in with a phone number and an SMS or in-app code
- Two-step verification (cloud password)
- Sign up for a new account
- Resend or cancel the code · change number
- Log out, or log out of every other session
- View and edit your own profile

**Chats**
- Chat list with unread badges, pinned and peer icons, and avatars
- Filter the chat list by name
- Saved Messages
- Open a chat by `@username`
- View a peer's profile
- Mark all read
- Forum supergroups open as a topic list — per-topic transcripts, unread
  badges, read marks, drafts and sends that land in the right topic
- Channel comments: posts show their comment count, and the thread opens and
  accepts replies

**Messages**
- Read history and scroll back through it, loading older pages as you go
- Search inside the current chat and open a result in its history context
- Send text
- Reply
- Edit eligible own outgoing text messages
- Forward to another chat
- Delete for yourself, for everyone, or from a channel
- Read receipts, incoming and outgoing, channels included
- Send and remove reactions, with the picker and the "who reacted" list
- View the complete text and inspect supported URL, username, phone, and email
  targets before a confirmed external action
- Live updates with gap recovery — a real update state machine, not polling

**On a bad connection**
- Persistent outbox: queued messages survive a restart and replay with their
  original `random_id`, with retry and delete per message
- Per-peer drafts, autosaved
- Offline cache — the app opens to readable recent chats with no signal
- Reconnect with bounded backoff, plus a manual "Reconnect now"

**Getting through**
- Direct TCP
- obfuscated2
- MTProxy — classic, `dd` and `ee`/FakeTLS secrets
- MTProto over HTTP
- Automatic fallback across all four, remembering what last worked
- `tg://proxy` links pasted straight into Settings
- Multiple data centres, with transparent migration at sign-in

**Media**
- View photos, with zoom and D-pad pan
- Pure-Java JPEG decoder — most of these handsets cannot decode JPEG themselves
- Blurred inline thumbnails in the chat list
- Avatars, cached on the device
- ~150 emoji from a sprite atlas
- Every other media type labelled in place — `[sticker]`, `[voice]`, `[video]` —
  so the conversation still reads correctly, though nothing but a photo can
  actually be opened

**Interface**
- Adaptive Canvas UI that measures the viewport instead of assuming it
- Light, dark and high-contrast themes
- Diagnostics: per-route attempt log, byte counters, retry countdown
- In-app log, and a crash log that survives the MIDlet dying
- Optional remote logging over TCP

**Cryptography, all on the handset**
- MTProto 2.0: SHA-1/256/512, HMAC, PBKDF2, AES-CTR, AES-IGE, 2048-bit bigint
- The authorization key is generated on the phone and never leaves it
- SRP-6a for two-step verification

**A companion MIDlet**
- **Probe** (~172 KB) — reports what your handset actually supports: platform,
  heap, RMS, raw sockets, key codes, image decoding, how much entropy the
  runtime's clock and allocator actually yield and how many gathers an auth key
  therefore costs here, plus the cryptographic test vectors and benchmarks run
  on the device

## What is not there yet

Mostly because of what the platform is: a few megabytes of heap, no codecs, no
background execution and a CPU without a JIT.

**Sending — outbound is text only**
- Photos, files, voice, any attachment at all

**Opening incoming media other than photos.** The message itself arrives
normally, text and all, but where the attachment should be there is only a label
— `[voice]`, `[file]`, `[video]` — and no way to download or open it:
- Files and documents
- Voice messages, music, video, round video, GIFs
- Stickers, animated stickers, custom emoji

Photos are the one exception: those download and open.

**Not implemented**
- Notifications of any kind, including background alerts
- Secret chats (end-to-end)
- Voice and video calls
- Folders, polls, scheduled messages, typing indicators
- Group and channel administration, including topic management — topics are
  read and written, not created, closed or pinned; forwarding into a forum
  lands in General
- Contact management
- Stories, Mini Apps, bots beyond plain messages
- Localisation — English only

**Bounded by measurement** — the client measures its own heap on first launch and
sizes every buffer, cache and page limit from the answer. On the ~5 MB handsets
tested that is a sliding window of 120 chats, another of 120 messages per open
conversation, and a 1 MB packet ceiling. Both lists scroll as far as they go:
pages load as the edge of what is held approaches, rows and blocks that fall
outside the window are released, and what a window costs does not depend on how
far anybody scrolled — a chat list of 1690 holds the same 120 rows as one of 60,
and what stays wrapped in a conversation is three screens either side of the
viewport rather than everything ever read. Driven under a constrained heap it stays usable down to about **1.5 MB of
free heap**, and below that it gives up decoration rather than function: an
avatar or an inline preview that will not fit is refused and drawn as a
placeholder, and the conversation still opens. A smaller phone gets proportionally smaller numbers, down to floors it
refuses to divide past. It also watches its headroom and drops caches before a
big allocation rather than after the crash. Details in
[docs/architecture.md](docs/architecture.md#memory-discipline). Fixed regardless
of heap: 64 queued outgoing messages, 1000 characters per message.

**Two security caveats, stated plainly.** The random number generator's seeding
has been **measured on three handsets**, and what one entropy gather is worth
turns out to be a property of the phone's clock: about 21 bits on a Samsung
GT-C3592, 58 on an Alcatel OT-810D, 135–165 on a Nokia C3-00. A key exchange
wants 256, so the client measures its own yield while it collects and keeps
gathering until it has them — roughly 3 gathers on the fastest clock and 26 on
the slowest, a fraction of a second either way, once per key and never on
reconnect. What is still not established is that consecutive gathers are
independent of each other, and a runtime nobody has measured could be worse than
all three, so keys generated on a phone are **still development keys** ([the
numbers](docs/hardware/), [the
posture](docs/architecture.md#security-posture-stated-honestly)). And **RMS
offers no encryption**, so anyone holding your phone and the right tools can
extract the session.

---

## Install

### What your phone needs

| | |
|---|---|
| **MIDP 2.0 and CLDC 1.1** | Both are declared in the manifest. CLDC 1.0 will not run it. |
| **About 1 MB of free Java heap** | Measured by driving the client against a real account with the heap squeezed in steps: everything works above ~1.7 MB free; near 1.5 MB an avatar decode starts being refused, and from then on avatars and inline previews are drawn as placeholders while the chat itself still opens; below ~1.1 MB the client connects but little after that survives, and near 0.95 MB sign-in itself runs out of memory. Two measured handsets have ~5 MB and the Nokia C3-00 has 2 MB. Turning avatars and inline previews off in Settings gives back the ~340 KB those caches retain, and stops the decodes that need room on top of it. |
| **A JAR size limit above ~375 KB** | The 1.0 RC minified build is about 374 KB and the normal one about 523 KB. |
| **Raw TCP (`socket://`), or HTTP** | Raw sockets are the good path. There is an MTProto-over-HTTP fallback for handsets that refuse them outright. |

That rules out the early 2000s. A phone with a 64 KB JAR cap and a few hundred
KB of heap cannot hold the crypto stack, never mind a photo — the arithmetic
does not fit before the UI is even considered. What does work is a **late
feature phone, roughly 2008 onwards**, or a high-end handset from a few years
earlier: the generation with megabytes of heap, a real file manager and no
meaningful JAR ceiling.

Rather than guess from a spec site — they disagree about exactly these
numbers — install **`TelegramJ2ME Probe`** first. It is ~172 KB, installs in
seconds, and reports the heap, the JAR limit, whether raw sockets are permitted,
whether the phone can decode a JPEG, and what the cryptography costs on it.

### Getting the files on

Prebuilt MIDlets are attached to every release:
**[latest release](https://github.com/smbdsbrain/TelegramJ2ME/releases/latest)**.

Download **both files** of one variant into the **same folder**, copy that
folder to the phone (USB, Bluetooth or memory card), then open the `.jad` from
the phone's file manager. If the handset refuses the `.jad`, open the `.jar`
instead — most will install it directly.

### Which build?

| | |
|---|---|
| **`TelegramJ2ME-<version>.jar` + `.jad`**<br>~523 KB | **Start here.** Class and method names survive in this build, so if it crashes, the error names real code and the report is actionable. While the client is this young that is worth more than the kilobytes. |
| **`TelegramJ2ME-<version>-min.jar` + `.jad`**<br>~374 KB | The same client, optimised and obfuscated — about 29% smaller. Use it if your phone rejects the normal build as too large. **No features are removed:** same source, same entry point, same preverification. Only names and dead code go, so a crash report from it says `tg.h.x` instead of `tg.ui.SettingsScreen`. |

Both are checked by an automated emulator run before release, obfuscated one
included.

**Do not rename either file, and do not mix files from different variants or
releases.** The `.jad` records the exact byte size of its `.jar`, and the phone's
installer aborts on a one-byte disagreement. Checksums are published as
`SHA256SUMS.txt`.

**Installing over the air from GitHub will not work.** GitHub requires modern
TLS, which these handsets do not have. Copy the files across instead.

---

## Feedback

This is the part the project actually needs. Every handset is different, and
three measured devices are still only three data points.

- **[Report your phone](https://github.com/smbdsbrain/TelegramJ2ME/issues/new?template=device-report.yml)**
  — even if everything worked. Install `TelegramJ2ME Probe` first (it is tiny and
  installs in seconds) and paste what it reports: the model, the heap, whether
  raw sockets are allowed, which route connected. That is the missing
  information.
- **[Report a bug](https://github.com/smbdsbrain/TelegramJ2ME/issues/new?template=bug-report.yml)**
  — the Diagnostics and Log screens in the app are there so you can copy them
  into an issue.
- Anything else: [open an issue](https://github.com/smbdsbrain/TelegramJ2ME/issues).

Never paste your phone number, `api_id`, `api_hash` or an `auth_key` into an
issue.

---

## Build it yourself

Builds on **Windows, Linux and macOS**. You need JDK 8, Python 3 and
PowerShell 7.

```bash
git clone https://github.com/smbdsbrain/TelegramJ2ME.git
cd TelegramJ2ME
./tools/bootstrap.sh            # JDK 8 check + pinned, SHA-256-verified downloads
./tools/build.sh -Target tg     # -> dist/tg.jar + dist/tg.jad
./tools/test.sh                 # 57 Java suites + 46 Python tests
```
```powershell
.\tools\bootstrap.ps1
.\tools\build.ps1 -Target tg
.\tools\test.ps1
```

Full instructions, prerequisites per platform, credentials setup and live
testing against Telegram's servers: **[docs/building.md](docs/building.md)**.

## Docs

| | |
|---|---|
| [building.md](docs/building.md) | prerequisites, build, test, credentials, live testing |
| [installing.md](docs/installing.md) | RC files, 0.8.1 upgrade path, and handset checklist |
| [architecture.md](docs/architecture.md) | how it is put together, and an honest security posture |
| [1.0-stability-contract.md](docs/1.0-stability-contract.md) | what 1.0 guarantees, cache compatibility, and the RC handoff gate |
| [toolchain.md](docs/toolchain.md) | pinned versions, why JDK 8, preverification |
| [emulator-notes.md](docs/emulator-notes.md) | what an emulator proves and what it does not |
| [diagnostics.md](docs/diagnostics.md) | getting measurements and crash tails off a handset with no console |
| [hardware/](docs/hardware/) | what has actually been measured, per device |
| [releasing.md](docs/releasing.md) | cutting a release |
| [CHANGELOG.md](CHANGELOG.md) | release-candidate changes and compatibility notes |
| [CONTRIBUTING.md](CONTRIBUTING.md) | the CLDC subset rule, and what to run before a PR |

---

## Legal

Third-party clients must comply with the
[Telegram API Terms](https://core.telegram.org/api/terms): use your own
`api_id`, follow the
[MTProto security guidelines](https://core.telegram.org/mtproto/security_guidelines),
and do not use the official Telegram logo or imply official status. Telegram is
a trademark of Telegram Messenger Inc.

Vendored third-party code and its licences are recorded in
[third_party/bc/UPSTREAM.md](third_party/bc/UPSTREAM.md) and
[third_party/noto-emoji/UPSTREAM.md](third_party/noto-emoji/UPSTREAM.md).
No SDK binary is committed to this repository.

Project code is released under the [WTFPL](LICENSE). Vendored components remain
under their respective upstream licences.
