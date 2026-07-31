#!/usr/bin/env python3
"""
Development-only device report collector.

The handset has no console, no debugger and no file export. Everything the
probe measures - heap ceiling, RMS limits, image decode cost, entropy, crash
tails - is assembled on the device as plain text and then has nowhere to go
except a human retyping it off a 2011 screen. This server is that missing
destination: the device POSTs the report, the report lands in a file, and the
development machine reads it.

Like tools/echo-server.py and tools/log-server.py, this MUST NEVER touch
Telegram protocol traffic. It accepts already-formatted diagnostic text and
appends it to a file. It terminates no crypto, relays nothing, and holds no
protocol state - the device owns all of that.

Two listeners, because the handset can only reliably use one of them:

  HTTP   POST /r/<token>/<target>/<device>   body = UTF-8 report text
         GET  /r/<token>/                    plain-text index
         GET  /r/<token>/<date>/<file>       one report back as text
  TCP    line protocol, first line "<token> <target> <device>", then one
         diagnostic line per newline - what tg.plat.TcpLogSink speaks.

The TCP default is 8443, not 443 or 80: MIDP forbids an untrusted MIDlet from
opening a socket:// connection to ports 80 and 443, and on the one handset
where this was measured (Alcatel OT-810D) 8443 was reachable while both of the
privileged ports were refused. http:// is not subject to that rule, so the HTTP
listener can sit on 80 and be reached by the same device.

Everything is bounded on purpose. The far end is a phone that may be looping,
the link is metered GPRS, and on a public address the listener is reachable by
anyone who guesses the path, so: a shared token, a body cap, a line cap, a
per-file cap, and a total-disk cap that evicts oldest-first.

Usage:
    python tools/ingest-server.py --token dev
    python tools/ingest-server.py --token dev --http-port 8080 --tcp-port 8443
    python tools/ingest-server.py --token dev --data ./probe-reports

Environment variables (used by the container image; flags win):
    INGEST_TOKEN, INGEST_DATA, INGEST_HTTP_PORT, INGEST_TCP_PORT,
    INGEST_MAX_TOTAL_MB
"""

import argparse
import datetime
import hmac
import os
import re
import socket
import socketserver
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# A phone that goes wrong can spew, and the link is metered. Refuse early
# rather than let a runaway MIDlet fill a disk or a bill.
MAX_BODY = 64 * 1024
MAX_LINE = 4096
MAX_FILE_BYTES = 4 * 1024 * 1024
DEFAULT_MAX_TOTAL_MB = 256

# One report is a burst, not a stream: a handful of small POSTs and done.
HTTP_IDLE_TIMEOUT = 60
TCP_IDLE_TIMEOUT = 900
MAX_CONNS_PER_IP = 8

# Path components become file names, so they are allow-listed rather than
# escaped. Anything else is a client bug or an attempt to walk out of the tree.
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$")
SAFE_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc)


def stamp():
    return utc_now().strftime("%Y-%m-%dT%H:%M:%SZ")


def log(message):
    print("%s  %s" % (stamp(), message), flush=True)


