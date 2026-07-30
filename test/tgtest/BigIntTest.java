package tgtest;

import java.util.Random;

import tg.crypto.bigint.BigInteger;

/**
 * Differential test of the vendored Bouncy Castle BigInteger against the JDK's.
 *
 * MTProto's auth_key handshake is 2048-bit Diffie-Hellman, so a single wrong
 * bit in this arithmetic produces a handshake that fails with no useful error -
 * or, worse, appears to succeed against a modified server. CLDC has no
 * java.math.BigInteger to fall back on, which is why this code is vendored at
 * all; the JDK implementation is therefore the oracle here, on the desktop
 * only.
 *
 * The seed is fixed so a failure is reproducible.
 */
public final class BigIntTest implements Test
{
    private static final long SEED = 0x54454C45475241L;        // "TELEGRA" in ASCII
    private static final int ROUNDS = 300;

    public String name()
    {
        return "bigint/differential-vs-jdk";
    }

    public void run() throws Exception
    {
        Random r = new Random(SEED);

        for (int i = 0; i < ROUNDS; i++)
        {
            byte[] xb = randomMagnitude(r, 1 + r.nextInt(72));
            byte[] yb = randomMagnitude(r, 1 + r.nextInt(72));
            byte[] mb = randomModulus(r, 1 + r.nextInt(72));

            BigInteger x = new BigInteger(1, xb);
            BigInteger y = new BigInteger(1, yb);
            BigInteger m = new BigInteger(1, mb);

            java.math.BigInteger jx = new java.math.BigInteger(1, xb);
            java.math.BigInteger jy = new java.math.BigInteger(1, yb);
            java.math.BigInteger jm = new java.math.BigInteger(1, mb);

            check("add", i, x.add(y), jx.add(jy));
            check("subtract", i, x.subtract(y), jx.subtract(jy));
            check("multiply", i, x.multiply(y), jx.multiply(jy));
            check("mod", i, x.mod(m), jx.mod(jm));
            check("divide", i, x.divide(m), jx.divide(jm));
            check("remainder", i, x.remainder(m), jx.remainder(jm));
            check("gcd", i, x.gcd(y), jx.gcd(jy));
            check("shiftLeft", i, x.shiftLeft(13), jx.shiftLeft(13));
            check("shiftRight", i, x.shiftRight(13), jx.shiftRight(13));
            check("modPow", i, x.modPow(y, m), jx.modPow(jy, jm));

            Assert.equal("bitLength round " + i, jx.bitLength(), x.bitLength());
            Assert.equal("signum round " + i, jx.signum(), x.signum());
            Assert.equal("compareTo round " + i,
                    sign(jx.compareTo(jy)), sign(x.compareTo(y)));
        }

        modPow2048();
        knownAnswers();
    }

    /**
     * The operation that decides whether this project is viable on a 208 MHz
     * handset. Correctness is asserted here; the timing figure is only a
     * desktop reference point - the number that matters comes from the device
     * benchmark.
     */
    private void modPow2048() throws Exception
    {
        Random r = new Random(SEED + 1);
        byte[] pb = randomModulus(r, 256);
        byte[] gb = randomMagnitude(r, 256);
        byte[] eb = randomMagnitude(r, 256);

        BigInteger g = new BigInteger(1, gb);
        BigInteger e = new BigInteger(1, eb);
        BigInteger p = new BigInteger(1, pb);

        g.modPow(e, p);                         // let the JIT warm up
        long t0 = System.currentTimeMillis();
        BigInteger got = g.modPow(e, p);
        long elapsed = System.currentTimeMillis() - t0;

        java.math.BigInteger want = new java.math.BigInteger(1, gb)
                .modPow(new java.math.BigInteger(1, eb), new java.math.BigInteger(1, pb));

        Assert.bytesEqual("2048-bit modPow", want.toByteArray(), got.toByteArray());
        System.out.println("      2048-bit modPow: " + elapsed + " ms on this desktop JVM");
    }

    /** A few fixed answers, so a broken RNG cannot make the test vacuous. */
    private void knownAnswers()
    {
        Assert.equal("ZERO", "0", BigInteger.ZERO.toString());
        Assert.equal("ONE", "1", BigInteger.ONE.toString());
        Assert.equal("valueOf(255) hex", "ff", BigInteger.valueOf(255).toString(16));

        BigInteger two = BigInteger.valueOf(2);
        Assert.equal("2^64", "18446744073709551616", two.pow(64).toString());

        // 3^7 mod 13 == 3
        Assert.equal("3^7 mod 13",
                "3",
                BigInteger.valueOf(3)
                        .modPow(BigInteger.valueOf(7), BigInteger.valueOf(13))
                        .toString());

        // Round-trip through the byte form MTProto actually transmits.
        byte[] raw = Assert.unhex("00ff10203040506070809000aabbccddeeff");
        Assert.equal("byte round trip",
                new java.math.BigInteger(1, raw).toString(16),
                new BigInteger(1, raw).toString(16));
    }

    // ----------------------------------------------------------- internal

    private static void check(String op, int round, BigInteger got, java.math.BigInteger want)
    {
        Assert.bytesEqual(op + " round " + round, want.toByteArray(), got.toByteArray());
    }

    private static byte[] randomMagnitude(Random r, int len)
    {
        byte[] b = new byte[len];
        r.nextBytes(b);
        return b;
    }

    /** Positive, odd, and top-bit set - the shape of a DH modulus. */
    private static byte[] randomModulus(Random r, int len)
    {
        byte[] b = new byte[len];
        r.nextBytes(b);
        b[0] |= (byte) 0x80;
        b[len - 1] |= 1;
        return b;
    }

    private static int sign(int v)
    {
        return v < 0 ? -1 : (v > 0 ? 1 : 0);
    }
}
