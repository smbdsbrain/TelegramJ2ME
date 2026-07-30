# pdf.js JPEG decoder attribution

`src/tg/ui/JpegDecoder.java` ports the entropy decoding, progressive scan and
fixed-point IDCT algorithms from Mozilla pdf.js:

- Upstream: https://github.com/mozilla/pdf.js/blob/v4.10.38/src/core/jpg.js
- Pinned source tag: `v4.10.38`
- Copyright 2014 Mozilla Foundation
- License: Apache License 2.0

The port replaces JavaScript typed arrays and browser output with
CLDC-compatible Java arrays and MIDP `Image.createRGBImage`, and adds the
project's compressed/decoded size limits and cooperative cancellation.

Apache License 2.0:
https://www.apache.org/licenses/LICENSE-2.0
