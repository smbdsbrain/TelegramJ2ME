package tgtest;

import tg.crypto.Entropy;
import tg.crypto.IntHistogram;
import tg.crypto.MinEntropy;
import tg.crypto.Rng;
import tg.plat.EntropyProbe;
import tg.plat.KeyTimingProbe;

/**
 * The measurement machinery behind the on-device entropy probe.
 *
 * Like {@link RngTest} this cannot test randomness quality - the open question
 * is what the handset's sources are worth, and no desktop can answer that. What
 * it can do is prove that the instrument is trustworthy before it is pointed at
 * the handset, which matters here more than usual: the result of the device run
 * decides whether the project's "seeding has not been verified" caveat gets
 * lifted, narrowed, or made worse. An estimator that flattered its input would
 * turn that decision into a rubber stamp.
 *
 * The central property is one-sided. {@link MinEntropy} may report less
 * min-entropy than a source really has; it must never report more. Both
 * directions are checked against {@code Math.log}, which is available here
 * because {@code tools/build.ps1} compiles {@code test/} at source 1.6 against
 * the full JDK - and is denied on the device by
 * {@code config/cldc11-midp20-api.txt}, which is exactly why the shipped code
 * computes logarithms with integers.
 */
public final class EntropyTest implements Test
{
    private static final double LN2 = Math.log(2.0);

    public String name()
    {
        return "crypto/entropy";
    }

    public void run() throws Exception
    {
        eighthsKnownValues();
        eighthsNeverOptimistic();
        eighthsNeverTooPessimistic();
        eighthsMonotone();
        eighthsDegenerate();
        integerSqrt();
        confidenceBound();
        bitsFormatting();
        histogramCounts();
        histogramHandlesExtremeKeys();
        histogramOverflowDegradesSafely();
        quantileLevelsSurviveBimodalInput();
        histogramProbingTerminates();
        correlationIsDetected();
        independentInputIsNotFlagged();
        entropyGathers();
        jitterSinkObserves();
        gatherReportsTheSamplesItFoldedIn();
        jitterDoesNotHangOnEmptyWindow();
        userInputVaries();
        estimateStaysHonest();
        reportsFitTheScreen();
        keyTimingHandlesTooFewPresses();
        keyTimingFindsQuantisation();
    }

    // ------------------------------------------------------------ estimator

    /**
     * Pins the fixed-point path. Powers of two are exact; the rest were computed
     * by hand from the algorithm and are here so a rewrite cannot quietly shift
     * every published figure.
     */
    private void eighthsKnownValues()
    {
        Assert.equal("all identical -> 0 bits", 0, MinEntropy.eighths(256, 256));
        Assert.equal("n=1 degenerate", 0, MinEntropy.eighths(1, 1));
        Assert.equal("1 of 2 -> 1.000", 8, MinEntropy.eighths(1, 2));
        Assert.equal("1 of 256 -> 8.000", 64, MinEntropy.eighths(1, 256));
        Assert.equal("1 of 1024 -> 10.000", 80, MinEntropy.eighths(1, 1024));
        Assert.equal("3 of 256 -> 6.375", 51, MinEntropy.eighths(3, 256));
        Assert.equal("7 of 100 -> 3.750", 30, MinEntropy.eighths(7, 100));
        Assert.equal("2 of 3 -> 0.500", 4, MinEntropy.eighths(2, 3));
        Assert.equal("100 of 2000 -> 4.250", 34, MinEntropy.eighths(100, 2000));
    }

    /**
     * The property the whole design rests on: the reported figure is a lower
     * bound. If this ever fails, every number the handset prints is an
     * overstatement and the security conclusion drawn from it is wrong.
     */
    private void eighthsNeverOptimistic()
    {
        for (int n = 2; n <= 2000; n += 7)
        {
            for (int c = 1; c < n; c += 13)
            {
                double truth = -Math.log((double) c / (double) n) / LN2;
                double got = MinEntropy.eighths(c, n) / 8.0;
                Assert.isTrue("eighths(" + c + "," + n + ")=" + got
                        + " must not exceed " + truth, got <= truth + 1e-9);
            }
        }
    }

