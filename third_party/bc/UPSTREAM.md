# Vendored third-party code

## Bouncy Castle `BigInteger` (CLDC build)

| | |
|---|---|
| Repository | https://github.com/bcgit/bc-java |
| Commit | `31a2228b4e4b314c4c80e72cb578915f6b919dec` |
| Path | `core/src/main/j2me/java/math/BigInteger.java` |
| SHA-256 of original | `5b7a6aeef84de2386c9cfdf71103780a32c6d3be9d3650a3c393eae98a663803` |
| Licence | Bouncy Castle Licence (MIT-style) - `LICENSE.html` |

`java.math.BigInteger` does not exist in CLDC 1.1, and MTProto needs 2048-bit modular exponentiation for the Diffie-Hellman step of `auth_key` generation. Bouncy Castle maintains a CLDC-targeted implementation with Montgomery reduction and sliding-window `modPow`, which is what we use.

### Local changes

Applied mechanically by `tools/port-bc-bigint.py`; every one is marked `PORT:` in the generated source. Never edit `src/tg/crypto/bigint/BigInteger.java` by hand.

1. **`package java.math` -> `package tg.crypto.bigint`.** MIDP 2.0 forbids `java.*` classes inside a MIDlet suite; the phone's AMS rejects the JAR outright.
2. **`org.bouncycastle.util.Arrays` / `Integers` -> three private static helpers** (`pgClone`, `pgFill`, `pgCompareUnsigned`). Five call sites did not justify vendoring the BC utility tree.
3. **`java.security.SecureRandom` removed.** CLDC has no `java.security`. `tg.crypto.Rng` subclasses `java.util.Random` and overrides `nextInt()`, so the generic path in `nextRndBytes()` draws from our own entropy pool.

The upstream file uses no `double`/`float`, no generics and no post-1.3 language features, so it compiles cleanly at `-source 1.3 -target 1.1`.
