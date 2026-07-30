#!/usr/bin/env python3
"""
Port the Bouncy Castle CLDC BigInteger into this project.

Upstream:  bcgit/bc-java  core/src/main/j2me/java/math/BigInteger.java
Pinned by: tools/sdk.lock.json  (commit + sha256)
Input:     third_party/bc/BigInteger.java.orig   (downloaded by bootstrap.ps1)
Output:    src/tg/crypto/bigint/BigInteger.java

Three things must change for this to be legal and loadable on a real handset:

  1. package java.math -> tg.crypto.bigint
     The MIDP 2.0 packaging rules forbid classes in java.* / javax.* inside a
     MIDlet suite; the AMS on the phone rejects the whole JAR. The class name
     itself is kept so the upstream diff stays reviewable.

  2. org.bouncycastle.util.{Arrays,Integers} -> three private static helpers.
     Pulling in the BC utility tree for four call sites is not worth the JAR
     bytes.

  3. java.security.SecureRandom -> removed.
     CLDC has no java.security at all. Strong randomness comes from
     tg.crypto.Rng, which subclasses java.util.Random and overrides nextInt(),
     so the generic nextInt()-driven path in nextRndBytes() is the only path.

Every substitution asserts its expected hit count, so an upstream change that
silently invalidates an assumption fails the build instead of producing subtly
wrong crypto code.
"""

import hashlib
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SRC = REPO / "third_party" / "bc" / "BigInteger.java.orig"
DST = REPO / "src" / "tg" / "crypto" / "bigint" / "BigInteger.java"
UPSTREAM_MD = REPO / "third_party" / "bc" / "UPSTREAM.md"

UPSTREAM_REPO = "https://github.com/bcgit/bc-java"
UPSTREAM_COMMIT = "31a2228b4e4b314c4c80e72cb578915f6b919dec"
UPSTREAM_PATH = "core/src/main/j2me/java/math/BigInteger.java"

HELPERS = """
    // ---------------------------------------------------------------------
    // PORT: replacements for org.bouncycastle.util.{Arrays,Integers}. Keeping
    // the BC utility classes would drag in unrelated code for four call sites.
    // ---------------------------------------------------------------------

    private static int[] pgClone(int[] data)
    {
        if (data == null)
        {
            return null;
        }
        int[] copy = new int[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        return copy;
    }

    private static void pgFill(int[] a, int val)
    {
        for (int i = 0; i < a.length; i++)
        {
            a[i] = val;
        }
    }

    private static int pgCompareUnsigned(int x, int y)
    {
        if (x == y)
        {
            return 0;
        }
        return ((x ^ 0x80000000) < (y ^ 0x80000000)) ? -1 : 1;
    }
"""

SECURERANDOM_OLD = """        if (rnd instanceof java.security.SecureRandom)
        {
            ((java.security.SecureRandom)rnd).nextBytes(bytes);
        }
        else
        {
            for (; ; )"""

SECURERANDOM_NEW = """        // PORT: CLDC has no java.security.SecureRandom. tg.crypto.Rng subclasses
        // java.util.Random and overrides nextInt(), so the generic path below
        // is fed by our own entropy pool and is the only path.
        {
            for (; ; )"""