    /**
     * The converse. Truncating to eighths costs at most one eighth; anything
     * worse means a bug that halves or otherwise mangles the result, which would
     * be just as misleading - it would make a usable source look dead.
     */
    private void eighthsNeverTooPessimistic()
    {
        for (int n = 2; n <= 2000; n += 7)
        {
            for (int c = 1; c < n; c += 13)
            {
                double truth = -Math.log((double) c / (double) n) / LN2;
                double got = MinEntropy.eighths(c, n) / 8.0;
                Assert.isTrue("eighths(" + c + "," + n + ")=" + got
                        + " lags " + truth + " by more than 1/8",
                        got >= truth - 0.125 - 1e-9);
            }
        }
    }

    private void eighthsMonotone()
    {
        int[] sizes = { 3, 17, 256, 1000, 2000 };
        for (int s = 0; s < sizes.length; s++)
        {
            int n = sizes[s];
            int previous = Integer.MAX_VALUE;
            for (int c = 1; c < n; c++)
            {
                int e = MinEntropy.eighths(c, n);
                Assert.isTrue("more common mode cannot mean more entropy"
                        + " (n=" + n + " c=" + c + ")", e <= previous);
                previous = e;
            }
        }
    }

    /** A probe that throws while reporting is worse than one that reports zero. */
    private void eighthsDegenerate()
    {
        Assert.equal("cmax 0", 0, MinEntropy.eighths(0, 10));
        Assert.equal("n 0", 0, MinEntropy.eighths(10, 0));
        Assert.equal("negative cmax", 0, MinEntropy.eighths(-1, 10));
        Assert.equal("cmax == n", 0, MinEntropy.eighths(10, 10));
        Assert.equal("cmax > n", 0, MinEntropy.eighths(11, 10));
        Assert.equal("huge cmax", 0, MinEntropy.eighths(Integer.MAX_VALUE, 10));
        Assert.equal("both zero", 0, MinEntropy.eighths(0, 0));
    }

    private void integerSqrt()
    {
        for (int k = 0; k <= 3000; k++)
        {
            long sq = (long) k * k;
            Assert.equal("isqrt(" + k + "^2)", k, MinEntropy.isqrt(sq));
            if (k > 0)
            {
                Assert.equal("isqrt(" + k + "^2 - 1)", k - 1, MinEntropy.isqrt(sq - 1));
            }
        }
        Assert.equal("isqrt(0)", 0, MinEntropy.isqrt(0));
        Assert.equal("isqrt(-5) clamps", 0, MinEntropy.isqrt(-5));

        // Must terminate rather than oscillate, and must stay a floor.
        long big = MinEntropy.isqrt(Long.MAX_VALUE);
        Assert.isTrue("isqrt(MAX) squares below MAX", big <= 3037000499L);
        Assert.isTrue("isqrt(MAX) is close", big >= 3037000498L);
    }

    /**
     * The 99% bound must always cost entropy relative to the raw estimate, and
     * must still never exceed what a floating-point evaluation of the same
     * formula would give.
     */
    private void confidenceBound()
    {
        Assert.equal("100/2000", 31, MinEntropy.eighthsWithConfidence(100, 2000));
        Assert.equal("61/1000", 29, MinEntropy.eighthsWithConfidence(61, 1000));
        Assert.equal("20/2000", 47, MinEntropy.eighthsWithConfidence(20, 2000));
        Assert.equal("1000/2000", 7, MinEntropy.eighthsWithConfidence(1000, 2000));

        for (int n = 4; n <= 2000; n += 11)
        {
            for (int c = 1; c < n; c += 17)
            {
                int raw = MinEntropy.eighths(c, n);
                int conf = MinEntropy.eighthsWithConfidence(c, n);
                Assert.isTrue("bound cannot add entropy (c=" + c + " n=" + n + ")",
                        conf <= raw);

                double p = (double) c / n;
                double pu = p + 2.576 * Math.sqrt(p * (1 - p) / (n - 1));
                double truth = pu >= 1.0 ? 0.0 : -Math.log(pu) / LN2;
                Assert.isTrue("bound must not be optimistic (c=" + c + " n=" + n + ")",
                        conf / 8.0 <= truth + 1e-9);
            }
        }

        // At a fixed rate, more samples must tighten the bound toward the raw
        // figure - otherwise the "withhold below n=256" rule is pointless.
        int small = MinEntropy.eighthsWithConfidence(5, 100);
        int medium = MinEntropy.eighthsWithConfidence(50, 1000);
        int large = MinEntropy.eighthsWithConfidence(500, 10000);
        Assert.isTrue("bound tightens with n", small < medium && medium < large);
    }

