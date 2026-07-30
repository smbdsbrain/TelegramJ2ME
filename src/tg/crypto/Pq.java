package tg.crypto;

/**
 * Factorisation of the MTProto proof-of-work value.
 *
 * The server sends {@code pq}, a product of two distinct odd primes, and the
 * client must return p and q with p &lt; q. Telegram documents pq as at most
 * 2^63-1, so the factors are each around 2^31 and 64-bit arithmetic suffices -
 * no BigInteger needed.
 *
 * <h3>Why the multiplication is written the hard way</h3>
 * Pollard's rho needs {@code (a * b) mod m} with m up to 2^63, where {@code a*b}
 * overflows a Java long. C implementations use __int128; CLDC has nothing of the
 * sort and {@code Math.multiplyHigh} is a Java 9 API. The double-precision
 * quotient trick is unsound near 2^63 because a double carries only 53 bits of
 * mantissa. What is left is shift-and-add: 63 modular additions per
 * multiplication, each of which is exact.
 *
 * That costs roughly a few million long operations for a typical pq. It happens
 * once per authorization key, so the price is paid at first launch and never
 * again - the same reasoning that makes the 2048-bit modPow acceptable.
 *
 * Brent's variant of the cycle detection is used because it batches the GCDs,
 * which are the expensive part.
 */
public final class Pq
{
    /** Result of a factorisation, with p &lt; q as MTProto requires. */
    public static final class Factors
    {
        public final long p;
        public final long q;

        Factors(long a, long b)
        {
            if (a <= b) { p = a; q = b; } else { p = b; q = a; }
        }
    }

    private Pq() { }

    /**
     * @param pq product of two distinct odd primes, 0 &lt; pq &lt; 2^63
     * @return the two factors, p &lt; q
     * @throws ArithmeticException if pq could not be factored
     */
    public static Factors factor(long pq)
    {
        if (pq <= 0)
        {
            throw new ArithmeticException("pq must be positive, got " + pq);
        }
        if ((pq & 1) == 0)
        {
            // pq is documented as a product of two odd primes, so an even value
            // means we misparsed resPQ rather than that 2 is a factor.
            throw new ArithmeticException("pq is even: " + pq);
        }

        // Small factors first: cheap, and it makes the tests trivial to write.
        for (long d = 3; d <= 65535 && d * d <= pq; d += 2)
        {
            if (pq % d == 0)
            {
                return new Factors(d, pq / d);
            }
        }

        long divisor = brentRho(pq);
        if (divisor <= 1 || divisor >= pq)
        {
            throw new ArithmeticException("failed to factor pq " + pq);
        }
        return new Factors(divisor, pq / divisor);
    }

    /** Convenience for the wire format: pq arrives as big-endian bytes. */
    public static long fromBytes(byte[] data)
    {
        if (data == null || data.length == 0 || data.length > 8)
        {
            throw new ArithmeticException("pq must be 1..8 bytes, got "
                                          + (data == null ? -1 : data.length));
        }
        long v = 0;
        for (int i = 0; i < data.length; i++)
        {
            v = (v << 8) | (data[i] & 0xffL);
        }
        if (v < 0)
        {
            throw new ArithmeticException("pq does not fit in a signed long");
        }
        return v;
    }

    /** Minimal big-endian encoding, which is how p and q go back on the wire. */
    public static byte[] toBytes(long v)
    {
        int len = 1;
        for (long t = v >>> 8; t != 0; t >>>= 8)
        {
            len++;
        }
        byte[] out = new byte[len];
        for (int i = len - 1; i >= 0; i--)
        {
            out[i] = (byte) v;
            v >>>= 8;
        }
        return out;
    }

    // ------------------------------------------------------------ internal

    private static long brentRho(long n)
    {
        // Deterministic parameters: a reproducible failure is worth more than
        // a marginally better expected running time.
        long y = 2;
        long c = 1;
        long m = 128;

        while (c < 100)
        {
            long g = 1;
            long r = 1;
            long q = 1;
            long x = 0;
            long ys = 0;

            while (g == 1)
            {
                x = y;
                for (long i = 0; i < r; i++)
                {
                    y = step(y, c, n);
                }

                long k = 0;
                while (k < r && g == 1)
                {
                    ys = y;
                    long limit = (m < r - k) ? m : (r - k);
                    for (long i = 0; i < limit; i++)
                    {
                        y = step(y, c, n);
                        q = mulmod(q, absDiff(x, y), n);
                    }
                    g = gcd(q, n);
                    k += m;
                }
                r <<= 1;

                if (r > (1L << 40))
                {
                    break;          // give up on this c
                }
            }

            if (g == n)
            {
                // Backtrack one step at a time to find the factor we skipped.
                g = 1;
                while (g == 1)
                {
                    ys = step(ys, c, n);
                    g = gcd(absDiff(x, ys), n);
                }
            }

            if (g > 1 && g < n)
            {
                return g;
            }
            c += 2;
        }
        return 1;
    }

    /** f(y) = y^2 + c mod n */
    private static long step(long y, long c, long n)
    {
        long v = mulmod(y, y, n) + c;
        if (v >= n || v < 0) { v %= n; }
        return v;
    }

    private static long absDiff(long a, long b)
    {
        return a > b ? a - b : b - a;
    }

    /**
     * (a * b) mod m, exact for any m &lt; 2^63.
     *
     * Russian-peasant doubling: every intermediate stays below m, so nothing
     * overflows. Slower than a 128-bit multiply, but this runs a few million
     * times once per auth_key, not per message.
     */
    static long mulmod(long a, long b, long m)
    {
        long result = 0;
        a %= m;
        b %= m;
        while (b > 0)
        {
            if ((b & 1) != 0)
            {
                result = addmod(result, a, m);
            }
            a = addmod(a, a, m);
            b >>= 1;
        }
        return result;
    }

    /**
     * (a + b) mod m without overflowing, given a, b &lt; m.
     *
     * {@code a + b} can exceed 2^63-1 and wrap negative. Computing
     * {@code (a - m) + b} instead keeps every intermediate in range: a-m is
     * negative and adding b &lt; m cannot push it past b.
     */
    private static long addmod(long a, long b, long m)
    {
        long s = (a - m) + b;
        return s < 0 ? s + m : s;
    }

    private static long gcd(long a, long b)
    {
        while (b != 0)
        {
            long t = a % b;
            a = b;
            b = t;
        }
        return a < 0 ? -a : a;
    }
}
