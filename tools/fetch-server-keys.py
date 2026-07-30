#!/usr/bin/env python3
"""
Generate src/tg/mt/ServerKeys.java from the official Telegram Desktop source.

Why this exists
---------------
The auth_key handshake encrypts p_q_inner_data to one of Telegram's server RSA
public keys, chosen by matching the fingerprints the server returns in resPQ.
A client that does not hold the matching key cannot authorize at all.

Telegram used to publish the key in core.telegram.org/mtproto/auth_key; it no
longer does. The keys now live only in official client source. Telegram Desktop
is official and open source (GPLv3), and a *public* key is public by
construction - it ships inside every Telegram binary on earth. Taking it from
there is what every third-party client does.

Production and test data centres use different keys. Mixing them up produces an
RSA blob the server silently rejects, so both are generated and tagged.

Output is committed: these are public keys, unlike api_id/api_hash.

Usage:
    python tools/fetch-server-keys.py
    python tools/fetch-server-keys.py --ref <commit>     # pin a different commit
"""

import argparse
import base64
import hashlib
import re
import struct
import sys
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "src" / "tg" / "mt" / "ServerKeys.java"

UPSTREAM_REPO = "telegramdesktop/tdesktop"
UPSTREAM_PATH = "Telegram/SourceFiles/mtproto/mtproto_dc_options.cpp"
DEFAULT_REF = "dev"

RAW = "https://raw.githubusercontent.com/%s/%s/%s"
API_COMMITS = "https://api.github.com/repos/%s/commits?path=%s&sha=%s&per_page=1"