    private void bitsFormatting()
    {
        Assert.equal("0", "0.000", MinEntropy.bits(0));
        Assert.equal("1/8", "0.125", MinEntropy.bits(1));
        Assert.equal("7/8", "0.875", MinEntropy.bits(7));
        Assert.equal("1", "1.000", MinEntropy.bits(8));
        Assert.equal("6.375", "6.375", MinEntropy.bits(51));
        Assert.equal("negative clamps", "0.000", MinEntropy.bits(-3));

        // Every rendering must have exactly three decimals, or the columns stop
        // lining up on a 34-column screen and the report becomes unreadable.
        for (int e = 0; e < 200; e++)
        {
            String s = MinEntropy.bits(e);
            int dot = s.indexOf('.');
            Assert.isTrue("bits(" + e + ")=" + s + " has a dot", dot > 0);
            Assert.equal("bits(" + e + ") has 3 decimals", 3, s.length() - dot - 1);
        }
    }

    // ------------------------------------------------------------ histogram

    private void histogramCounts()
    {
        IntHistogram h = new IntHistogram(256);
        for (int i = 0; i < 1000; i++) { h.add(i % 50); }

        Assert.equal("distinct", 50, h.distinct());
        Assert.equal("total", 1000, h.total());
        Assert.equal("maxCount", 20, h.maxCount());
        Assert.equal("no overflow", 0, h.overflow());
        Assert.equal("min", 0, h.minValue());
        Assert.equal("max", 49, h.maxValue());
        Assert.equal("sum", 1000L * 49 / 2, h.sum());
        Assert.equal("countOf(7)", 20, h.countOf(7));
        Assert.equal("countOf(unseen)", 0, h.countOf(999));

        IntHistogram same = new IntHistogram(64);
        for (int i = 0; i < 1000; i++) { same.add(42); }
        Assert.equal("single value distinct", 1, same.distinct());
        Assert.equal("single value maxCount", 1000, same.maxCount());
        Assert.equal("single value mode", 42, same.modeValue());
        Assert.equal("identical input is 0 bits", 0,
                MinEntropy.eighths(same.maxCount(), (int) same.total()));
    }

    /**
     * Integer.MIN_VALUE is the key that breaks a table which reduces with '%'
     * instead of a mask: the remainder is negative and the probe walks off the
     * front of the array. Spin counts are not supposed to reach it, but a probe
     * that dies mid-measurement on a handset is unrecoverable.
     */
    private void histogramHandlesExtremeKeys()
    {
        IntHistogram h = new IntHistogram(64);
        int[] keys = { Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1, 1, -1000000 };
        for (int i = 0; i < keys.length; i++)
        {
            h.add(keys[i]);
            h.add(keys[i]);
        }
        Assert.equal("all extreme keys stored", keys.length, h.distinct());
        for (int i = 0; i < keys.length; i++)
        {
            Assert.equal("countOf(" + keys[i] + ")", 2, h.countOf(keys[i]));
        }
        Assert.equal("min", Integer.MIN_VALUE, h.minValue());
        Assert.equal("max", Integer.MAX_VALUE, h.maxValue());
    }

    /**
     * When the table fills, dropped samples are charged to the mode. The
     * resulting estimate must be no higher than the truth - overflow is allowed
     * to lose precision, never to invent entropy.
     */
    private void histogramOverflowDegradesSafely()
    {
        IntHistogram h = new IntHistogram(64);
        for (int i = 0; i < 200; i++) { h.add(i); }

        Assert.isTrue("table overflowed", h.overflow() > 0);
        Assert.equal("every sample counted", 200, h.total());

        // The input is uniform over 200 values: 7.64 bits.
        double truth = -Math.log(1.0 / 200.0) / LN2;
        double got = MinEntropy.eighths(h.conservativeMaxCount(), (int) h.total()) / 8.0;
        Assert.isTrue("overflow estimate " + got + " must not exceed " + truth,
                got <= truth + 1e-9);
        Assert.isTrue("conservative count >= observed mode",
                h.conservativeMaxCount() >= h.maxCount());
    }

