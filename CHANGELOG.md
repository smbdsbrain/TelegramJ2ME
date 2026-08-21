# Changelog

## Unreleased

### Changed

- The project and MIDlet suite are renamed from TelegramJ2ME to J2MEgram to
  comply with the [Telegram API Terms of Service](https://core.telegram.org/api/terms),
  which forbid the word "Telegram" in an app title. The JAD/manifest
  description becomes "Unofficial Telegram J2ME Client", release artifacts are
  now named `J2MEgram-<version>[-min]`, and the `initConnection` app_version
  shown in Telegram's "Active sessions" reads `J2MEgram <version>`.
- Because MIDlet suite identity is Name plus Vendor, a J2MEgram build installs
  alongside an existing TelegramJ2ME install instead of upgrading it in place:
  sign in again in the new install, then remove the old suite manually.
- The generated Telegram API schema now targets layer 225, pinned to the final
  official Telegram Desktop layer-225 TL commit and converted to JSON offline.
  The public JSON endpoint's exact older hash is monitored without allowing an
  unknown schema change to pass.
- Exact packaged E2E accepts current semantic-version artifact names. The live
  forum gate deletes and verifies both probe messages even after a failed run.

### Testing

- Live production config, dialogs, reactions and forum flows pass at layer 225.
  Normal and minified packaged JARs also pass the two-account flow under both
  ordinary and fragmented slow-network transport, with server cleanup
  confirmed after every run.

## 1.2.0

### Added

- Incoming message formatting (issue #18): bold, italic, underline,
  strikethrough, inline code, preformatted blocks and block quotes are laid
  out with bounded MIDP fonts while preserving UTF-16 and emoji boundaries.
- Lightweight spoilers use a static dotted bitmap mask with no animation or
  timer. A focused concealed message exposes a `Reveal spoiler` command, and
  revealing it changes paint state without reflowing the transcript.
- A live `forumspoiler` emulator scenario covers a spoiler containing an emoji
  and a link in a real forum topic, including the dialog list, topic list,
  concealed transcript, reveal action and post-reveal link action.

### Changed

- Visual entities may overlap each other and actionable entities; styles are
  combined, while only invalid UTF-16 ranges and exact duplicates are dropped.
  Entity retention remains bounded and fails closed by concealing the whole
  message if its spoiler count exceeds the cap.
- Links, mentions, email addresses and phone numbers overlapping a concealed
  spoiler are unavailable until the message is revealed.
- Dialog and topic previews, full-text views, replies, forwards and search
  representations redact concealed ranges before clipping or caching them.

### Fixed

- Spoiler text no longer leaks through the dialog list or forum topic list.
  Verified in a real topic and in the chat list on a physical Nokia E6-00.

### Compatibility notes

- Dialog cache is v3. Older v1/v2 dialog records stored only flattened preview
  text and are deliberately discarded because their missing entities make old
  spoiler previews impossible to conceal safely; dialogs are fetched again.
  History, auth keys, outbox, drafts and update cursors are unchanged.
- Formatting is incoming-only. LaTeX, custom emoji, animated spoiler particles,
  collapsed block quotes and formatted-message composition remain out of scope.

## 1.1.0

### Added

- Forum supergroups open as a topic list (issue #17): a bounded, windowed
  topic screen between the chat list and the transcript, per-topic history
  via `messages.getReplies`, sends that write `top_msg_id`, per-topic read
  cursors via `messages.readDiscussion`, unread badges, closed/hidden
  markers, live row updates, and in-topic message search.
- Channel comments: posts show their comment count, a `Comments` action opens
  the linked discussion thread, and replies can be sent into it.
- The open transcript's identity is `(peer, thread)` end to end: staleness
  guards, read marks, the acknowledgement queue, local read reconciliation,
  the composer, drafts, and the outbox all carry the thread beside the peer.
- Live per-thread read sync from other devices
  (`updateReadChannelDiscussionInbox`), and channel pts seeding from thread
  responses so discussion groups outside the dialog window do not loop on
  snapshot recovery.
- A `forum` live scenario (`./tools/live.ps1 forum`) driving the whole
  feature end to end against a prepared account.

### Changed

- Outbox records are v3 (the thread survives a power cut before the first
  send; `random_id` is still never regenerated), drafts are envelope v2
  (keyed by peer and thread), history cache is v4 (keyed by peer and thread,
  carrying the reply-header thread facts), and the dialog cache is v2
  (carrying the forum flag so an offline forum opens as its topic list).
  Every legacy format is still read as thread 0.
- The navigation stack floor is five screens: chat list, topic list,
  transcript, photo, over the root.

### Fixed

- Sending a reply in a forum no longer lands outside every topic: the topic
  root travels in `inputReplyToMessage.top_msg_id`.
- Plain topic messages no longer carry a "Reply to" caption naming the
  topic's root service message; the caption stays on real replies. Verified
  on a physical Nokia E6-00.

### Compatibility notes

- Downgrading discards history-cache v4 and dialog-cache v2 records; they are
  refetched. Auth keys, outbox, drafts, and update cursors are unaffected.
- Topic management (create/close/pin) is out of scope; forwarding into a
  forum lands in General.

## 1.0.1

### Added

- Live foreground updates for open chats and the dialog list, backed by
  `updates.getDifference` recovery and a 30-second safety audit.
- Compact `online/live`, degraded retry, and `+N new` status indicators that
  preserve the reader's viewport and current dialog selection.
- Update diagnostics with the last delivery source, update age, next retry or
  audit, and bounded queue size.

### Fixed

- A temporary common or channel recovery failure no longer silently disables
  the MTProto push loop; retries are isolated and use bounded backoff.
- Optimised builds no longer let ProGuard synthesize CLDC-incompatible
  `Integer.valueOf(int)` and `Long.valueOf(long)` calls. The packaged class tree
  is now API-checked after optimisation as well as before it.

### Verified

- Foreground live delivery, dialog updates, pause/resume catch-up, and recovery
  from degraded state on a physical Nokia E6-00.
- The CLDC incompatibility was reproduced from a physical Nokia C3-00 failure;
  the corrected packaged class tree passes the post-optimisation API gate.

## 1.0.0-rc1

Release candidate; not tagged or published until the Nokia C3-00 upgrade run.

### Added

- Bounded in-chat server-side message search with paged results and
  history-around opening.
- Full-message text view and bounded parsing/detection of URL, text URL,
  username, mention-name, phone, and email entities.
- Bounded URL, email, phone, and username entities are also attached to new
  and edited outgoing text so Telegram preserves the same safe actions.
- Confirmed, validated external actions with explicit platform-launch outcomes.
- Editing of eligible own outgoing text, live edit updates, an `edited` label,
  and history-cache v3.
- Deterministic 1.0 failure matrix, stability gate, and evidence template.

### Changed

- History cache reads v1/v2/v3 and writes v3; dialog cache remains v1.
- Release metadata is `MIDlet-Version: 1.0.0` while preserving the existing
  production suite name and vendor for an in-place 0.8.1 upgrade.
- Public-looking `TelegramJ2ME-*` builds refuse embedded development report or
  proxy secrets.

### Compatibility notes

- Downgrading to 0.8.1 may discard history cache v3.
- Telegram's live config reports layer 225 while the pinned public JSON schema
  remains layer 223; the client intentionally continues to advertise 223.
- New emulator and Nokia C3-00 rows remain `NOT RUN` until their exact RC gates
  complete.

## 0.8.1

Baseline production release for the 1.0 upgrade path. See the Git history and
GitHub release notes for earlier changes.