class Store:
    """Append-only text files under <root>/<date>/<device>-<target>.log."""

    def __init__(self, root, max_total_bytes):
        self.root = os.path.abspath(root)
        self.max_total_bytes = max_total_bytes
        self.lock = threading.Lock()
        os.makedirs(self.root, exist_ok=True)

    def append(self, target, device, text, peer, channel):
        """Write one report. Returns the path relative to the data root."""
        day = utc_now().strftime("%Y-%m-%d")
        rel = os.path.join(day, "%s-%s.log" % (device, target))
        path = os.path.join(self.root, rel)

        banner = ("\n===== %s  %s  target=%s device=%s from=%s =====\n"
                  % (stamp(), channel, target, device, peer))

        with self.lock:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            if os.path.exists(path) and os.path.getsize(path) >= MAX_FILE_BYTES:
                # Do not silently drop: the fact that a file filled up is
                # itself a finding, and a truncated tail is worse than a
                # rolled one.
                rolled = "%s.%s" % (path, utc_now().strftime("%H%M%S"))
                os.replace(path, rolled)
            with open(path, "a", encoding="utf-8", newline="\n") as handle:
                handle.write(banner)
                handle.write(text)
                if not text.endswith("\n"):
                    handle.write("\n")
            self._prune_locked()
        return rel

    def index(self):
        rows = []
        for day in sorted(os.listdir(self.root)):
            day_dir = os.path.join(self.root, day)
            if not os.path.isdir(day_dir):
                continue
            for name in sorted(os.listdir(day_dir)):
                full = os.path.join(day_dir, name)
                if not os.path.isfile(full):
                    continue
                info = os.stat(full)
                modified = datetime.datetime.fromtimestamp(
                    info.st_mtime, datetime.timezone.utc)
                rows.append((day, name, info.st_size,
                             modified.strftime("%Y-%m-%dT%H:%M:%SZ")))
        return rows

    def read(self, day, name):
        path = os.path.join(self.root, day, name)
        if not os.path.isfile(path):
            return None
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            return handle.read()

    def _prune_locked(self):
        """Evict oldest-first until the tree is under the total cap."""
        files = []
        total = 0
        for day in os.listdir(self.root):
            day_dir = os.path.join(self.root, day)
            if not os.path.isdir(day_dir):
                continue
            for name in os.listdir(day_dir):
                full = os.path.join(day_dir, name)
                if not os.path.isfile(full):
                    continue
                size = os.path.getsize(full)
                files.append((os.path.getmtime(full), size, full))
                total += size

        if total <= self.max_total_bytes:
            return

        files.sort()
        for _, size, full in files:
            if total <= self.max_total_bytes:
                break
            try:
                os.remove(full)
                total -= size
                log("pruned %s (%d bytes)" % (os.path.relpath(full, self.root), size))
            except OSError as exc:
                log("prune failed for %s: %s" % (full, exc))

        for day in os.listdir(self.root):
            day_dir = os.path.join(self.root, day)
            if os.path.isdir(day_dir) and not os.listdir(day_dir):
                try:
                    os.rmdir(day_dir)
                except OSError:
                    pass


class ConnectionLimiter:
    """Crude per-IP concurrency cap; the listener may sit on a public address."""

    def __init__(self, limit):
        self.limit = limit
        self.lock = threading.Lock()
        self.counts = {}

    def acquire(self, ip):
        with self.lock:
            current = self.counts.get(ip, 0)
            if current >= self.limit:
                return False
            self.counts[ip] = current + 1
            return True

    def release(self, ip):
        with self.lock:
            current = self.counts.get(ip, 0) - 1
            if current <= 0:
                self.counts.pop(ip, None)
            else:
                self.counts[ip] = current


def token_ok(expected, given):
    # compare_digest so a wrong token cannot be found one character at a time.
    return hmac.compare_digest(expected, given or "")


class BodyTooLarge(Exception):
    pass


class BodyUnreadable(Exception):
    pass


