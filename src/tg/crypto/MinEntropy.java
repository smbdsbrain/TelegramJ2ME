package tg.crypto;

/**
 * Min-entropy estimation in integer arithmetic.
 *
 * <h3>Why this lives in tg.crypto</h3>
 * It began as probe-only code in {@code tg.plat} and moved here when
 * {@link AuthKeySeeding} started sizing itself from a measurement instead of a
 * constant. Nothing about it is platform-specific - there are no imports at all
 * - and the alternative was a second implementation of the same estimator in
 * the package that seeds keys. Two implementations of one formula is exactly
 * what {@link Entropy.JitterSink} exists to avoid: they agree until one of them
 * is edited, and then every published figure quietly stops describing what the
 * client does.
 *
 * <h3>Why integer</h3>
 * CLDC 1.1's {@code java.lang.Math} has no {@code log}, no {@code pow} and no
 * {@code exp} - {@code config/cldc11-midp20-api.txt} denies all three and
 * {@code tools/check-api.py} fails the build if one appears. {@code Math.sqrt}
 * does exist, but this class avoids it too: integer-only means the desktop test
 * can assert values that are <em>bit-identical</em> to what the handset
 * computes, with no KVM floating-point corner cases in play. On a device we
 * cannot attach a debugger to, that is worth more than the three lines it saves.
 *
 * <h3>What is estimated</h3>
 * The NIST SP 800-90B <i>most-common-value</i> estimator: if the commonest
 * symbol appears {@code cmax} times out of {@code n}, the min-entropy per sample
 * is {@code -log2(cmax/n)}. It is the weakest of the 800-90B estimators, but it
 * is the one that can be computed in a few hundred bytes of code on a 208 MHz
 * CPU, and it is a lower bound, which is the direction that matters here.
 *
 * <h3>The invariant that lets this ship</h3>
 * <b>Every rounding error moves the answer down.</b> Each {@code >>} and each
 * {@code /} below truncates toward zero for positive operands, so the reported
 * figure is always less than or equal to the true one. A conservative estimator
 * can be wrong and still be honest; an optimistic one cannot.
 *
 * <h3>What this does NOT do</h3>
 * MCV assumes independent, identically distributed samples. Jitter spin counts
 * are almost certainly serially correlated by clock-tick phase. The full
 * 800-90B non-IID track is out of scope for a feature phone, so
 * {@link JitterYield} and {@code tg.plat.EntropyProbe} additionally run the
 * estimator over adjacent <i>pairs</i> and discount by the ratio - a
 * correlation check, not a substitute. Reports must say so.
 */
public final class MinEntropy
{
    /** Below this many samples the confidence bound is too crude to publish. */
    public static final int MIN_SAMPLES_FOR_CONFIDENCE = 256;

    /** 2.576 sigma for a one-sided 99% bound, scaled by 1000 to stay integral. */
    private static final long Z99_MILLI = 2576L;

    private MinEntropy() { }

    /**
     * Min-entropy per sample in eighths of a bit: {@code floor(log2(n/cmax) * 8)}.
     *
     * Returns 0 for degenerate input rather than throwing - a probe that dies
     * while reporting is worse than one that reports zero, and zero is the
     * honest answer for "every sample was identical" anyway.
     *
     * @param cmax occurrences of the most common value
     * @param n    total samples
     */
    public static int eighths(int cmax, int n)
    {
        if (cmax <= 0 || n <= 0 || cmax >= n) { return 0; }

        // Integer part: how many times cmax doubles before it passes n.
        int whole = 0;
        long t = cmax;
        while ((t << 1) <= n) { t <<= 1; whole++; }

        // Fractional part, three binary digits, by repeated squaring in Q30.
        // x starts in [1,2) scaled by 2^30; squaring it and testing against 2
        // peels off one bit of log2 per iteration.
        //
        // Q30 rather than the Q16 that would obviously suffice for three output
        // bits, because squaring amplifies the initial truncation eightfold. At
        // Q16 the accumulated loss is around 2^-13, which is enough to push a
        // ratio sitting just above a k/8 boundary - log2(884/482) = 0.875013 is
        // a real example - down into the bucket below, costing a full eighth.
        // At Q30 the loss is under 2^-26, six orders of magnitude inside the
        // output quantum. Overflow headroom: x < 2^31, so x*x < 2^62.
        long x = (((long) n) << 30) / t;
        int frac = 0;
        for (int i = 0; i < 3; i++)
        {
            x = (x * x) >> 30;
            frac <<= 1;
            if (x >= (2L << 30)) { x >>= 1; frac |= 1; }
        }
        return whole * 8 + frac;
    }