def fail(msg):
    print("port-bc-bigint: ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def sub_exact(text, old, new, count, what):
    """Replace and assert the number of occurrences, so upstream drift is loud."""
    n = text.count(old)
    if n != count:
        fail("expected %d occurrence(s) of %s, found %d - upstream changed, "
             "review third_party/bc/BigInteger.java.orig" % (count, what, n))
    return text.replace(old, new)


def main():
    if not SRC.exists():
        fail("missing %s - run tools/bootstrap.ps1 first" % SRC)

    raw = SRC.read_bytes()
    src_sha = hashlib.sha256(raw).hexdigest()
    text = raw.decode("utf-8")

    # 1. package
    text = sub_exact(text, "package java.math;", "package tg.crypto.bigint;",
                     1, "package declaration")

    # 2. BC utility imports
    text = sub_exact(text, "import org.bouncycastle.util.Arrays;\n", "",
                     1, "Arrays import")
    text = sub_exact(text, "import org.bouncycastle.util.Integers;\n", "",
                     1, "Integers import")
    text = sub_exact(text, "Arrays.clone(", "pgClone(", 3, "Arrays.clone call")
    text = sub_exact(text, "Arrays.fill(", "pgFill(", 1, "Arrays.fill call")
    text = sub_exact(text, "Integers.compareUnsigned(", "pgCompareUnsigned(",
                     1, "Integers.compareUnsigned call")

    # 3. SecureRandom
    text = sub_exact(text, SECURERANDOM_OLD, SECURERANDOM_NEW,
                     1, "SecureRandom instanceof block")

    # 4. inject helpers right after the class opening brace
    m = re.search(r"public class BigInteger\s*\n\{\n", text)
    if not m:
        fail("could not locate the class opening brace")
    text = text[:m.end()] + HELPERS + text[m.end():]

    # 5. header
    header = (
        "/*\n"
        " * Vendored from Bouncy Castle - DO NOT EDIT BY HAND.\n"
        " * Regenerate with:  python tools/port-bc-bigint.py\n"
        " *\n"
        " * upstream repo   : %s\n"
        " * upstream commit : %s\n"
        " * upstream path   : %s\n"
        " * upstream sha256 : %s\n"
        " * licence         : Bouncy Castle Licence (MIT-style),"
        " see third_party/bc/LICENSE.html\n"
        " *\n"
        " * Local changes are limited to CLDC 1.1 compatibility and are listed\n"
        " * in third_party/bc/UPSTREAM.md. Each one is marked with 'PORT:'.\n"
        " */\n"
    ) % (UPSTREAM_REPO, UPSTREAM_COMMIT, UPSTREAM_PATH, src_sha)
    text = header + text

    # 6. paranoia: nothing forbidden may survive in actual code. Comments may
    #    legitimately name the APIs we removed, so strip them before checking.
    code_only = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    code_only = re.sub(r"//[^\n]*", "", code_only)
    for forbidden in ("java.security", "org.bouncycastle", "package java."):
        if forbidden in code_only:
            fail("forbidden reference '%s' still present after porting" % forbidden)

    DST.parent.mkdir(parents=True, exist_ok=True)
    DST.write_text(text, encoding="utf-8", newline="\n")

    UPSTREAM_MD.write_text(
        "# Vendored third-party code\n\n"
        "## Bouncy Castle `BigInteger` (CLDC build)\n\n"
        "| | |\n|---|---|\n"
        "| Repository | %s |\n"
        "| Commit | `%s` |\n"
        "| Path | `%s` |\n"
        "| SHA-256 of original | `%s` |\n"
        "| Licence | Bouncy Castle Licence (MIT-style) - `LICENSE.html` |\n\n"
        "`java.math.BigInteger` does not exist in CLDC 1.1, and MTProto needs "
        "2048-bit modular exponentiation for the Diffie-Hellman step of "
        "`auth_key` generation. Bouncy Castle maintains a CLDC-targeted "
        "implementation with Montgomery reduction and sliding-window `modPow`, "
        "which is what we use.\n\n"
        "### Local changes\n\n"
        "Applied mechanically by `tools/port-bc-bigint.py`; every one is marked "
        "`PORT:` in the generated source. Never edit "
        "`src/tg/crypto/bigint/BigInteger.java` by hand.\n\n"
        "1. **`package java.math` -> `package tg.crypto.bigint`.** MIDP 2.0 "
        "forbids `java.*` classes inside a MIDlet suite; the phone's AMS "
        "rejects the JAR outright.\n"
        "2. **`org.bouncycastle.util.Arrays` / `Integers` -> three private "
        "static helpers** (`pgClone`, `pgFill`, `pgCompareUnsigned`). Five call "
        "sites did not justify vendoring the BC utility tree.\n"
        "3. **`java.security.SecureRandom` removed.** CLDC has no "
        "`java.security`. `tg.crypto.Rng` subclasses `java.util.Random` and "
        "overrides `nextInt()`, so the generic path in `nextRndBytes()` draws "
        "from our own entropy pool.\n\n"
        "The upstream file uses no `double`/`float`, no generics and no "
        "post-1.3 language features, so it compiles cleanly at "
        "`-source 1.3 -target 1.1`.\n"
        % (UPSTREAM_REPO, UPSTREAM_COMMIT, UPSTREAM_PATH, src_sha),
        encoding="utf-8", newline="\n")

    print("    OK   ported -> src/tg/crypto/bigint/BigInteger.java (%d lines)"
          % text.count("\n"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