class ReportHandler(BaseHTTPRequestHandler):
    # HTTP/1.0 keeps every response self-delimiting and closes the socket
    # afterwards. MidpHttpExecutor opens one connection per POST anyway, and a
    # 2011 HTTP stack is not worth testing against keep-alive.
    protocol_version = "HTTP/1.0"
    server_version = "tg-ingest"
    sys_version = ""

    store = None
    token = ""
    limiter = None

    def log_message(self, fmt, *args):
        log("http %s %s" % (self.client_address[0], fmt % args))

    def do_POST(self):
        parts = self.split_path()
        # /r/<token>/<target>/<device>
        if len(parts) != 4 or parts[0] != "r" or not token_ok(self.token, parts[1]):
            self.deny()
            return
        target, device = parts[2], parts[3]
        if not SAFE_NAME.match(target) or not SAFE_NAME.match(device):
            self.deny()
            return

        try:
            body = self.read_body()
        except BodyTooLarge:
            self.send_text(413, "body must be at most %d bytes\n" % MAX_BODY)
            return
        except BodyUnreadable as exc:
            # Distinct from 413 on purpose. A handset whose HTTP stack (or whose
            # carrier gateway) does not send a length is a completely different
            # problem from a handset sending too much, and the phone can only
            # show us the status code.
            log("http %s POST with unusable body: %s | %s"
                % (self.client_address[0], exc, self.header_summary()))
            self.send_text(411, "could not determine body length\n")
            return

        if not body:
            log("http %s POST with empty body | %s"
                % (self.client_address[0], self.header_summary()))
            self.send_text(411, "empty body\n")
            return

        text = body.decode("utf-8", "replace")

        rel = self.store.append(target, device, text,
                                self.client_address[0], "http")
        # The header summary is here and not only on the failure paths because
        # the first question about a handset is always "what does its HTTP
        # stack actually do", and by the time it goes wrong the working request
        # you wanted to compare against is gone.
        log("stored %s (%d bytes) from %s | %s"
            % (rel, len(body), self.client_address[0], self.header_summary()))
        self.send_text(200, "ok %s\n" % rel)

    def do_GET(self):
        parts = self.split_path()

        # Unauthenticated on purpose, and deliberately says nothing beyond
        # "the process is listening". A container healthcheck that had to
        # present the token would leave it visible in `docker inspect`.
        if parts == ["healthz"]:
            self.send_text(200, "ok\n")
            return

        if len(parts) < 2 or parts[0] != "r" or not token_ok(self.token, parts[1]):
            self.deny()
            return

        if len(parts) == 2:
            rows = self.store.index()
            if not rows:
                self.send_text(200, "no reports yet\n")
                return
            lines = ["%-12s %-40s %10s  %s" % ("date", "file", "bytes", "modified")]
            for day, name, size, modified in rows:
                lines.append("%-12s %-40s %10d  %s" % (day, name, size, modified))
            self.send_text(200, "\n".join(lines) + "\n")
            return

        if len(parts) == 4 and SAFE_DATE.match(parts[2]) and SAFE_NAME.match(parts[3]):
            text = self.store.read(parts[2], parts[3])
            if text is None:
                self.deny()
                return
            self.send_text(200, text)
            return

        self.deny()

    # ------------------------------------------------------------ body read

    def read_body(self):
        """
        The request body, however this client chose to frame it.

        Reading Content-Length was not enough. A J2ME handset does set the
        header, but what arrives here is whatever its HTTP stack and its
        carrier's gateway between them decided to send, and a feature phone on
        an operator APN is routinely proxied. Three framings are accepted:

          chunked          - what a stack that streams the body will use
          Content-Length   - the well-behaved case
          neither          - read to EOF, bounded and with a timeout

        The bytes are plain UTF-8 regardless. MidpHttpExecutor labels them
        application/x-www-form-urlencoded because that header is hardcoded for
        the MTProto carrier it was written for - never form-decode them.
        """
        encoding = (self.headers.get("Transfer-Encoding") or "").lower()
        if "chunked" in encoding:
            return self.read_chunked()

        raw_length = self.headers.get("Content-Length")
        if raw_length is not None:
            try:
                length = int(raw_length.strip())
            except ValueError:
                raise BodyUnreadable("Content-Length %r is not a number" % raw_length)
            if length > MAX_BODY:
                raise BodyTooLarge()
            if length < 0:
                raise BodyUnreadable("negative Content-Length")
            return self.rfile.read(length) if length else b""

        return self.read_to_eof()

    def read_chunked(self):
        body = b""
        while True:
            line = self.rfile.readline(64)
            if not line:
                raise BodyUnreadable("truncated chunk header")
            # A chunk size may carry extensions after a ';'.
            token = line.split(b";", 1)[0].strip()
            try:
                size = int(token, 16)
            except ValueError:
                raise BodyUnreadable("bad chunk size %r" % token[:32])
            if size == 0:
                # Consume the trailer, then stop.
                while True:
                    trailer = self.rfile.readline(256)
                    if not trailer or trailer in (b"\r\n", b"\n"):
                        break
                return body
            if len(body) + size > MAX_BODY:
                raise BodyTooLarge()
            body += self.rfile.read(size)
            self.rfile.read(2)  # trailing CRLF

    def read_to_eof(self):
        """
        Last resort: take what the socket gives us until it closes.

        Only reachable from a client that sends a body with neither a length
        nor chunked framing, which is malformed HTTP - the realistic handset
        cases are the two above. It is here so such a client gets its data
        stored instead of a rejection.

        A body delimited only by end-of-stream can be recognised solely by the
        peer closing its write side. A client that cannot half-close - which
        includes MIDP's HttpConnection, since it needs the same socket to read
        the response - will therefore always pay the timeout. Kept short for
        that reason: a slow answer on a metered GPRS link is nearly as bad as
        no answer, and this path should be rare enough that five seconds is an
        acceptable worst case.
        """
        self.connection.settimeout(5)
        body = b""
        try:
            while len(body) <= MAX_BODY:
                chunk = self.rfile.read1(4096) if hasattr(self.rfile, "read1") \
                        else self.rfile.read(4096)
                if not chunk:
                    break
                body += chunk
        except (socket.timeout, OSError):
            # Whatever arrived before the stall is still worth storing: a
            # partial report beats no report when the handset may not survive
            # long enough to send another.
            pass
        if len(body) > MAX_BODY:
            raise BodyTooLarge()
        return body

    def header_summary(self):
        """Enough of the request to tell where a body went, with no payload."""
        interesting = ("Content-Length", "Transfer-Encoding", "Content-Type",
                       "User-Agent", "Via", "X-Forwarded-For", "Connection",
                       "Expect")
        parts = ["%s %s" % (self.command, self.request_version)]
        for name in interesting:
            value = self.headers.get(name)
            if value is not None:
                parts.append("%s=%s" % (name, value))
        return " ".join(parts)

    def split_path(self):
        path = self.path.split("?", 1)[0]
        return [segment for segment in path.split("/") if segment]

    def deny(self):
        # Same response for a bad token, a bad path and a missing file: a
        # public listener should not confirm which of the three it was.
        self.send_text(404, "not found\n")

    def send_text(self, code, text):
        payload = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            self.wfile.write(payload)
        except OSError:
            pass


