# Changelog

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
