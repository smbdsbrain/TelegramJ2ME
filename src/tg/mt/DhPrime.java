package tg.mt;

import tg.crypto.bigint.BigInteger;
import tg.diag.Diag;
import tg.io.Hex;

/**
 * Diffie-Hellman parameter validation for the auth_key handshake.
 *
 * <h3>Why this cannot be skipped</h3>
 * A server that supplies a composite {@code dh_prime}, or a generator that does
 * not generate the large prime-order subgroup, can make the shared secret fall
 * into a small set it can enumerate. Accepting whatever the server sends turns
 * the whole handshake into theatre. The MTProto security guidelines make these
 * checks mandatory, and "the demo went green" is not a reason to drop them.
 *
 * <h3>Why there is a fast path</h3>
 * Proving a 2048-bit number prime - twice, for p and (p-1)/2 - is far beyond
 * what a 208 MHz handset can do in acceptable time. Telegram's own
 * documentation recommends embedding known-good primes checked in advance, so
 * that is what happens here: the current server prime is compiled in, and a
 * byte-exact match skips the arithmetic entirely.
 *
 * If the server ever changes the prime, {@link #validate} falls back to real
 * Miller-Rabin rounds. That will be slow on the handset - which is the correct
 * trade: slow beats insecure, and the caller is told through {@link Result} so
 * the UI can say what is happening rather than appearing to hang.
 */
public final class DhPrime
{
    /**
     * The prime Telegram has been serving, from
     * core.telegram.org/mtproto/auth_key. Verified there as a safe 2048-bit
     * prime; a byte-exact match is what licenses skipping the primality test.
     */
    private static final String KNOWN_GOOD_HEX =
        "c71caeb9c6b1c9048e6c522f70f13f73980d40238e3e21c14934d037563d930f" +
        "48198a0aa7c1405822949 3d22530f4dbfa336f6e0ac925139543aed44cce7c37" +
        "20fd51f69458705ac68cd4fe6b6b13abdc9746512969328454f18faf8c595f64" +
        "2477fe96bb2a941d5bcd1d4ac8cc498807 08fa9b378e3c4f3a9060bee67cf9a4" +
        "a4a695811051907e162753b56b0f6b410dba74d8a84b2a14b3144e0ef1284754" +
        "fd17ed950d5965b4b9dd46582db1178d169c6bc465b0d6ff9ca3928fef5b9ae4" +
        "e418fc15e83ebea0f87fa9ff5eed70050ded2849f47bf959d956850ce929851f" +
        "0d8115f635b105ee2e4e15d04b2454bf6f4fadf034b10403119cd8e3b92fcc5b";

    /** Lower bound recommended for g_a and g_b: 2^(2048-64). */
    private static final int SAFE_BITS = 2048 - 64;

    /** Miller-Rabin rounds when the prime is not the known one. */
    private static final int MILLER_RABIN_ROUNDS = 15;

    private static BigInteger knownGood;
    private static BigInteger safeLowerBound;

    private DhPrime() { }

    /** Outcome of validation, so the caller can report what was actually done. */
    public static final class Result
    {
        public boolean ok;
        public boolean usedKnownGoodFastPath;
        public String failure;
        public long millis;
    }

    private static synchronized BigInteger knownGood()
    {
        if (knownGood == null)
        {
            knownGood = new BigInteger(1, Hex.decode(KNOWN_GOOD_HEX));
        }
        return knownGood;
    }

    private static synchronized BigInteger safeLowerBound()
    {
        if (safeLowerBound == null)
        {
            safeLowerBound = BigInteger.ONE.shiftLeft(SAFE_BITS);
        }
        return safeLowerBound;
    }