    /**
     * Regression: equal-width levels cannot carry the serial-correlation check.
     *
     * The Alcatel OT-810D produced a bimodal spread - most spin counts near a
     * full tick around 1200, a real minority near zero where the loop entered
     * close to a tick boundary. Both linear schemes tried on it put over 95% of
     * samples into a single level: min..max reported the mode holding 92%, and
     * a mean +- 3 MAD band, which assumes the low values are outliers rather
     * than a population, made it worse still at 95% and cost raw resolution
     * besides. Either way the pair estimator had nothing to compare and the
     * headline shipped marked UNCHECKED - and that check is the only guard
     * against MCV overstating a correlated source.
     *
     * Equal-population cuts make the level distribution uniform by
     * construction, so the check works whatever shape the source has. This test
     * pins the difference between the two schemes, not the absolute numbers.
     */
    private void quantileLevelsSurviveBimodalInput()
    {
        int n = 1730;
        int[] samples = new int[n];
        for (int i = 0; i < n; i++)
        {
            samples[i] = (i % 8 == 0)
                    ? 3 + (i * 29) % 260              // entered near a boundary
                    : 1120 + (i * 17) % 162;          // a full tick
        }

        IntHistogram calib = new IntHistogram(1024);
        for (int i = 0; i < n; i++) { calib.add(samples[i]); }

        int lo = calib.minValue();
        int width = calib.maxValue() - lo + 1;
        int linear = coarseEntropy(samples, levelsByWidth(samples, lo, width));

        int[] cuts = calib.quantileCuts(8);
        Assert.equal("seven cuts for eight levels", 7, cuts.length);
        for (int i = 1; i < cuts.length; i++)
        {
            Assert.isTrue("cuts ascend", cuts[i] >= cuts[i - 1]);
        }
        int quantile = coarseEntropy(samples, levelsByCuts(samples, cuts));

        // Not "exactly zero": how far the linear scheme collapses depends on how
        // skewed the input is - the handset's real data left 95.3% in the modal
        // level and rounded to 0.000, this synthetic one leaves 87.5%. The claim
        // under test is that equal-width is useless for the pair check while
        // equal-population is close to the 3.000 bits eight levels can carry.
        Assert.isTrue("equal-width collapses into one level, got "
                + MinEntropy.bits(linear), linear <= 2);
        Assert.isTrue("equal-population stays near 3.000, got "
                + MinEntropy.bits(quantile), quantile >= 20);
        Assert.isTrue("and is far better than equal-width",
                quantile >= linear + 16);
    }

    private static int[] levelsByWidth(int[] samples, int lo, int width)
    {
        int[] out = new int[samples.length];
        for (int i = 0; i < samples.length; i++)
        {
            int level = ((samples[i] - lo) * 8) / width;
            out[i] = level > 7 ? 7 : (level < 0 ? 0 : level);
        }
        return out;
    }

    private static int[] levelsByCuts(int[] samples, int[] cuts)
    {
        int[] out = new int[samples.length];
        for (int i = 0; i < samples.length; i++)
        {
            int level = 0;
            while (level < cuts.length && samples[i] > cuts[level]) { level++; }
            out[i] = level;
        }
        return out;
    }

    /** The coarse-level entropy the probe's pair check is built on. */
    private static int coarseEntropy(int[] samples, int[] levels)
    {
        IntHistogram coarse = new IntHistogram(16);
        for (int i = 0; i < levels.length; i++) { coarse.add(levels[i]); }
        return MinEntropy.eighths(coarse.maxCount(), levels.length);
    }

    /** Every key colliding must still terminate rather than probe forever. */
    private void histogramProbingTerminates()
    {
        IntHistogram h = new IntHistogram(16);
        for (int i = 0; i < 64; i++) { h.add(i * 65536); }
        Assert.equal("all samples accounted for", 64, h.total());
        Assert.isTrue("distinct is bounded by capacity", h.distinct() <= 16);
        Assert.equal("the rest overflowed", 64 - h.distinct(), h.overflow());
    }