class LineHandler(socketserver.BaseRequestHandler):
    """tg.plat.TcpLogSink speaks this: a greeting line, then one line per log line."""

    store = None
    token = ""
    limiter = None

    def handle(self):
        ip = self.client_address[0]
        peer = "%s:%d" % self.client_address
        if not self.limiter.acquire(ip):
            log("tcp %s refused: too many connections" % peer)
            return
        try:
            self.pump(peer, ip)
        finally:
            self.limiter.release(ip)

    def pump(self, peer, ip):
        self.request.settimeout(TCP_IDLE_TIMEOUT)
        buffer = b""
        greeted = False
        target = device = None
        collected = []
        received = 0

        try:
            while True:
                chunk = self.request.recv(4096)
                if not chunk:
                    break
                received += len(chunk)
                if received > MAX_BODY:
                    log("tcp %s dropped: over %d bytes" % (peer, MAX_BODY))
                    break
                buffer += chunk

                while b"\n" in buffer:
                    raw, buffer = buffer.split(b"\n", 1)
                    line = raw.decode("utf-8", "replace").rstrip("\r")
                    if not greeted:
                        parsed = self.parse_greeting(line)
                        if parsed is None:
                            log("tcp %s refused: bad greeting" % peer)
                            return
                        target, device = parsed
                        greeted = True
                        log("tcp %s opened target=%s device=%s" % (peer, target, device))
                        continue
                    collected.append(line[:MAX_LINE])

                if len(buffer) > MAX_LINE:
                    collected.append(buffer[:MAX_LINE].decode("utf-8", "replace"))
                    collected.append("  [line exceeded %d bytes, truncated]" % MAX_LINE)
                    buffer = b""
        except socket.timeout:
            collected.append("---- idle timeout ----")
        except OSError as exc:
            collected.append("---- error: %s ----" % exc)
        finally:
            if buffer and greeted:
                collected.append(buffer.decode("utf-8", "replace"))
            if greeted and collected:
                rel = self.store.append(target, device, "\n".join(collected),
                                        ip, "tcp")
                log("stored %s (%d lines) from %s" % (rel, len(collected), peer))

    def parse_greeting(self, line):
        fields = line.split()
        if len(fields) != 3:
            return None
        given, target, device = fields
        if not token_ok(self.token, given):
            return None
        if not SAFE_NAME.match(target) or not SAFE_NAME.match(device):
            return None
        return target, device


class ThreadedLineServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def env_int(name, fallback):
    try:
        return int(os.environ.get(name, "")) or fallback
    except ValueError:
        return fallback


def selftest():
    """
    Prove the server accepts a body however a handset chose to frame it.

    This exists because it already went wrong once: the first version read
    Content-Length and nothing else, and a Samsung GT-C3590 uploading over a
    carrier APN got 413 on every single report. The status code was all the
    phone could show, and 413 pointed at the body cap, which was never the
    problem. Whatever the next handset does, it will be one of these shapes.
    """
    import tempfile
    import threading

    failures = []

    def check(label, request, expect, half_close=False):
        client = socket.create_connection(("127.0.0.1", port), timeout=10)
        client.sendall(request)
        if half_close:
            # The only way to delimit a body that carries no length: the server
            # cannot tell "done" from "slow" without seeing end-of-stream.
            client.shutdown(socket.SHUT_WR)
        client.settimeout(10)
        data = b""
        try:
            while True:
                piece = client.recv(4096)
                if not piece:
                    break
                data += piece
        except socket.timeout:
            pass
        client.close()

        status = 0
        first = data.split(b"\r\n", 1)[0].decode("ascii", "replace")
        parts = first.split()
        if len(parts) > 1 and parts[1].isdigit():
            status = int(parts[1])
        ok = status == expect
        if not ok:
            failures.append("%s: expected %d, got %d" % (label, expect, status))
        print("   %-34s %s (%d)" % (label, "OK" if ok else "FAIL", status))

    token = "selftest-token"
    path = "/r/%s/probe/selftest" % token
    body = b"target line\nmeasurement = 42\n"

    with tempfile.TemporaryDirectory() as data_dir:
        ReportHandler.store = Store(data_dir, 8 * 1024 * 1024)
        ReportHandler.token = token
        ReportHandler.limiter = ConnectionLimiter(MAX_CONNS_PER_IP)

        server = ThreadingHTTPServer(("127.0.0.1", 0), ReportHandler)
        server.daemon_threads = True
        port = server.server_address[1]
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        print("body framing:")
        check("Content-Length",
              ("POST %s HTTP/1.1\r\nHost: x\r\nContent-Length: %d\r\n"
               "Connection: close\r\n\r\n" % (path, len(body))).encode() + body, 200)
        check("chunked",
              ("POST %s HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n"
               "Connection: close\r\n\r\n" % path).encode()
              + b"%x\r\n%s\r\n0\r\n\r\n" % (len(body), body), 200)
        check("no length, client half-closes",
              ("POST %s HTTP/1.0\r\nHost: x\r\nConnection: close\r\n\r\n"
               % path).encode() + body, 200, half_close=True)
        check("empty body -> 411 not 413",
              ("POST %s HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n"
               "Connection: close\r\n\r\n" % path).encode(), 411)
        check("oversize -> 413",
              ("POST %s HTTP/1.1\r\nHost: x\r\nContent-Length: %d\r\n"
               "Connection: close\r\n\r\n" % (path, MAX_BODY + 1)).encode(), 413)

        print("access control:")
        check("wrong token -> 404",
              ("POST /r/wrong/probe/x HTTP/1.1\r\nHost: x\r\nContent-Length: %d\r\n"
               "Connection: close\r\n\r\n" % len(body)).encode() + body, 404)
        check("path traversal -> 404",
              ("POST /r/%s/probe/..%%2f..%%2fetc HTTP/1.1\r\nHost: x\r\n"
               "Content-Length: %d\r\nConnection: close\r\n\r\n"
               % (token, len(body))).encode() + body, 404)
        check("healthz needs no token",
              ("GET /healthz HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").encode(), 200)
        check("index needs the token",
              ("GET /r/wrong/ HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").encode(), 404)

        server.shutdown()
        server.server_close()

    if failures:
        print("\nFAILED:")
        for line in failures:
            print("   " + line)
        return 1
    print("\nselftest passed")
    return 0


