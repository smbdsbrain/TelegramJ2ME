package tgtest;

import tg.crypto.Rng;
import tg.crypto.bigint.BigInteger;

/**
 * Behaviour of the RNG.
 *
 * This does not - and cannot - test randomness quality; the statistical
 * properties of the hash construction follow from SHA-256, and the real open
 * question is the entropy of the seed on the actual handset, which no desktop
 * test can answer. What is tested here is the mechanical behaviour that a bug
 * would silently break:
 *
 *   - a fixed seed reproduces exactly, so a failing crypto test can be replayed;
 *   - different seeds diverge, i.e. the seed is actually used;
 *   - output is not reused across calls or across the internal block boundary;
 *   - the bridge into BigInteger works, which is the whole reason Rng subclasses
 *     java.util.Random - DH secret generation goes through it.
 */
public final class RngTest implements Test
{
    public String name()
    {
        return "crypto/rng";
    }

    public void run() throws Exception
    {
        deterministicForTests();
        seedActuallyMatters();
        noRepeatsAcrossBlockBoundary();
        bridgesIntoBigInteger();
        boundedNextInt();
    }

    private void deterministicForTests()
    {
        byte[] seed = Assert.ascii("fixed-seed-for-reproducible-failures");

        byte[] a = Rng.forTesting(seed).nextBytes(200);
        byte[] b = Rng.forTesting(seed).nextBytes(200);
        Assert.bytesEqual("same seed reproduces the stream", a, b);

        // Reading in pieces must give the same stream as reading in one go -
        // otherwise the internal buffer is being mismanaged.
        Rng piecewise = Rng.forTesting(seed);
        byte[] joined = new byte[200];
        int off = 0;
        int[] chunks = { 1, 7, 32, 31, 64, 65 };
        for (int i = 0; off < 200; i++)
        {
            int n = Math.min(chunks[i % chunks.length], 200 - off);
            piecewise.nextBytes(joined, off, n);
            off += n;
        }
        Assert.bytesEqual("chunked reads match a single read", a, joined);
    }

    private void seedActuallyMatters()
    {
        byte[] a = Rng.forTesting(Assert.ascii("seed-a")).nextBytes(64);
        byte[] b = Rng.forTesting(Assert.ascii("seed-b")).nextBytes(64);
        boolean identical = true;
        for (int i = 0; i < a.length; i++)
        {
            if (a[i] != b[i]) { identical = false; break; }
        }
        Assert.isFalse("different seeds must produce different streams", identical);

        // Folding in more entropy must change the stream too.
        Rng r = Rng.forTesting(Assert.ascii("seed-a"));
        r.addEntropy(Assert.ascii("extra"));
        byte[] c = r.nextBytes(64);
        Assert.isTrue("addEntropy changes the stream", !equal(a, c));
        Assert.isTrue("isSeeded", r.isSeeded());
    }

    /**
     * The generator emits SHA-256 blocks of 32 bytes. A boundary bug would show
     * up as a repeated 32-byte run, which is exactly what would silently weaken
     * a nonce or a DH secret.
     */
    private void noRepeatsAcrossBlockBoundary()
    {
        byte[] out = Rng.forTesting(Assert.ascii("boundary")).nextBytes(32 * 16);
        for (int i = 0; i + 32 <= out.length; i += 32)
        {
            for (int j = i + 32; j + 32 <= out.length; j += 32)
            {
                boolean same = true;
                for (int k = 0; k < 32; k++)
                {
                    if (out[i + k] != out[j + k]) { same = false; break; }
                }
                Assert.isFalse("block at " + i + " repeats at " + j, same);
            }
        }

        // A run of zeros would mean the buffer is being handed out after it was
        // wiped rather than after it was refilled.
        int zeroRun = 0;
        int longestZeroRun = 0;
        for (int i = 0; i < out.length; i++)
        {
            zeroRun = (out[i] == 0) ? zeroRun + 1 : 0;
            if (zeroRun > longestZeroRun) { longestZeroRun = zeroRun; }
        }
        Assert.isTrue("no long zero run (saw " + longestZeroRun + ")", longestZeroRun < 8);
    }

    /**
     * The reason Rng extends java.util.Random at all: MTProto's DH step is
     * {@code new BigInteger(2048, rng)}, and on CLDC there is no SecureRandom to
     * pass instead.
     */
    private void bridgesIntoBigInteger()
    {
        Rng rng = Rng.forTesting(Assert.ascii("dh-secret"));

        BigInteger a = new BigInteger(2048, rng);
        Assert.isTrue("2048-bit secret is positive", a.signum() > 0);
        Assert.isTrue("2048-bit secret has a plausible bit length: " + a.bitLength(),
                a.bitLength() > 2000 && a.bitLength() <= 2048);

        BigInteger b = new BigInteger(2048, rng);
        Assert.isFalse("two draws differ", a.equals(b));

        // Same seed, same sequence - so a DH failure can be reproduced.
        Rng again = Rng.forTesting(Assert.ascii("dh-secret"));
        Assert.isTrue("BigInteger draw is reproducible from the seed",
                a.equals(new BigInteger(2048, again)));
    }

    private void boundedNextInt()
    {
        Rng rng = Rng.forTesting(Assert.ascii("bounded"));
        for (int i = 0; i < 5000; i++)
        {
            int v = rng.nextInt(1000);
            Assert.isTrue("nextInt(1000) in range, got " + v, v >= 0 && v < 1000);
        }
        // Power-of-two bound takes a different branch.
        for (int i = 0; i < 5000; i++)
        {
            int v = rng.nextInt(256);
            Assert.isTrue("nextInt(256) in range, got " + v, v >= 0 && v < 256);
        }
        try
        {
            rng.nextInt(0);
            Assert.fail("nextInt(0) should throw");
        }
        catch (IllegalArgumentException expected) { }
    }

    private static boolean equal(byte[] a, byte[] b)
    {
        if (a.length != b.length) { return false; }
        for (int i = 0; i < a.length; i++)
        {
            if (a[i] != b[i]) { return false; }
        }
        return true;
    }
}