    // ---------------------------------------------------------- correlation

    /**
     * A perfectly predictable alternating sequence scores a full bit per sample
     * on the single-symbol estimator. Without the pair check the probe would
     * report 1.000 bits/sample for a source that has none, which is the most
     * dangerous way this code could be wrong.
     *
     * What the pair check actually buys is bounded, and this test pins the
     * bound rather than an aspiration. Two pairs - (0,1) and (1,0) - occur
     * equally often, so a pair-MCV sees one bit per pair, i.e. half a bit per
     * sample, and the discount halves the estimate. It does not reach zero:
     * driving a deterministic alternation to zero needs longer tuples or a
     * conditional estimator, which is the part of SP 800-90B the report's note
     * says is not implemented. Detecting the correlation and paying for it is
     * what is claimed; eliminating it is not.
     */
    private void correlationIsDetected()
    {
        IntHistogram singles = new IntHistogram(32);
        IntHistogram pairs = new IntHistogram(512);
        int previous = -1;
        for (int i = 0; i < 1000; i++)
        {
            int v = i & 1;
            singles.add(v);
            if (previous >= 0) { pairs.add(previous * 16 + v); }
            previous = v;
        }

        int single = MinEntropy.eighths(singles.maxCount(), (int) singles.total());
        int pairPer = MinEntropy.eighths(pairs.maxCount(), (int) pairs.total()) / 2;

        Assert.equal("alternating looks like 1 bit alone", 8, single);
        Assert.equal("pairs cut it to half a bit", 3, pairPer);
        Assert.isTrue("the discount fires", pairPer < single);
        Assert.equal("estimate drops from 1.000 to 0.375", 3,
                MinEntropy.discount(single, pairPer, single));
    }

    /** The converse: genuinely independent input must not trip the discount. */
    private void independentInputIsNotFlagged()
    {
        Rng rng = Rng.forTesting(Assert.ascii("iid-source"));
        IntHistogram singles = new IntHistogram(64);
        IntHistogram pairs = new IntHistogram(512);
        int previous = -1;
        for (int i = 0; i < 4000; i++)
        {
            int v = rng.nextInt(16);
            singles.add(v);
            if (previous >= 0) { pairs.add(previous * 16 + v); }
            previous = v;
        }

        int single = MinEntropy.eighths(singles.maxCount(), (int) singles.total());
        int pairPer = MinEntropy.eighths(pairs.maxCount(), (int) pairs.total()) / 2;

        Assert.isTrue("uniform over 16 is near 4 bits, got " + MinEntropy.bits(single),
                single >= 28 && single <= 32);
        Assert.isTrue("no false correlation alarm: single=" + MinEntropy.bits(single)
                + " pair/2=" + MinEntropy.bits(pairPer), pairPer >= single - 4);
    }

    // -------------------------------------------------------------- Entropy

    private void entropyGathers()
    {
        byte[] a = Entropy.gather();
        byte[] b = Entropy.gather();
        byte[] c = Entropy.gather();

        Assert.equal("gather is a SHA-256 digest", 32, a.length);
        // The same assertion the on-device screen makes, now also a build gate.
        Assert.isTrue("gather() 1 != 2", !sameBytes(a, b));
        Assert.isTrue("gather() 2 != 3", !sameBytes(b, c));
        Assert.isTrue("gather() 1 != 3", !sameBytes(a, c));
    }

    /**
     * The sink must observe the real loop without altering what it returns:
     * {@code Rng()} calls the no-sink overload, and if the two diverged the
     * probe would be measuring something the production path does not do.
     */
    private void jitterSinkObserves()
    {
        final int[] count = new int[1];
        final long[] lastNow = new long[1];
        final boolean[] ok = { true };

        byte[] digest = Entropy.collectJitter(60, new Entropy.JitterSink()
        {
            public void sample(long spins, long nowMillis)
            {
                if (spins < 1) { ok[0] = false; }
                if (count[0] > 0 && nowMillis < lastNow[0]) { ok[0] = false; }
                lastNow[0] = nowMillis;
                count[0]++;
            }
        });

        Assert.equal("jitter digest length", 32, digest.length);
        Assert.isTrue("sink saw at least one sample", count[0] >= 1);
        Assert.isTrue("spins >= 1 and time never went backwards", ok[0]);

        byte[] again = Entropy.collectJitter(60);
        Assert.equal("no-sink overload still works", 32, again.length);
        Assert.isTrue("two jitter runs differ", !sameBytes(digest, again));
    }

