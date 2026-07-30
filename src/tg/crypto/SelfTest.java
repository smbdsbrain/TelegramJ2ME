package tg.crypto;

import java.util.Vector;

import tg.crypto.bigint.BigInteger;
import tg.io.Hex;

/**
 * Crypto self-test and benchmark that runs <em>on the device</em>.
 *
 * The handoff is explicit that a vector matching on the desktop proves nothing
 * about the handset: a different VM, a different integer width behaviour, a
 * preverifier that mangled something, or simply a JAR that was built wrong.
 * So the same vectors live here, in CLDC-only code, and are executed in all
 * three places:
 *
 *   1. desktop, from tgtest.SelfTestTest;
 *   2. emulator, from tg.app.CryptoMidlet;
 *   3. the phone, from the same MIDlet.
 *
 * A disagreement between any two of those is a toolchain bug, and finding it
 * this way is far cheaper than debugging a failed auth_key handshake.
 *
 * The benchmark exists because 2048-bit modPow on a ~208 MHz CPU is the single
 * number that decides whether this project is viable. Everything is sized to
 * avoid large allocations: buffers are small and reused, never megabytes.
 */
public final class SelfTest
{
    /** Small enough not to strain an unmeasured heap, big enough to time. */
    private static final int BENCH_BUFFER = 1024;

    private SelfTest() { }

    /** Outcome of a self-test run. */
    public static final class Result
    {
        public String[] lines;
        public int passed;
        public int failed;

        public boolean ok()
        {
            return failed == 0;
        }
    }

    // ------------------------------------------------------------ vectors

