#!/usr/bin/env python3
"""
Development-only TCP echo server.

Purpose: prove that a raw socket works end to end from the handset before any
Telegram code exists. It echoes bytes back verbatim and prints a hex dump, so
the exact bytes the phone's TCP stack put on the wire are visible.

This server MUST NEVER learn anything about MTProto. It does not relay Telegram
traffic, terminate crypto, or hold protocol state - the whole point of the
project is that the device owns all of that. It exists purely so that "can this
phone do TCP at all?" is answerable separately from "is our MTProto correct?".

Usage:
    python tools/echo-server.py                    # 0.0.0.0:7777
    python tools/echo-server.py --port 7777 --hex
    python tools/echo-server.py --delay 0.5        # simulate a slow peer

Then point the probe's "Raw TCP" screen at this machine's LAN address. From an
external mobile network the runtime needs a publicly reachable address, so a
port-forward or tunnel may be required.
"""

import argparse
import socket
import socketserver
import sys
import threading
import time

HEX_WIDTH = 16
START = time.time()


def stamp():
    return "%8.3f" % (time.time() - START)


def hexdump(data, prefix):
    lines = []
    for off in range(0, len(data), HEX_WIDTH):
        chunk = data[off:off + HEX_WIDTH]
        hexpart = " ".join("%02x" % b for b in chunk)
        textpart = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        lines.append("%s %s  %-*s  |%s|"
                     % (prefix, "%04x" % off, HEX_WIDTH * 3 - 1, hexpart, textpart))
    return "\n".join(lines)


class EchoHandler(socketserver.BaseRequestHandler):
    show_hex = False
    delay = 0.0

    def handle(self):
        peer = "%s:%d" % self.client_address
        thread = threading.current_thread().name
        print("%s [%s] CONNECT   %s" % (stamp(), thread, peer), flush=True)

        total_rx = 0
        total_tx = 0
        t0 = time.time()
        try:
            self.request.settimeout(300)
            while True:
                data = self.request.recv(4096)
                if not data:
                    break
                total_rx += len(data)
                print("%s [%s] RX %5d b from %s"
                      % (stamp(), thread, len(data), peer), flush=True)
                if self.show_hex:
                    print(hexdump(data, "        rx"), flush=True)

                if self.delay:
                    time.sleep(self.delay)

                self.request.sendall(data)
                total_tx += len(data)
                print("%s [%s] TX %5d b to   %s"
                      % (stamp(), thread, len(data), peer), flush=True)
        except socket.timeout:
            print("%s [%s] TIMEOUT   %s (300 s idle)" % (stamp(), thread, peer), flush=True)
        except ConnectionResetError:
            print("%s [%s] RESET     %s" % (stamp(), thread, peer), flush=True)
        except OSError as exc:
            print("%s [%s] ERROR     %s: %s" % (stamp(), thread, peer, exc), flush=True)
        finally:
            held = time.time() - t0
            print("%s [%s] CLOSE     %s  rx=%d tx=%d held=%.1fs"
                  % (stamp(), thread, peer, total_rx, total_tx, held), flush=True)


class ThreadedServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=7777)
    ap.add_argument("--hex", action="store_true", help="hex dump every payload")
    ap.add_argument("--delay", type=float, default=0.0,
                    help="seconds to wait before echoing, to test client timeouts")
    args = ap.parse_args(argv[1:])

    EchoHandler.show_hex = args.hex
    EchoHandler.delay = args.delay

    server = ThreadedServer((args.host, args.port), EchoHandler)
    print("echo-server on %s:%d  (development only - carries no Telegram traffic)"
          % (args.host, args.port), flush=True)
    print("local addresses: %s" % ", ".join(local_addresses()), flush=True)
    print("Ctrl-C to stop", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopping", flush=True)
    finally:
        server.shutdown()
        server.server_close()
    return 0


def local_addresses():
    """Best-effort list of addresses the phone might be able to reach."""
    out = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None):
            addr = info[4][0]
            if ":" not in addr:
                out.add(addr)
    except OSError:
        pass
    try:
        # The address that would be used to reach the internet - the one a
        # phone on the same LAN should target.
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 53))
        out.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(out) or ["unknown"]


if __name__ == "__main__":
    sys.exit(main(sys.argv))