    /**
     * As {@link #eighths} but with the SP 800-90B 99% upper confidence bound on
     * {@code p_max} applied first, which is what the standard actually requires.
     *
     * {@code p_u = p + 2.576 * sqrt(p(1-p)/(n-1))}. Multiplying through by
     * {@code n} turns it into an inflated count, so the same estimator can be
     * reused rather than reimplemented in floating point:
     *
     * <pre>c_u = cmax + ceil( 2576 * sqrt(cmax*(n-cmax)*(n-1)) / (1000*(n-1)) )</pre>
     *
     * At {@code n = 4000} the radicand peaks around 1.6e10 - five orders of
     * magnitude inside a long.
     *
     * <b>The bound is severe at small n</b>, which is why
     * {@link #MIN_SAMPLES_FOR_CONFIDENCE} exists: at n = 50 it can halve the
     * estimate, and publishing that as "the measured entropy" would understate a
     * real result as badly as an optimistic estimator overstates one.
     */
    public static int eighthsWithConfidence(int cmax, int n)
    {
        if (n < 3 || cmax <= 0 || cmax >= n) { return eighths(cmax, n); }

        long den = n - 1;
        long radicand = (long) cmax * (n - cmax) * den;
        long root = isqrt(radicand) + 1;                    // round up: conservative
        long scaled = Z99_MILLI * root;
        long margin = (scaled + 1000L * den - 1) / (1000L * den);   // ceil
        long cu = cmax + margin;
        if (cu >= n) { return 0; }
        return eighths((int) cu, n);
    }

    /**
     * {@code floor(sqrt(v))} by Newton's method.
     *
     * Deliberately not {@code Math.sqrt}: see the class note. Converges in about
     * 32 iterations for a 64-bit input and terminates for every non-negative
     * long including {@link Long#MAX_VALUE}.
     */
    public static long isqrt(long v)
    {
        if (v <= 0) { return 0; }
        if (v < 4) { return 1; }
        long x = v;
        // (x + 1) >> 1 would be the obvious seed and wraps to a negative value
        // when v is Long.MAX_VALUE, after which the iteration divides by a
        // negative x and never converges. The unsigned shift cannot overflow.
        long y = (x >>> 1) + 1;
        while (y < x)
        {
            x = y;
            // Newton approaches from above, so v/x <= x here and the sum stays
            // below 2x - no overflow for any v.
            y = (x + v / x) >> 1;
        }
        return x;
    }

    /**
     * Eighths of a bit as a three-decimal string: {@code 51 -> "6.375"}.
     *
     * One eighth is exactly 0.125, so thousandths are {@code (e % 8) * 125} and
     * no floating point is needed to print an exact value. The zero padding
     * matters: without it {@code e = 0} prints as "6.0" beside "6.375" and the
     * columns stop lining up on a 34-column screen.
     */
    public static String bits(int e)
    {
        if (e < 0) { e = 0; }
        int whole = e / 8;
        int milli = (e % 8) * 125;
        StringBuffer sb = new StringBuffer(8);
        sb.append(whole).append('.');
        if (milli < 100) { sb.append('0'); }
        if (milli < 10) { sb.append('0'); }
        sb.append(milli);
        return sb.toString();
    }

    /** Convenience: {@code bits(eighths(cmax, n))}. */
    public static String bitsOf(int cmax, int n)
    {
        return bits(eighths(cmax, n));
    }

    /**
     * The most common value's share, in parts per thousand. Reported alongside
     * the entropy figure because a reader can sanity-check it by eye and cannot
     * sanity-check a logarithm.
     */
    public static int permille(int cmax, int n)
    {
        if (n <= 0) { return 0; }
        return (int) (((long) cmax * 1000L) / n);
    }

    /**
     * Scale {@code eighthsValue} by the ratio {@code num/den}, saturating at
     * zero and never rounding up.
     *
     * Used for the serial-correlation discount: a source whose pair entropy is
     * only half what independence would predict gets its per-sample figure
     * halved. Done in longs because the operands are already scaled by 8.
     */
    public static int discount(int eighthsValue, int num, int den)
    {
        if (eighthsValue <= 0 || num <= 0 || den <= 0) { return 0; }
        if (num >= den) { return eighthsValue; }
        return (int) (((long) eighthsValue * num) / den);
    }
}
