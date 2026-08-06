package tgtest;

import tg.crypto.JitterYield;
import tg.crypto.MinEntropy;

/**
 * The meter the auth-key barrier stops at.
 *
 * {@link tg.crypto.AuthKeySeeding} keeps gathering until this class says it has
 * 256 bits, so an optimistic answer here is a key seeded from less than the
 * project claims - and unlike a broken handshake, nothing would ever report it.
 * Every case below is therefore about the direction of the error: a stream that
 * carries nothing must credit nothing, a bounded stream must not be credited
 * above its own log2, and repetition must cost.
 *
 * The cases run the real accumulation, not a copy of it. Sample values are
 * scripted so the answers are exact rather than statistical - {@code EntropyTest}
 * is where the estimator itself is checked against {@code Math.log}.
 */
public final class JitterYieldTest implements Test
{
    public String name() { return "crypto/jitter-yield"; }

    public void run() throws Exception
    {
        anEmptyRunCreditsNothing();
        aFrozenStreamCreditsNothing();
        creditIsBoundedByTheAlphabet();
        creditGrowsWithSamples();
        repetitionBetweenGathersCosts();
        theSampleFloorHolds();
        roundsAreCounted();
    }

    /** No samples is not "unmeasured" - it is zero, and it must read as zero. */
    private void anEmptyRunCreditsNothing()
    {
        JitterYield y = new JitterYield();
        Assert.equal("no samples", 0, y.samples());
        Assert.equal("no bits", 0, y.creditedBits());
        Assert.isFalse("and never enough", y.enough(1));

        // One sample cannot support an estimate either: p_max is 1.
        y.startRound();
        y.sample(1234, 1000L);
        y.endRound();
        Assert.equal("a single sample credits nothing", 0, y.creditedBits());
    }

    /**
     * The frozen-clock case, which is the one that has to fail closed. A handset
     * whose clock does not advance produces a spin count that never changes, and
     * the barrier must refuse rather than fold a predictable stream into a key.
     */
    private void aFrozenStreamCreditsNothing()
    {
        JitterYield y = new JitterYield();
        for (int round = 0; round < 8; round++)
        {
            y.startRound();
            for (int i = 0; i < 64; i++) { y.sample(4242, 1000L + i); }
            y.endRound();
        }

        Assert.equal("512 identical samples", 512, y.samples());
        Assert.equal("p_max = 1 means no entropy", 0, y.perSampleEighths());
        Assert.equal("so nothing is credited", 0, y.creditedBits());
        Assert.isFalse("and the target is never reached", y.enough(256));
    }

    /**
     * The one-sided property, at the level that matters here: a stream drawn from
     * k values cannot be worth more than log2(k) per sample, whatever the
     * estimator does internally.
     */
    private void creditIsBoundedByTheAlphabet()
    {
        for (int k = 2; k <= 16; k *= 2)
        {
            JitterYield y = feed(new JitterYield(), 8, 64, k, 1);
            int perSample = y.perSampleEighths();
            int ceiling = (int) Math.floor(Math.log(k) / Math.log(2.0) * 8.0);

            Assert.isTrue("k=" + k + ": " + MinEntropy.bits(perSample)
                    + " must not exceed log2(k) = " + MinEntropy.bits(ceiling),
                    perSample <= ceiling);
            Assert.isTrue("k=" + k + ": a uniform stream is worth something",
                    perSample > 0);
            Assert.equal("k=" + k + ": credit is per-sample times samples",
                    (perSample * y.samples()) / 8, y.creditedBits());
        }
    }

