# Installing J2MEgram

The primary candidate is
`J2MEgram-<version>.jar` plus its matching `.jad`. The `-min` pair is the
same application, optimised and obfuscated for handsets with a smaller JAR
limit; use it only when the normal JAR is rejected.

Copy both files of one pair into the same folder by USB, Bluetooth, or memory
card, then open the `.jad`. If the handset refuses local JAD installation, open
the matching `.jar`. Do not rename files or mix normal/minified pairs: the JAD
contains the exact JAR filename and byte length.

## Upgrade from TelegramJ2ME (1.2.0 and earlier)

As of 1.3.0 the suite is renamed to `MIDlet-Name: J2MEgram` to comply with the
[Telegram API Terms of Service](https://core.telegram.org/api/terms). MIDlet
suite identity is Name plus Vendor, so a J2MEgram build installs **alongside**
an existing TelegramJ2ME suite rather than upgrading it: it starts with empty
record stores, and you sign in again. Once the new install works, remove the
old TelegramJ2ME suite manually — removing it deletes its auth key, settings,
outbox, drafts, and caches, so do it only after the new sign-in succeeds.

From 1.3.0 onwards the name and vendor stay fixed, so a conforming AMS treats
later versions as in-place upgrades again and preserves RMS. If the phone
offers “replace/update” and “remove”, choose update.

## Before connecting

- Set the phone date, time and numeric time-zone offset, especially when using
  FakeTLS MTProxy. Old firmware can name the right city but carry obsolete DST
  rules; Nokia E6-00 firmware 111.140.0058 labels Moscow as GMT+4.
- Grant network permissions when prompted.
- If unsigned MIDlets are denied ports 80/443, configure an MTProxy on an
  allowed high port before Connect.
- Keep the normal and minified artifacts' SHA-256 values with the files.

The RC is handed off only after both variants pass the emulator E2E gate. A tag
and public GitHub Release wait for the manual Nokia C3-00 checklist in
[the device evidence template](testing/device-evidence-template.md).