    /**
     * Validate everything the specification and the security guidelines require.
     *
     * @param p  dh_prime from server_DH_inner_data
     * @param g  the generator, always 2..7 in practice
     * @param gA g_a from server_DH_inner_data
     */
    public static Result validate(BigInteger p, int g, BigInteger gA)
    {
        Result r = new Result();
        long t0 = System.currentTimeMillis();

        try
        {
            // 2^2047 < p < 2^2048
            if (p.bitLength() != 2048)
            {
                return fail(r, t0, "dh_prime is " + p.bitLength() + " bits, expected 2048");
            }

            if (p.equals(knownGood()))
            {
                r.usedKnownGoodFastPath = true;
                Diag.info("dh_prime matches the compiled-in known-good value");
            }
            else
            {
                Diag.warn("dh_prime is NOT the known-good value - running Miller-Rabin, "
                          + "this is slow on a handset");
                if (!p.isProbablePrime(MILLER_RABIN_ROUNDS))
                {
                    return fail(r, t0, "dh_prime is composite");
                }
                // Safe prime: (p-1)/2 must be prime too, otherwise the subgroup
                // order can be smooth and the secret brute-forceable.
                BigInteger half = p.subtract(BigInteger.ONE).shiftRight(1);
                if (!half.isProbablePrime(MILLER_RABIN_ROUNDS))
                {
                    return fail(r, t0, "(dh_prime-1)/2 is composite - not a safe prime");
                }
            }

            String gProblem = checkGenerator(p, g);
            if (gProblem != null)
            {
                return fail(r, t0, gProblem);
            }

            String gaProblem = checkPublicValue("g_a", p, gA);
            if (gaProblem != null)
            {
                return fail(r, t0, gaProblem);
            }

            r.ok = true;
            r.millis = System.currentTimeMillis() - t0;
            return r;
        }
        catch (Throwable t)
        {
            return fail(r, t0, "validation threw " + Diag.className(t));
        }
    }

    /**
     * g must generate the subgroup of prime order (p-1)/2, i.e. be a quadratic
     * residue mod p. For the handful of values Telegram uses this reduces to a
     * condition on p mod 4g - quadratic reciprocity saves us an exponentiation.
     */
    public static String checkGenerator(BigInteger p, int g)
    {
        if (g < 2 || g > 7)
        {
            return "generator " + g + " is outside the documented range 2..7";
        }

        int mod;
        boolean ok;
        switch (g)
        {
            case 2:
                mod = p.mod(BigInteger.valueOf(8)).intValue();
                ok = (mod == 7);
                break;
            case 3:
                mod = p.mod(BigInteger.valueOf(3)).intValue();
                ok = (mod == 2);
                break;
            case 4:
                ok = true;                      // no extra condition
                mod = 0;
                break;
            case 5:
                mod = p.mod(BigInteger.valueOf(5)).intValue();
                ok = (mod == 1 || mod == 4);
                break;
            case 6:
                mod = p.mod(BigInteger.valueOf(24)).intValue();
                ok = (mod == 19 || mod == 23);
                break;
            case 7:
                mod = p.mod(BigInteger.valueOf(7)).intValue();
                ok = (mod == 3 || mod == 5 || mod == 6);
                break;
            default:
                return "unreachable";
        }
        if (!ok)
        {
            return "generator " + g + " does not generate the prime-order subgroup "
                   + "(p mod condition failed, residue " + mod + ")";
        }
        return null;
    }

    /**
     * 1 &lt; value &lt; p-1, plus Telegram's stronger recommendation that it lie
     * between 2^(2048-64) and p - 2^(2048-64). The stronger bound rules out
     * values close to the ends of the range, where the subgroup structure could
     * be exploited.
     */
    public static String checkPublicValue(String name, BigInteger p, BigInteger value)
    {
        if (value.compareTo(BigInteger.ONE) <= 0)
        {
            return name + " must be greater than 1";
        }
        BigInteger pMinus1 = p.subtract(BigInteger.ONE);
        if (value.compareTo(pMinus1) >= 0)
        {
            return name + " must be less than dh_prime-1";
        }
        BigInteger low = safeLowerBound();
        if (value.compareTo(low) < 0)
        {
            return name + " is below the recommended 2^(2048-64) bound";
        }
        if (value.compareTo(p.subtract(low)) > 0)
        {
            return name + " is above the recommended dh_prime - 2^(2048-64) bound";
        }
        return null;
    }

    private static Result fail(Result r, long t0, String why)
    {
        r.ok = false;
        r.failure = why;
        r.millis = System.currentTimeMillis() - t0;
        Diag.error("DH validation failed: " + why);
        return r;
    }
}