def fail(msg):
    print("fetch-server-keys: ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def http_get(url, accept=None):
    req = urllib.request.Request(url, headers={"User-Agent": "j2me-mtproto-client"})
    if accept:
        req.add_header("Accept", accept)
    with urllib.request.urlopen(req, timeout=60) as fh:
        return fh.read()


# --------------------------------------------------------------------------
# DER / PKCS#1
# --------------------------------------------------------------------------

def der_read_len(data, pos):
    first = data[pos]
    pos += 1
    if first < 0x80:
        return first, pos
    count = first & 0x7F
    if count == 0 or count > 4:
        fail("unsupported DER length form")
    value = int.from_bytes(data[pos:pos + count], "big")
    return value, pos + count


def parse_pkcs1_public_key(pem):
    """PKCS#1 RSAPublicKey ::= SEQUENCE { modulus INTEGER, exponent INTEGER }."""
    body = "".join(line.strip() for line in pem.splitlines()
                   if "-----" not in line and line.strip())
    der = base64.b64decode(body)

    pos = 0
    if der[pos] != 0x30:
        fail("expected a DER SEQUENCE at the start of the key")
    pos += 1
    _, pos = der_read_len(der, pos)

    values = []
    for _ in range(2):
        if der[pos] != 0x02:
            fail("expected a DER INTEGER")
        pos += 1
        length, pos = der_read_len(der, pos)
        raw = der[pos:pos + length]
        pos += length
        # DER pads with a leading 0x00 to keep the integer positive; MTProto
        # fingerprints the bare magnitude, so strip it.
        values.append(raw.lstrip(b"\x00"))
    return values[0], values[1]          # n, e


# --------------------------------------------------------------------------
# MTProto fingerprint
# --------------------------------------------------------------------------

def tl_bytes(data):
    """TL `string`/`bytes`: length prefix then the payload, padded to 4 bytes."""
    out = bytearray()
    if len(data) < 254:
        out.append(len(data))
    else:
        out.append(254)
        out += len(data).to_bytes(3, "little")
    out += data
    while len(out) % 4:
        out.append(0)
    return bytes(out)


def fingerprint(n, e):
    """
    Lower 64 bits of SHA1 over the TL-serialised bare type

        rsa_public_key n:string e:string = RSAPublicKey

    read as a little-endian signed int64, which is what resPQ carries.
    """
    digest = hashlib.sha1(tl_bytes(n) + tl_bytes(e)).digest()
    return struct.unpack("<q", digest[-8:])[0]


# --------------------------------------------------------------------------
# Extraction
# --------------------------------------------------------------------------

def extract_key_arrays(source):
    """Pull the C string literals out of kPublicRSAKeys / kTestPublicRSAKeys."""
    out = {}
    for var, label in (("kPublicRSAKeys", "production"),
                       ("kTestPublicRSAKeys", "test")):
        match = re.search(re.escape(var) + r"\[\]\s*=\s*\{(.*?)\};", source, re.S)
        if not match:
            fail("could not find %s in the upstream source" % var)
        block = match.group(1)
        # C line continuations: "\<newline> and escaped \n
        cleaned = block.replace("\\\n", "").replace('\\n', "\n").replace('"', "")
        pems = re.findall(r"-----BEGIN RSA PUBLIC KEY-----.*?-----END RSA PUBLIC KEY-----",
                          cleaned, re.S)
        if not pems:
            fail("no PEM blocks inside %s" % var)
        out[label] = pems
    return out


def extract_dcs(source):
    """Built-in bootstrap DC addresses, so our table matches the official one."""
    out = {}
    for var, label in (("kBuiltInDcs", "production"), ("kBuiltInDcsTest", "test")):
        match = re.search(re.escape(var) + r"\[\]\s*=\s*\{(.*?)\};", source, re.S)
        if not match:
            continue
        entries = re.findall(r"\{\s*(\d+)\s*,\s*\"([0-9.]+)\"\s*,\s*(\d+)\s*\}",
                             match.group(1))
        out[label] = [(int(d), ip, int(p)) for d, ip, p in entries]
    return out


def java_hex_chunks(data, per_line=32):
    """Split a hex string into quoted Java literals short enough to read."""
    text = data.hex()
    lines = [text[i:i + per_line * 2] for i in range(0, len(text), per_line * 2)]
    return lines


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ref", default=DEFAULT_REF)
    args = ap.parse_args(argv[1:])

    # Resolve the branch to a commit so the output is reproducible.
    ref = args.ref
    try:
        import json
        info = json.loads(http_get(API_COMMITS % (UPSTREAM_REPO, UPSTREAM_PATH, ref),
                                   accept="application/vnd.github+json"))
        commit = info[0]["sha"]
        commit_date = info[0]["commit"]["author"]["date"]
    except Exception as exc:                             # noqa: BLE001
        print("  warning: could not resolve commit (%s); pinning by ref" % exc)
        commit, commit_date = ref, "unknown"

    source = http_get(RAW % (UPSTREAM_REPO, commit, UPSTREAM_PATH)).decode("utf-8")
    source_sha = hashlib.sha256(source.encode("utf-8")).hexdigest()

    keys = extract_key_arrays(source)
    dcs = extract_dcs(source)

    parsed = {}
    for env in ("production", "test"):
        parsed[env] = []
        for pem in keys[env]:
            n, e = parse_pkcs1_public_key(pem)
            fp = fingerprint(n, e)
            parsed[env].append((n, e, fp))
            print("  %-10s %4d-bit  e=%s  fingerprint=%d (0x%016x)"
                  % (env, len(n) * 8, int.from_bytes(e, "big"), fp, fp & 0xFFFFFFFFFFFFFFFF))

    for env, entries in dcs.items():
        print("  %-10s bootstrap DCs: %s" % (env, ", ".join(
            "dc%d=%s:%d" % t for t in entries)))

    emit_java(parsed, commit, commit_date, source_sha)
    print("  wrote %s" % OUT.relative_to(REPO))
    return 0


def emit_java(parsed, commit, commit_date, source_sha):
    lines = []
    a = lines.append

    a("package tg.mt;")
    a("")
    a("/**")
    a(" * Telegram server RSA public keys - GENERATED, do not edit by hand.")
    a(" *")
    a(" * Regenerate with:  python tools/fetch-server-keys.py")
    a(" *")
    a(" * Source: %s" % UPSTREAM_REPO)
    a(" *         %s" % UPSTREAM_PATH)
    a(" *         commit %s (%s)" % (commit, commit_date))
    a(" *         sha256 %s" % source_sha)
    a(" *")
    a(" * Telegram stopped publishing these in the documentation; they now live only")
    a(" * in official client source. A public key is public by construction - it ships")
    a(" * inside every Telegram binary - so unlike api_id/api_hash this file is")
    a(" * committed.")
    a(" *")
    a(" * The auth_key handshake encrypts p_q_inner_data to whichever of these the")
    a(" * server names in resPQ.server_public_key_fingerprints. Production and test")
    a(" * data centres use DIFFERENT keys; using the wrong one yields a blob the")
    a(" * server rejects without a useful error.")
    a(" *")
    a(" * Fingerprints are not stored: {@link RsaKey} recomputes them from the")
    a(" * modulus and exponent, so a transcription error cannot go unnoticed.")
    a(" */")
    a("public final class ServerKeys")
    a("{")
    a("    /** Public exponent, 65537 for every key Telegram publishes. */")
    a("    public static final String EXPONENT = \"010001\";")
    a("")

    for env in ("production", "test"):
        const = env.upper()
        a("    /** %s data centres. */" % env)
        a("    public static final String[] %s_MODULUS = {" % const)
        for (n, e, fp) in parsed[env]:
            exp_hex = e.hex()
            a("        // fingerprint %d, exponent 0x%s" % (fp, exp_hex))
            chunks = java_hex_chunks(n)
            for i, chunk in enumerate(chunks):
                suffix = "" if i == len(chunks) - 1 else " +"
                prefix = "        \"" if i == 0 else "        \""
                a("%s%s\"%s" % (prefix, chunk, suffix if suffix else ","))
        a("    };")
        a("")

    a("    private ServerKeys() { }")
    a("")
    a("    /** Moduli for the environment this build targets. */")
    a("    public static String[] moduli()")
    a("    {")
    a("        return Dc.isTest() ? TEST_MODULUS : PRODUCTION_MODULUS;")
    a("    }")
    a("}")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    sys.exit(main(sys.argv))