    /**
     * More of the same source is worth more in total - over a run, which is the
     * only form of the statement that is true.
     *
     * The credited total is a product of the sample count and a per-sample figure
     * quantised to eighths of a bit, so one more gather can lower it: 32 fresh
     * samples that shift the bound down by a single eighth cost more than they
     * add. The barrier is unaffected - it stops the first time the total is
     * enough and never looks again - but a test that demanded monotonicity would
     * be asserting something the estimator does not promise, and it would fail on
     * the day someone made the bound tighter.
     */
    private void creditGrowsWithSamples()
    {
        JitterYield y = new JitterYield();
        feed(y, 1, 32, 8, 1);
        int atStart = y.creditedBits();

        for (int round = 2; round <= 12; round++)
        {
            feed(y, 1, 32, 8, round);
        }

        Assert.equal("384 samples", 384, y.samples());
        Assert.isTrue("12 gathers are worth more than one: " + atStart
                + " -> " + y.creditedBits(), y.creditedBits() > atStart);
        Assert.isTrue("384 samples of a 3-bit source reach the target: "
                + y.creditedBits(), y.creditedBits() >= 256);
    }

    /**
     * The property that makes pooling the right shape.
     *
     * A per-gather figure multiplied by a gather count would credit a handset
     * whose every gather is a replay of the first one with the full total. Pooled
     * into one table, the repeats raise p_max instead, and the barrier is made to
     * keep going. That is not a proof that distinct gathers are independent - it
     * is the narrower claim that dependence which shows up as repetition is paid
     * for.
     */
    private void repetitionBetweenGathersCosts()
    {
        JitterYield fresh = new JitterYield();
        JitterYield repeat = new JitterYield();
        for (int round = 0; round < 6; round++)
        {
            // A different LCG seed per round against the same one replayed.
            feed(fresh, 1, 40, 32, round + 1);
            feed(repeat, 1, 40, 32, 1);
        }

        Assert.equal("same sample count", fresh.samples(), repeat.samples());
        Assert.isTrue("replayed gathers credit less than fresh ones: "
                + repeat.creditedBits() + " vs " + fresh.creditedBits(),
                repeat.creditedBits() < fresh.creditedBits());
    }

    /**
     * Bits alone are not enough to stop.
     *
     * Below MinEntropy.MIN_SAMPLES_FOR_CONFIDENCE this project does not publish a
     * bounded figure at all, and sizing a permanent key from an estimate too
     * small to publish would be using the estimator outside its stated domain.
     * On the two coarse-clock handsets measured it is this floor that decides the
     * gather count, not the target.
     */
    private void theSampleFloorHolds()
    {
        // 128 samples of a 16-value alphabet: worth well over 256 bits by the
        // arithmetic, and still not enough samples to say so.
        JitterYield y = feed(new JitterYield(), 4, 32, 16, 5);
        Assert.equal("128 samples", 128, y.samples());
        Assert.isTrue("the arithmetic alone would be satisfied: "
                + y.creditedBits(), y.creditedBits() >= 256);
        Assert.isFalse("but the sample floor is not", y.enough(256));

        feed(y, 4, 32, 16, 9);
        Assert.equal("256 samples", 256, y.samples());
        Assert.isTrue("now it is enough", y.enough(256));
    }

    private void roundsAreCounted()
    {
        JitterYield y = feed(new JitterYield(), 3, 10, 4, 1);
        Assert.equal("three rounds", 3, y.rounds());
        Assert.equal("thirty samples", 30, y.samples());
        Assert.isTrue("repeats are reported per thousand",
                y.repeatsPerThousand() >= 0 && y.repeatsPerThousand() <= 1000);
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Feed {@code rounds} gathers of {@code perRound} samples drawn from
     * {@code distinct} values by an LCG.
     *
     * An LCG rather than a cycle: adjacent samples of a round-robin stream are a
     * function of each other, the serial-correlation check would - correctly -
     * halve the credit, and the case would then be testing the discount rather
     * than the thing it means to test.
     */
    private static JitterYield feed(JitterYield y, int rounds, int perRound,
                                    int distinct, int seed)
    {
        int state = seed;
        for (int r = 0; r < rounds; r++)
        {
            y.startRound();
            for (int i = 0; i < perRound; i++)
            {
                state = state * 1664525 + 1013904223;
                y.sample(1100 + ((state >>> 16) & 0x7fff) % distinct, 1000L + i);
            }
            y.endRound();
        }
        return y;
    }
}