    /**
     * Run every vector. Returns lines suitable for the diagnostics screen; each
     * begins with "ok " or "FAIL ".
     */
    public static Result run()
    {
        Vector lines = new Vector(24);
        int[] counters = new int[2];        // [0] passed, [1] failed

        // --- SHA-1, FIPS 180-4
        check(lines, counters, "sha1 empty",
              "da39a3ee5e6b4b0d3255bfef95601890afd80709",
              Sha1.hash(new byte[0]));
        check(lines, counters, "sha1 abc",
              "a9993e364706816aba3e25717850c26c9cd0d89d",
              Sha1.hash(ascii("abc")));
        check(lines, counters, "sha1 two-block",
              "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
              Sha1.hash(ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")));

        // --- SHA-256, FIPS 180-4
        check(lines, counters, "sha256 empty",
              "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              Sha256.hash(new byte[0]));
        check(lines, counters, "sha256 abc",
              "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
              Sha256.hash(ascii("abc")));
        check(lines, counters, "sha256 two-block",
              "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
              Sha256.hash(ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")));

        // --- SHA-256 streaming: MTProto hashes msg_key || auth_key slices
        //     without joining them, so this path matters as much as one-shot.
        Sha256 streaming = new Sha256();
        byte[] part = ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq");
        for (int i = 0; i < part.length; i++) { streaming.update(part[i]); }
        check(lines, counters, "sha256 byte-at-a-time",
              "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
              streaming.digest());

        check(lines, counters, "sha512 abc",
              "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
            + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
              Sha512.hash(ascii("abc")));
        check(lines, counters, "pbkdf2-sha512 iter1",
              "867f70cf1ade02cff3752599a3a53dc4af34c7a669815ae5d513554e1c8cf252"
            + "c02d470a285a0501bad999bfe943c08f050235d7d68b1da55e63f73b60a57fce",
              Pbkdf2.derive(ascii("password"), ascii("salt"), 1, 64));

        // --- AES, FIPS-197 Appendix C
        byte[] fipsPlain = Hex.decode("00112233445566778899aabbccddeeff");
        checkAes(lines, counters, "aes128",
                 "000102030405060708090a0b0c0d0e0f",
                 fipsPlain, "69c4e0d86a7b0430d8cdb78070b4c55a");
        checkAes(lines, counters, "aes192",
                 "000102030405060708090a0b0c0d0e0f1011121314151617",
                 fipsPlain, "dda97ca4864cdfe06eaf70a0ec0d7191");
        checkAes(lines, counters, "aes256",
                 "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
                 fipsPlain, "8ea2b7ca516745bfeafc49904b496089");

        // --- AES-IGE, the mode MTProto actually uses (OpenSSL reference vector)
        byte[] igeKey = Hex.decode("000102030405060708090A0B0C0D0E0F");
        byte[] igeIv = Hex.decode(
                "000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F");
        AesIge ige = new AesIge(igeKey);
        byte[] igeCipher = ige.encrypt(igeIv, new byte[32]);
        check(lines, counters, "aes-ige encrypt",
              "1a8519a6557be652e9da8e43da4ef4453cf456b4ca488aa383c79c98b34797cb",
              igeCipher);
        check(lines, counters, "aes-ige decrypt",
              "0000000000000000000000000000000000000000000000000000000000000000",
              ige.decrypt(igeIv, igeCipher));

        // --- big integer: the DH arithmetic
        check(lines, counters, "bigint 3^7 mod 13",
              BigInteger.valueOf(3).modPow(BigInteger.valueOf(7), BigInteger.valueOf(13))
                      .toString().equals("3"));
        check(lines, counters, "bigint 2^64",
              BigInteger.valueOf(2).pow(64).toString().equals("18446744073709551616"));
        check(lines, counters, "bigint byte round trip",
              Hex.encode(new BigInteger(1, Hex.decode("00ff102030405060708090"))
                      .toByteArray()).endsWith("ff102030405060708090"));

        // A fixed 256-bit modPow: the desktop and the phone must agree exactly,
        // which is the whole point of running this here.
        BigInteger g = new BigInteger(1, Hex.decode(
            "0000000000000000000000000000000000000000000000000000000000000003"));
        BigInteger e = new BigInteger(1, Hex.decode(
            "00000000000000000000000000000000000000000000000000000000000f4241"));
        BigInteger m = new BigInteger(1, Hex.decode(
            "fffffffffffffffffffffffffffffffefffffc2f"));
        // Pinned from the desktop JVM. The device must reproduce it bit for bit;
        // a mismatch means the ported BigInteger behaves differently after
        // preverification, which would break the DH step silently.
        check(lines, counters, "bigint modPow 3^1000001",
              "00d87a9a3ed71544cbfc14952d83aeecda23db40af",
              g.modPow(e, m).toByteArray());

        // --- RNG: deterministic from a fixed seed, so a device disagreement
        //     here means the hash or the buffering differs, not the entropy.
        Rng rng = Rng.forTesting(ascii("selftest-seed"));
        check(lines, counters, "rng deterministic",
              "5ee48f72895098b871f4b9c90e215700",
              trim(rng.nextBytes(16), 16));

        Result r = new Result();
        r.passed = counters[0];
        r.failed = counters[1];

        lines.insertElementAt("passed " + r.passed + ", failed " + r.failed, 0);
        r.lines = new String[lines.size()];
        lines.copyInto(r.lines);
        return r;
    }

    // --------------------------------------------------------- benchmarks

    /**
     * Timings for the operations MTProto depends on. Repeats a small buffer
     * rather than allocating a large one - the handoff is explicit that
     * multi-megabyte benchmark buffers are not acceptable here.
     */
    public static String[] benchmark()
    {
        Vector out = new Vector(12);
        Runtime rt = Runtime.getRuntime();
        out.addElement("heap free before = " + (rt.freeMemory() / 1024) + "k");

        byte[] buf = new byte[BENCH_BUFFER];
        for (int i = 0; i < buf.length; i++) { buf[i] = (byte) i; }

        // SHA-256 throughput
        Sha256 sha = new Sha256();
        int shaRounds = 64;
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < shaRounds; i++) { sha.update(buf, 0, buf.length); }
        sha.digest();
        long shaMs = System.currentTimeMillis() - t0;
        out.addElement("sha256 " + (shaRounds * BENCH_BUFFER / 1024) + "k = " + shaMs + " ms"
                       + rate(shaRounds * BENCH_BUFFER, shaMs));

        // AES-IGE throughput, both directions
        byte[] key = new byte[32];
        byte[] iv = new byte[32];
        for (int i = 0; i < 32; i++) { key[i] = (byte) i; iv[i] = (byte) (i * 7); }
        AesIge ige = new AesIge(key);

        int igeRounds = 16;
        t0 = System.currentTimeMillis();
        for (int i = 0; i < igeRounds; i++) { ige.encrypt(iv, 0, buf, 0, buf, 0, buf.length); }
        long encMs = System.currentTimeMillis() - t0;
        out.addElement("aes-ige enc " + (igeRounds * BENCH_BUFFER / 1024) + "k = " + encMs + " ms"
                       + rate(igeRounds * BENCH_BUFFER, encMs));

        t0 = System.currentTimeMillis();
        for (int i = 0; i < igeRounds; i++) { ige.decrypt(iv, 0, buf, 0, buf, 0, buf.length); }
        long decMs = System.currentTimeMillis() - t0;
        out.addElement("aes-ige dec " + (igeRounds * BENCH_BUFFER / 1024) + "k = " + decMs + " ms"
                       + rate(igeRounds * BENCH_BUFFER, decMs));

        // The number that decides the project: one 2048-bit modular
        // exponentiation, as performed once per auth_key generation.
        out.addElement("-- modPow (auth_key DH cost) --");
        out.addElement("modPow  256-bit = " + timeModPow(256) + " ms");
        out.addElement("modPow  512-bit = " + timeModPow(512) + " ms");
        out.addElement("modPow 1024-bit = " + timeModPow(1024) + " ms");
        out.addElement("modPow 2048-bit = " + timeModPow(2048) + " ms");
        // Reference figures from this benchmark on a desktop JVM. The cold
        // number is the fair comparison for a handset, which has no JIT.
        out.addElement("(desktop ref 2048-bit: ~33 ms cold, ~12 ms warmed)");

        out.addElement("heap free after = " + (rt.freeMemory() / 1024) + "k");

        String[] arr = new String[out.size()];
        out.copyInto(arr);
        return arr;
    }

    public static String[] benchmarkPbkdf2(Pbkdf2.Progress progress)
    {
        byte[] password = ascii("telegram-j2me-password-benchmark");
        byte[] salt = ascii("telegram-j2me-salt");
        long t0 = System.currentTimeMillis();
        byte[] result = Pbkdf2.derive(password, salt, 100000, 64, progress);
        long ms = System.currentTimeMillis() - t0;
        return new String[] {
            "PBKDF2-HMAC-SHA512",
            "iterations = 100000",
            "output = 64 bytes",
            "time = " + ms + " ms",
            "head = " + Hex.encode(result, 0, 8),
            ms > 180000 ? "WARNING: over 3 minutes" : "timing acceptable for evaluation"
        };
    }

    private static long timeModPow(int bits)
    {
        int bytes = bits / 8;
        byte[] pb = new byte[bytes];
        byte[] gb = new byte[bytes];
        byte[] eb = new byte[bytes];
        // Deterministic operands: the timing must be comparable between runs
        // and between devices.
        Rng r = Rng.forTesting(ascii("bench-" + bits));
        r.nextBytes(pb, 0, bytes);
        r.nextBytes(gb, 0, bytes);
        r.nextBytes(eb, 0, bytes);
        pb[0] |= (byte) 0x80;
        pb[bytes - 1] |= 1;

        BigInteger p = new BigInteger(1, pb);
        BigInteger g = new BigInteger(1, gb);
        BigInteger e = new BigInteger(1, eb);

        long t0 = System.currentTimeMillis();
        g.modPow(e, p);
        return System.currentTimeMillis() - t0;
    }

    private static String rate(int bytes, long ms)
    {
        if (ms <= 0) { return " (too fast to time)"; }
        return " (" + (bytes * 1000L / ms / 1024) + " KB/s)";
    }

    // ------------------------------------------------------------ internal

    private static void check(Vector lines, int[] counters,
                              String label, String expectedHex, byte[] actual)
    {
        check(lines, counters, label, expectedHex, actual, true);
    }

    private static void check(Vector lines, int[] counters, String label,
                              String expectedHex, byte[] actual, boolean strict)
    {
        boolean ok = Hex.equals(Hex.decode(expectedHex), actual);
        if (ok)
        {
            counters[0]++;
            lines.addElement("ok   " + label);
        }
        else if (!strict)
        {
            // Informational: record the value so a device that differs can be
            // compared, without failing the run.
            counters[0]++;
            lines.addElement("ok?  " + label + " = " + Hex.encode(actual));
        }
        else
        {
            counters[1]++;
            lines.addElement("FAIL " + label);
            lines.addElement("     want " + expectedHex);
            lines.addElement("     got  " + Hex.encode(actual));
        }
    }

    private static void check(Vector lines, int[] counters, String label, boolean ok)
    {
        if (ok)
        {
            counters[0]++;
            lines.addElement("ok   " + label);
        }
        else
        {
            counters[1]++;
            lines.addElement("FAIL " + label);
        }
    }

    private static void checkAes(Vector lines, int[] counters, String label,
                                 String keyHex, byte[] plain, String cipherHex)
    {
        Aes aes = new Aes(Hex.decode(keyHex));
        byte[] enc = new byte[16];
        aes.encryptBlock(plain, 0, enc, 0);
        check(lines, counters, label + " enc", cipherHex, enc);

        byte[] dec = new byte[16];
        aes.decryptBlock(enc, 0, dec, 0);
        check(lines, counters, label + " dec", Hex.encode(plain), dec);
    }

    private static byte[] trim(byte[] data, int len)
    {
        if (data.length == len) { return data; }
        byte[] out = new byte[len];
        System.arraycopy(data, 0, out, 0, len);
        return out;
    }

    /** CLDC's String.getBytes() honours the platform encoding; this does not. */
    private static byte[] ascii(String s)
    {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++)
        {
            out[i] = (byte) s.charAt(i);
        }
        return out;
    }
}