    /**
     * The overload the seeding barrier sizes itself from.
     *
     * Its value rests entirely on the samples being the ones that went into the
     * digest returned - not a second, similar measurement. If observing changed
     * the window, or the sink saw nothing while the digest saw samples, the
     * barrier would be counting bits that are not in the pool.
     */
    private void gatherReportsTheSamplesItFoldedIn()
    {
        final int[] count = new int[1];
        byte[] observed = Entropy.gather(new Entropy.JitterSink()
        {
            public void sample(long spins, long nowMillis) { count[0]++; }
        });

        Assert.equal("observed gather is still a digest", 32, observed.length);
        Assert.isTrue("the sink saw the window's samples", count[0] >= 1);

        byte[] plain = Entropy.gather();
        Assert.equal("the no-sink overload still works", 32, plain.length);
        Assert.isTrue("observing does not make two gathers agree",
                !sameBytes(observed, plain));

        // The window is public so nothing has to hardcode 120 to reason about
        // the sample count a handset will get out of one gather.
        Assert.isTrue("the jitter window is a positive number of ms",
                Entropy.jitterWindowMillis() > 0);
    }

    /**
     * A zero or negative window must return immediately. The same guard is what
     * stops a handset with a stopped clock from hanging inside {@code Rng()},
     * which no desktop run can reproduce but every caller depends on.
     */
    private void jitterDoesNotHangOnEmptyWindow()
    {
        Assert.equal("zero window", 32, Entropy.collectJitter(0).length);
        Assert.equal("negative window", 32, Entropy.collectJitter(-1).length);

        final int[] seen = new int[1];
        Entropy.collectJitter(0, new Entropy.JitterSink()
        {
            public void sample(long spins, long nowMillis) { seen[0]++; }
        });
        Assert.equal("zero window yields no samples", 0, seen[0]);
    }

    private void userInputVaries()
    {
        byte[] a = Entropy.fromUserInput(1, 1000L);
        byte[] b = Entropy.fromUserInput(2, 1000L);
        byte[] c = Entropy.fromUserInput(1, 1001L);

        Assert.equal("digest length", 32, a.length);
        Assert.isTrue("key code changes the result", !sameBytes(a, b));
        Assert.isTrue("timestamp changes the result", !sameBytes(a, c));
        // Not asserted: that the same arguments reproduce. They do not, because
        // freeMemory() is folded in too - which is the point of the method.
    }

    /**
     * A tripwire, not a behaviour test.
     *
     * 58 is the figure measured on an Alcatel OT-810D and written up in
     * docs/hardware/alcatel-ot810d.md. Changing it means either a new device run
     * or a claim with nothing behind it, and whoever does it should have to come
     * through here to find out which.
     *
     * The upper guard matters as much as the value: a single gather must stay
     * visibly short of the 256 bits a DH secret needs, because the moment this
     * number reaches that, every caller's reason to fold in more entropy
     * quietly disappears.
     *
     * Nothing sizes itself from this figure any more - the barrier measures its
     * own yield - and {@code tgtest.SourceGuardTest} is what holds that. This
     * stays because the on-device reports quote it.
     */
    private void estimateStaysHonest()
    {
        int claimed = Entropy.estimatedBitsPerGather();
        Assert.equal("measured on the OT-810D; see docs/hardware/",
                58, claimed);
        Assert.isTrue("one gather must not look sufficient on its own",
                claimed < 256);
    }

    // --------------------------------------------------------- report shape