def main(argv):
    # A Windows console commonly uses cp1251/cp866 while a report may contain
    # any Unicode character. Never tear down a session over one glyph.
    try:
        sys.stdout.reconfigure(errors="backslashreplace")
    except (AttributeError, OSError):
        pass

    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default=os.environ.get("INGEST_HOST", "0.0.0.0"))
    ap.add_argument("--http-port", type=int, default=env_int("INGEST_HTTP_PORT", 8080))
    ap.add_argument("--tcp-port", type=int, default=env_int("INGEST_TCP_PORT", 8443))
    ap.add_argument("--data", default=os.environ.get("INGEST_DATA", "probe-reports"))
    ap.add_argument("--token", default=os.environ.get("INGEST_TOKEN", ""),
                    help="shared secret; required, and never logged")
    ap.add_argument("--max-total-mb", type=int,
                    default=env_int("INGEST_MAX_TOTAL_MB", DEFAULT_MAX_TOTAL_MB))
    ap.add_argument("--selftest", action="store_true",
                    help="check request handling against an in-process server and exit")
    args = ap.parse_args(argv[1:])

    if args.selftest:
        return selftest()

    if not args.token or len(args.token) < 8:
        # There is no network-level protection in front of this on a public
        # address, so an empty or trivial token is a misconfiguration, not a
        # convenience.
        print("refusing to start: --token (or INGEST_TOKEN) must be >= 8 characters",
              file=sys.stderr, flush=True)
        return 2

    store = Store(args.data, args.max_total_mb * 1024 * 1024)
    limiter = ConnectionLimiter(MAX_CONNS_PER_IP)

    ReportHandler.store = store
    ReportHandler.token = args.token
    ReportHandler.limiter = limiter
    LineHandler.store = store
    LineHandler.token = args.token
    LineHandler.limiter = limiter

    http_server = ThreadingHTTPServer((args.host, args.http_port), ReportHandler)
    http_server.daemon_threads = True
    http_server.timeout = HTTP_IDLE_TIMEOUT
    tcp_server = ThreadedLineServer((args.host, args.tcp_port), LineHandler)

    log("ingest-server (development only - carries no Telegram traffic)")
    log("http  %s:%d   POST /r/<token>/<target>/<device>" % (args.host, args.http_port))
    log("tcp   %s:%d   greeting line: <token> <target> <device>" % (args.host, args.tcp_port))
    log("data  %s  (cap %d MB, evicts oldest first)" % (store.root, args.max_total_mb))

    http_thread = threading.Thread(target=http_server.serve_forever,
                                   name="http", daemon=True)
    http_thread.start()
    try:
        tcp_server.serve_forever()
    except KeyboardInterrupt:
        log("stopping")
    finally:
        tcp_server.shutdown()
        tcp_server.server_close()
        http_server.shutdown()
        http_server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
