#!/usr/bin/env python3
"""
Development-only OTA provisioning server.

Serves dist/ over plain HTTP so a handset can install the MIDlet suite over the
air. Once basic installation works, this is the fastest deployment loop: rebuild
and reinstall from the phone's browser with no cable and no card.

Plain HTTP on purpose. The TLS stack and certificate store on a 2011 feature
phone are not a dependency worth taking for a development path, and the client
itself never uses HTTPS - MTProto runs over raw TCP.

The JAD is served as text/vnd.sun.j2me.app-descriptor and the JAR as
application/java-archive; an AMS that receives the wrong content type will
usually just download the file instead of installing it.

Before serving, the JAD's MIDlet-Jar-Size is checked against the JAR on disk.
A mismatch is the single most common reason an install aborts with a useless
error, so it fails loudly here instead.

Usage:
    python tools/serve-ota.py
    python tools/serve-ota.py --port 8080 --dir dist
Then browse on the phone to  http://<this-machine>:8080/probe.jad
"""

import argparse
import http.server
import os
import re
import socket
import sys

MIME = {
    ".jad": "text/vnd.sun.j2me.app-descriptor",
    ".jar": "application/java-archive",
}


class OtaHandler(http.server.SimpleHTTPRequestHandler):
    def guess_type(self, path):
        ext = os.path.splitext(path)[1].lower()
        if ext in MIME:
            return MIME[ext]
        return super().guess_type(path)

    def end_headers(self):
        # Some AMS implementations refuse a chunked or cached descriptor.
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, fmt, *args):
        print("  %s - %s" % (self.client_address[0], fmt % args), flush=True)


def verify_descriptors(directory):
    """A JAD whose MIDlet-Jar-Size disagrees with the JAR will not install."""
    ok = True
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".jad"):
            continue
        jad_path = os.path.join(directory, name)
        with open(jad_path, "r", encoding="ascii", errors="replace") as fh:
            text = fh.read()

        url = re.search(r"^MIDlet-Jar-URL:\s*(.+?)\s*$", text, re.M)
        size = re.search(r"^MIDlet-Jar-Size:\s*(\d+)\s*$", text, re.M)
        if not url or not size:
            print("  %s: missing MIDlet-Jar-URL or MIDlet-Jar-Size" % name)
            ok = False
            continue

        jar_path = os.path.join(directory, os.path.basename(url.group(1)))
        if not os.path.exists(jar_path):
            print("  %s: references %s which is not here" % (name, url.group(1)))
            ok = False
            continue

        actual = os.path.getsize(jar_path)
        declared = int(size.group(1))
        if actual != declared:
            print("  %s: MIDlet-Jar-Size %d but %s is %d bytes - rebuild"
                  % (name, declared, os.path.basename(jar_path), actual))
            ok = False
        else:
            print("  %s -> %s  (%d bytes, size matches)"
                  % (name, os.path.basename(jar_path), actual))
    return ok


def local_addresses():
    out = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 53))
        out.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(out) or ["<this machine>"]


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--dir", default="dist")
    args = ap.parse_args(argv[1:])

    directory = os.path.abspath(args.dir)
    if not os.path.isdir(directory):
        print("no such directory: %s" % directory, file=sys.stderr)
        return 2

    print("checking descriptors in %s" % directory, flush=True)
    if not verify_descriptors(directory):
        print("\nrefusing to serve: fix the above, then ./tools/build.ps1",
              file=sys.stderr)
        return 1

    handler = lambda *a, **kw: OtaHandler(*a, directory=directory, **kw)
    server = http.server.ThreadingHTTPServer((args.host, args.port), handler)

    print("\nOTA server on %s:%d (plain HTTP, development only)"
          % (args.host, args.port), flush=True)
    for addr in local_addresses():
        for name in sorted(os.listdir(directory)):
            if name.endswith(".jad"):
                print("  http://%s:%d/%s" % (addr, args.port, name), flush=True)
    print("Ctrl-C to stop", flush=True)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopping", flush=True)
    finally:
        server.shutdown()
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
