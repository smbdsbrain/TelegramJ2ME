# 1.0 device evidence

Copy this file for a device run. Do not record phone numbers, account IDs,
usernames, peer titles, ordinary message text, auth keys, proxy secrets, or
private endpoints. Test messages use an opaque marker only.

| Field | Value |
|---|---|
| Date (UTC) | |
| Commit / artifact | |
| SHA-256 | |
| Device and firmware | |
| CLDC / MIDP | |
| Network type | |
| Fresh install or upgrade | |
| Previous installed version | |
| Available heap shown by diagnostics | |

## Scenario evidence

| Scenario ID | Procedure variant | Observable result | Status |
|---|---|---|---|
| DEVICE-01 | Install the normal 1.0 RC over 0.8.1 without clearing RMS; start, reconnect, open cached dialogs/history, and send only the agreed marked test message. | | NOT RUN |
| NET-03 | On a deliberately slow route, run peer search, Back, and retry; record durations and whether the screen remains usable. | | NOT RUN |
| RMS-02 | If a safe power-loss or storage-fault procedure is available, record its exact boundary and recovery. Do not simulate flash evidence with desktop `FaultyRecords`. | | NOT RUN |

## Manual checklist

- [ ] Existing session resumed; no phone-number prompt.
- [ ] Cached dialogs/history were visible while reconnecting.
- [ ] Dialog paging and deep history paging remained usable.
- [ ] Find messages opened the selected result.
- [ ] Full text and entity picker showed the exact target; external launch was cancelled.
- [ ] Own outgoing text could be edited and showed `edited` after the update.
- [ ] Back/soft-key navigation worked without relying on a navigation cluster.
- [ ] No crash-log entry was created during this run.
- [ ] Test marker was removed for everyone, or recorded separately for cleanup.

## Attachments

List sanitized screenshots/log filenames and their SHA-256 values. State any
redactions. Raw emulator profiles and RMS stores are private and must not be
attached or committed.
