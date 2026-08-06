package tg.crypto;

/**
 * How many bits the jitter samples of this run are actually worth.
 *
 * <h3>Why it exists</h3>
 * {@link Entropy#gather()} spends a fixed wall-clock window sampling the clock,
 * so the number of samples it collects is {@code window / tick} and its yield is
 * a property of the handset, not of this code. Three measured devices span an
 * order of magnitude - 21 bits per gather on a Samsung GT-C3592, 58 on an
 * Alcatel OT-810D, 135-165 on a Nokia C3-00 - and a compiled-in count sized from
 * any one of them is wrong on the other two, in both directions. So
 * {@link AuthKeySeeding} feeds this class the samples it is folding in and asks
 * after every gather whether it has enough yet.
 *
 * <h3>The estimate</h3>
 * The same arithmetic {@code tg.plat.EntropyProbe} publishes, which is the point:
 * the client's decision and the figures in {@code docs/hardware/} must not be
 * able to disagree.
 * <ul>
 *   <li>NIST SP 800-90B most-common-value over the raw spin counts, at the 99%
 *       upper confidence bound on {@code p_max} - {@link MinEntropy};</li>
 *   <li>discounted when adjacent samples turn out to be dependent: the estimator
 *       is run again over pairs of equal-population levels, and if a pair carries
 *       less than twice a single sample's entropy the headline is scaled by that
 *       ratio;</li>
 *   <li>credited total is {@code headline x samples}, and nothing else is counted.
 *       Heap readings, identity hash codes and the wall clock all go into the
 *       pool and are charged at zero here, exactly as the published figures are.</li>
 * </ul>
 *
 * <h3>Every rounding goes down</h3>
 * {@link MinEntropy}'s own invariant, plus two more here. Histogram overflow is
 * charged to the mode ({@link IntHistogram#conservativeMaxCount()}), so a table
 * too small to hold the distribution lowers the answer rather than raising it.
 * And samples are pooled <em>across</em> gathers into one table: a handset whose
 * gathers repeat each other raises {@code p_max} and is made to gather more,
 * where multiplying a per-gather figure by a gather count would have quietly
 * summed correlated samples.
 *
 * That pooling is a mitigation, not a proof. Nothing here demonstrates that
 * consecutive gathers are independent, and this class does not claim it - it
 * claims only that dependence which shows up as repetition is paid for. The open
 * item in {@link Entropy} stands.
 *
 * <h3>Not thread-safe</h3>
 * One barrier owns one instance and drives it from the thread doing the
 * gathering. {@link Entropy.JitterSink} is called from inside the sampling loop,
 * so nothing here allocates.
 */
public final class JitterYield implements Entropy.JitterSink
{
    /**
     * Equal-population levels for the pair check.
     *
     * Eight gives 64 possible pairs. Fewer would blunt the check; more would
     * spread a barrier's few hundred samples too thin for the pair estimator to
     * mean anything. Same value the probe uses, for the same reason.
     */
    private static final int LEVELS = 8;

    /**
     * Distinct raw spin counts the table can hold before overflow is charged to
     * the mode. A 120 ms window on a 1 ms clock yields 120 samples, and the
     * observed spread on the measured handsets is a few hundred values wide.
     */
    private static final int RAW_SLOTS = 1024;

    private final IntHistogram raw = new IntHistogram(RAW_SLOTS);
    private final IntHistogram coarse = new IntHistogram(16);
    private final IntHistogram pairs = new IntHistogram(128);

    /** Level boundaries, learned from the first round. Empty until then. */
    private int[] cuts = new int[0];

    private int samples;
    private int rounds;
    private int repeats;
    private int previousLevel = -1;
    private int previousRaw;
    private boolean hasPrevious;

    /**
     * Begin one gather's worth of samples.
     *
     * The pair state is reset rather than carried over, because the gathers are
     * separated by a sleep: treating the last sample of one and the first of the
     * next as adjacent would measure the sleep, not the scheduler.
     */
    public void startRound()
    {
        previousLevel = -1;
        hasPrevious = false;
    }

