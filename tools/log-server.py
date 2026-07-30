#!/usr/bin/env python3
"""
Development-only remote log collector.

The handset has no console and no debugger. `tg.plat.TcpLogSink` can stream the
diagnostic ring to this server over a plain TCP socket so a crash on the phone
is readable on the development machine as it happens.

Like tools/echo-server.py, this MUST NEVER touch Telegram protocol traffic. It
receives already-formatted diagnostic text, one line per newline, and writes it
to stdout and optionally to a file. It implements nothing.

Usage:
    python tools/log-server.py
    python tools/log-server.py --port 7778 --out probe-reports/device.log
"""

import argparse
import datetime
import os
import socket
import socketserver
import sys
import threading

# A device that goes wrong can spew; cap what we accept per line so a runaway
# MIDlet cannot exhaust this process's memory either.
MAX_LINE = 4096


class LogHandler(socketserver.BaseRequestHandler):
    out_file = None
    lock = threading.Lock()

    def handle(self):
        peer = "%s:%d" % self.client_address
        self.emit("---- connected: %s ----" % peer)
        buffer = b""
        try:
            self.request.settimeout(900)
            while True:
                chunk = self.request.recv(4096)
                if not chunk:
                    break
                buffer += chunk
                while b"\n" in buffer:
                    line, buffer = buffer.split(b"\n", 1)
                    self.emit(line.decode("utf-8", "replace").rstrip("\r"))
                if len(buffer) > MAX_LINE:
                    self.emit(buffer[:MAX_LINE].decode("utf-8", "replace"))
                    self.emit("  [line exceeded %d bytes, truncated]" % MAX_LINE)
                    buffer = b""
        except socket.timeout:
            self.emit("---- idle timeout: %s ----" % peer)
        except OSError as exc:
            self.emit("---- error: %s: %s ----" % (peer, exc))
        finally:
            if buffer:
                self.emit(buffer.decode("utf-8", "replace"))
            self.emit("---- disconnected: %s ----" % peer)

    def emit(self, text):
        stamped = "%s  %s" % (datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3], text)
        with LogHandler.lock:
            print(stamped, flush=True)
            if LogHandler.out_file:
                LogHandler.out_file.write(stamped + "\n")
                LogHandler.out_file.flush()


class ThreadedServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main(argv):
    # A Windows console commonly uses cp1251/cp866 while Telegram names may
    # contain any Unicode character. Diagnostics must never tear down the TCP
    # session merely because stdout cannot represent one glyph.
    try:
        sys.stdout.reconfigure(errors="backslashreplace")
    except (AttributeError, OSError):
        pass

    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=7778)
    ap.add_argument("--out", help="also append to this file")
    args = ap.parse_args(argv[1:])

    if args.out:
        directory = os.path.dirname(args.out)
        if directory:
            os.makedirs(directory, exist_ok=True)
        LogHandler.out_file = open(args.out, "a", encoding="utf-8")

    server = ThreadedServer((args.host, args.port), LogHandler)
    print("log-server on %s:%d  (development only - carries no Telegram traffic)"
          % (args.host, args.port), flush=True)
    if args.out:
        print("appending to %s" % args.out, flush=True)
    print("Ctrl-C to stop", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopping", flush=True)
    finally:
        server.shutdown()
        server.server_close()
        if LogHandler.out_file:
            LogHandler.out_file.close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
