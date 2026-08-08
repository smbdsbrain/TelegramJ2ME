# Installing TelegramJ2ME 1.0 RC

The primary upgrade candidate is
`TelegramJ2ME-1.0.0-rc1.jar` plus its matching `.jad`. The `-min` pair is the
same application, optimised and obfuscated for handsets with a smaller JAR
limit; use it only when the normal JAR is rejected.

Copy both files of one pair into the same folder by USB, Bluetooth, or memory
card, then open the `.jad`. If the handset refuses local JAD installation, open
the matching `.jar`. Do not rename files or mix normal/minified pairs: the JAD
contains the exact JAR filename and byte length.

## Upgrade from 0.8.1

Install over the existing suite without deleting it. Both versions use
`MIDlet-Name: TelegramJ2ME`, `MIDlet-Vendor: smbdsbrain`, and the production
environment, so a conforming AMS treats 1.0.0 as an upgrade and preserves RMS.
If the phone offers “replace/update” and “remove”, choose update. Removing the
old suite usually deletes its auth key, settings, outbox, drafts, and caches.

History cache is upgraded on write from v1/v2 to v3. A later downgrade to 0.8.1
may discard the newer history cache, so downgrade is not a cache-preservation
path.

## Before connecting

- Set the phone clock, especially when using FakeTLS MTProxy.
- Grant network permissions when prompted.
- If unsigned MIDlets are denied ports 80/443, configure an MTProxy on an
  allowed high port before Connect.
- Keep the normal and minified artifacts' SHA-256 values with the files.

The RC is handed off only after both variants pass the emulator E2E gate. A tag
and public GitHub Release wait for the manual Nokia C3-00 checklist in
[the device evidence template](testing/device-evidence-template.md).