    /** End of one gather. Learns the level boundaries from the first one. */
    public void endRound()
    {
        rounds++;
        if (cuts.length == 0 && raw.distinct() > 1)
        {
            // Equal-population, not equal-width: spin counts are bimodal, and any
            // linear scale drops almost everything into one level, leaving the
            // pair check with nothing to compare. See IntHistogram.quantileCuts.
            cuts = raw.quantileCuts(LEVELS);
        }
    }

    public void sample(long spins, long nowMillis)
    {
        int s = clamp(spins);
        raw.add(s);
        samples++;

        if (cuts.length > 0)
        {
            // How many cut points this sample exceeds. At most seven
            // comparisons, so no search structure is warranted.
            int level = 0;
            while (level < cuts.length && s > cuts[level]) { level++; }
            coarse.add(level);
            if (previousLevel >= 0) { pairs.add(previousLevel * (cuts.length + 1) + level); }
            previousLevel = level;
        }

        if (hasPrevious && s == previousRaw) { repeats++; }
        previousRaw = s;
        hasPrevious = true;
    }

    /** Samples seen across every round so far. */
    public int samples()
    {
        return samples;
    }

    /** Gathers completed, as counted by {@link #endRound()}. */
    public int rounds()
    {
        return rounds;
    }

    /** Adjacent identical spin counts, per thousand samples. Diagnostics only. */
    public int repeatsPerThousand()
    {
        return MinEntropy.permille(repeats, samples);
    }

    /**
     * Min-entropy per sample in eighths of a bit, after the confidence bound and
     * the serial-correlation discount.
     */
    public int perSampleEighths()
    {
        if (samples < 2) { return 0; }

        int headline = MinEntropy.eighthsWithConfidence(raw.conservativeMaxCount(), samples);
        if (headline <= 0) { return 0; }

        // The pair check needs a coarse distribution that is not concentrated in
        // one level; when it is, the ratio says nothing and the probe reports the
        // headline UNCHECKED. Here there is no reader to warn, so an unusable
        // check must not read as "no correlation found" - it costs the discount
        // it cannot rule out.
        int coarseTotal = (int) coarse.total();
        int pairTotal = (int) pairs.total();
        if (coarseTotal < LEVELS * 2 || pairTotal < LEVELS)
        {
            return halved(headline);
        }

        int hCoarse = MinEntropy.eighths(coarse.conservativeMaxCount(), coarseTotal);
        if (hCoarse <= 0) { return halved(headline); }

        int hPairPer = MinEntropy.eighths(pairs.conservativeMaxCount(), pairTotal) / 2;
        if (hPairPer < hCoarse)
        {
            return MinEntropy.discount(headline, hPairPer, hCoarse);
        }
        return headline;
    }

    /**
     * The credited total: per-sample entropy times the samples seen.
     *
     * A lower bound on what the pool has absorbed from jitter, and the number
     * {@link AuthKeySeeding} stops at.
     *
     * Not monotone, and not meant to be: the per-sample figure is quantised to
     * eighths of a bit, so a gather that moves the bound down by one eighth can
     * cost more than its samples add. It matters to nobody - the barrier stops
     * the first time the total is enough and never asks again - but a caller that
     * assumed the total only rises would be assuming something the estimator does
     * not promise.
     */
    public int creditedBits()
    {
        return (perSampleEighths() * samples) / 8;
    }

    /**
     * Whether the run has produced {@code targetBits} it can stand behind.
     *
     * Two conditions, not one. The bits are the goal; the sample floor is
     * {@link MinEntropy#MIN_SAMPLES_FOR_CONFIDENCE}, below which this project
     * does not publish a bounded figure at all, and sizing a permanent key from
     * an estimate too small to publish would be using the estimator outside its
     * stated domain. On the two coarse-clock handsets measured it is the floor
     * that binds, not the target.
     */
    public boolean enough(int targetBits)
    {
        return samples >= MinEntropy.MIN_SAMPLES_FOR_CONFIDENCE
                && creditedBits() >= targetBits;
    }

    /** Halving, rounding down, saturating at zero. */
    private static int halved(int eighthsValue)
    {
        return eighthsValue <= 0 ? 0 : eighthsValue / 2;
    }

    private static int clamp(long v)
    {
        if (v > Integer.MAX_VALUE) { return Integer.MAX_VALUE; }
        if (v < Integer.MIN_VALUE) { return Integer.MIN_VALUE; }
        return (int) v;
    }
}