    /**
     * The reports are read on a feature-phone screen. TextScreen wraps, but a
     * report that wraps is a report nobody can line up column-wise, so the width
     * is enforced here rather than discovered on the handset.
     */
    private void reportsFitTheScreen()
    {
        // Same sizes the MIDlets use: a stride or a byte count one digit longer
        // than the test exercised is exactly how a width regression would slip
        // through to the handset.
        checkLines("clockReport", EntropyProbe.clockReport());
        checkLines("hashCodeReport", EntropyProbe.hashCodeReport(256));
        checkLines("memoryReport", EntropyProbe.memoryReport(256));
        checkLines("jitterReport", EntropyProbe.jitterReport(
                EntropyProbe.measuredTickMillis(), 300));

        long[] times = new long[40];
        int[] codes = new int[40];
        long t = 100000L;
        for (int i = 0; i < 40; i++)
        {
            t += 137 + (i * 13) % 71;
            times[i] = t;
            codes[i] = 48 + (i % 10);
        }
        checkLines("keyTiming", KeyTimingProbe.analyse(times, codes, 40, 1));
    }

    private void checkLines(String what, String[] lines)
    {
        Assert.isTrue(what + " returns lines", lines != null && lines.length > 0);
        for (int i = 0; i < lines.length; i++)
        {
            Assert.isTrue(what + "[" + i + "] is not null", lines[i] != null);
            Assert.isTrue(what + "[" + i + "] is " + lines[i].length()
                    + " chars, over " + EntropyProbe.MAX_LINE + ": " + lines[i],
                    lines[i].length() <= EntropyProbe.MAX_LINE);
        }
    }

    // ----------------------------------------------------------- key timing

    private void keyTimingHandlesTooFewPresses()
    {
        checkLines("empty", KeyTimingProbe.analyse(new long[4], new int[4], 0, 1));
        checkLines("one press", KeyTimingProbe.analyse(new long[4], new int[4], 1, 1));
        checkLines("null input", KeyTimingProbe.analyse(null, null, 10, 1));
    }

    /**
     * The finding this screen exists to be able to report: event timestamps
     * quantised coarser than the clock tick, so the interval carries far less
     * than its digits suggest and the keyboard-entropy plan in Entropy's javadoc
     * may not be viable on that handset.
     *
     * Two quanta, because they behave differently and conflating them is how a
     * measurement lies. At 16 ms the low four bits really are constant. At 15 ms
     * they cycle and look random while carrying nothing independent - they are a
     * function of d/15, which H_raw already counts. The report must not call the
     * second case "dead low bits", and must not let anyone add them to H_raw.
     */
    private void keyTimingFindsQuantisation()
    {
        String[] fifteen = quantisedRun(15);
        Assert.isTrue("reports the 15 ms gcd",
                contains(fifteen, "gcd(deltas) = 15 ms"));
        Assert.isTrue("names the quantisation",
                contains(fifteen, "deltas quantised at 15 ms:"));
        Assert.isTrue("warns against double-counting",
                contains(fifteen, "do not add"));
        Assert.isTrue("notes the timestamps are coarser than the tick",
                contains(fifteen, "than the 1 ms clock tick."));
        Assert.isFalse("15 ms does not zero the low bits",
                contains(fifteen, "d & 15 -> 0.000"));
        Assert.isFalse("so it must not claim they are constant",
                contains(fifteen, "low 4 bits are CONSTANT"));

        String[] sixteen = quantisedRun(16);
        Assert.isTrue("reports the 16 ms gcd",
                contains(sixteen, "gcd(deltas) = 16 ms"));
        Assert.isTrue("16 ms does zero the low bits",
                contains(sixteen, "d & 15 -> 0.000"));
        Assert.isTrue("and says so",
                contains(sixteen, "low 4 bits are CONSTANT"));
    }

    private String[] quantisedRun(int quantum)
    {
        long[] times = new long[40];
        int[] codes = new int[40];
        long t = 0;
        for (int i = 0; i < 40; i++)
        {
            t += (long) quantum * (7 + (i * 3) % 11);
            times[i] = t;
            codes[i] = 50 + (i % 8);
        }
        return KeyTimingProbe.analyse(times, codes, 40, 1);
    }

    // -------------------------------------------------------------- helpers

    private static boolean contains(String[] lines, String needle)
    {
        for (int i = 0; i < lines.length; i++)
        {
            if (lines[i] != null && lines[i].indexOf(needle) >= 0) { return true; }
        }
        return false;
    }

    private static boolean sameBytes(byte[] a, byte[] b)
    {
        if (a.length != b.length) { return false; }
        for (int i = 0; i < a.length; i++)
        {
            if (a[i] != b[i]) { return false; }
        }
        return true;
    }
}
