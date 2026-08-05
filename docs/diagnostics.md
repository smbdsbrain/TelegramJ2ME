# Measuring a handset, and getting the results off it

A 2011 feature phone has no console, no debugger, no `System.out` you can see
and no file export. Everything this project measures - heap ceiling, RMS limits,
image decode cost, entropy quality, crypto timings, crash tails - is computed on
the device and then has nowhere to go.

Until now the answer was a person copying figures off a 320x240 screen into a
GitHub issue. That works for one number and fails for a crash tail.

`tools/ingest-server.py` is the missing destination.

## The probe

`probe.jar` is the first thing to put on an unknown handset. About 110 KB,
installs in seconds, and deliberately contains **no crypto and no Telegram
code** — so it answers "what is this device" without any of the client's own
behaviour mixed in, and ProGuard can shrink it to something quick to reinstall
over and over.

```
./tools/build.ps1 -Target probe
```

| Menu item | What it answers |
|---|---|
| Platform & build | CLDC/MIDP version, encoding, locale, which optional JSRs exist |
| Heap probe | the real ceiling — allocate until the VM refuses, plus the largest single block. The messenger runs a coarser version of the same probe on its first launch; see [architecture.md](architecture.md#memory-discipline) |
| RMS test | record store limits, whether a record reads back identical, whether it survives exit |
| Entropy measure | clock granularity, jitter, `hashCode` and heap readings; the RNG seeding evidence |
| Clock & timers | the clock's tick, whether `Thread.sleep` is a lower bound, and whether the wall clock ever jumps or runs backwards |
| Text round trip | where non-ASCII text stops surviving — `Utf8`, the platform conversion, RMS, or the upload path |
| Display caps / Display size | colours, font metrics, and the canvas the AMS actually hands over, with and without full-screen mode |
| Keys / Key timing | key codes, game actions, and bits per key press |
| Public TCP echo | whether a raw socket works at all |
| Telegram DC socket :80/:443/:5222/:8443 | which ports an unsigned MIDlet is allowed to open |
| Two sockets at once | whether a second socket can be opened, and whether trying breaks the first |
| PNG / JPEG decode | whether the **platform** can decode each format |
| Emoji sheet cost | what holding the emoji sprite sheet actually costs in heap |
| Background socket | whether a socket survives `pauseApp`/`startApp` |
| Diagnostic log / Crash log | the ring buffer and any recorded crash |
| **Upload all** | runs every non-interactive item above and uploads each result |

Most of those exist because a device disagreed with an assumption. **Emoji sheet
cost** was added to test a theory that the sprite sheet was exhausting a small
heap — it measured 48 KB and killed the theory. **PNG / JPEG decode** is a
platform capability, not a client one: a `FAIL` on JPEG does not mean photos are
broken, because the client carries its own decoder. **Two sockets at once** is
there because one handset refuses the second *and corrupts the first*, which is
why `Single socket mode` exists. **Clock & timers** is there because every
network timeout is computed from `System.currentTimeMillis()`, and **Text round
trip** because `microedition.encoding` is ISO8859-1 on every handset measured,
so any path that reaches for `String.getBytes()` loses non-Latin text — as one
crash entry once did. It reports each layer separately, because the useful
answer is *which* stage failed.

The messenger's Diagnostics upload carries a `-- storage --` section for the
same reason the probe cannot answer it: record stores are scoped per MIDlet
suite, so `probe.jar`'s RMS results say nothing about `tg.jar`'s. It reports the
cross-launch persistence marker, the size of each store, and whether the key
store has had a write fail.

`crypto.jar` is the second install, once the probe has shown the phone runs
these JARs at all. It carries the whole crypto stack and answers whether the
FIPS and OpenSSL vectors still hold after this toolchain compiled, preverified
and shrank the code — and how long a 2048-bit modular exponentiation takes,
which is the cost of an `auth_key` handshake and the number the project's
viability rests on.

Results from both are written up per device under
[`hardware/`](hardware/).

## What it is not

It is not a backend, and the project does not acquire one. It stores formatted
diagnostic text and nothing else: it terminates no protocol, relays no traffic
and holds no session state. The client speaks MTProto on-device and that does
not change. `tools/echo-server.py` and `tools/log-server.py` carry the same
contract for the same reason.

Every build published from this repository has **no collector configured** and
cannot upload anywhere. That is the default and the correct state.

## It says so on the device

A build that can send diagnostics somewhere must be able to be seen doing it.
A messenger that quietly ships logs off a phone is exactly what this project
exists to avoid being, so the capability is disclosed twice, in the places
someone would actually look:

- the **connection screen**, before anything is used: *"Diagnostics: can upload
  to &lt;host&gt; on request. Never automatically."*
- **Settings**, where it can be turned on, naming the host, the device label
  used, and the two ways anything is ever sent - pressing Upload, or the Remote
  log toggle, which is off by default.

A build with no collector says that instead, in the same place. Nothing is
uploaded without an explicit action either way.

## Running a collector

```
python tools/ingest-server.py --token <at least 8 characters>
python tools/ingest-server.py --token dev --http-port 8080 --data ./probe-reports
```

| Route | Purpose |
|---|---|
| `POST /r/<token>/<target>/<device>` | one report, UTF-8 text, 64 KiB max |
| `GET /r/<token>/` | index of stored reports |
| `GET /r/<token>/<date>/<file>` | read one back |
| `GET /healthz` | liveness, unauthenticated |

There is also a TCP line protocol on `8443`, which is what `tg.plat.TcpLogSink`
speaks: greeting line `<token> <target> <device>`, then one log line per
newline.

**8443, not 443, and HTTP on 80 rather than a high port.** MIDP forbids an
untrusted MIDlet from opening a `socket://` connection to ports 80 or 443, which
is measured behaviour on the one handset tested, not folklore. `http://` is not
covered by that restriction, so the HTTP listener can sit on 80 - which is also
the port most likely to survive a carrier APN.

Everything is bounded because the other end is a phone that may be looping and
the link is metered: body cap, line cap, per-file cap, per-IP connection cap,
and a total disk cap that evicts oldest-first.

## Pointing a build at one

Create `secrets/dev-sink.yaml` (gitignored):

```yaml
host: 192.0.2.10
http_port: 80
tcp_port: 8443
token: <the same token>
device: my-handset
```

`tools/build.ps1` reads it into `generated/tg/app/DevSink.java`, the same
mechanism that already turns `secrets/telegram.yaml` into `Secrets.java`. Both
`secrets/` and `generated/` are gitignored and both are rejected by
`tools/audit-public.ps1`, so an endpoint cannot reach a commit by accident.

With no such file the build prints `no report sink configured` and the upload
commands say so on screen instead of dialling anywhere.

The messenger build also accepts a destination typed into
**Settings -> Remote log host/port**, which overrides the compiled-in one.

## Using it

**Probe** gains **Upload** on every result screen and **Upload all**, which runs
Platform, Heap probe, RMS, Image decode, Emoji sheet, Entropy log, Diagnostic
log and Crash log in that order and uploads each as it completes. Platform and
heap go first on purpose: every other figure is read against them, and the
handset may not survive the sweep.

**Crypto** gains **Upload** on the vectors, benchmark and PBKDF2 results. The
number worth carrying off is the 2048-bit modPow timing - it is what an
`auth_key` handshake costs.

**The messenger** gains **Crash log**, which it never had. It has always written
`tgcrash` and never read it, and `ProbeMidlet` cannot help: MIDP scopes record
stores to the MIDlet suite, so each JAR sees only its own. **Upload** on the
Diagnostics screen ships the ring buffer, connection state, crash entries and
heap figures together.

## What is stripped before anything is sent

`tg.plat.Report.redact` runs on the device, before transmission:

- 32+ hex digit runs, including the space-separated form `Diag.hex` produces,
  become `<hex:NN>` - the length is kept because it is often the diagnostic
  point and discloses nothing;
- values following `api_hash`, `auth_key`, `password`, `token`, `secret`,
  `session`, `phone`, `srp`, `salt` or `nonce` become `<redacted>`;
- `+` followed by seven or more digits becomes `<phone>`.

`tgtest.ReportTest` covers these, and also asserts that ordinary measurements
pass through untouched - a redactor that ate `largestSingleAlloc` would make the
whole exercise pointless.

This is a backstop. It does not license adding logging that prints message
text, contact names or key material.

## Where the code is

| File | Role |
|---|---|
| `tools/ingest-server.py` | the collector; stdlib only, no dependencies |
| `src/tg/plat/Report.java` | header, redaction, chunking |
| `src/tg/plat/HttpReportSink.java` | upload over `tg.io.HttpExecutor` |
| `src/tg/plat/ReportUpload.java` | worker thread and progress, shared by all three MIDlets |
| `src/tg/plat/TcpLogSink.java` | the pre-existing line-protocol sink |

MIDP's `HttpConnection` has no timeout control, so an upload can block for as
long as the runtime decides. Every send runs on a worker thread; doing it in a
lcdui callback would freeze the display, and some AMS implementations treat that
as a hung MIDlet and kill it - while it is being diagnosed.
