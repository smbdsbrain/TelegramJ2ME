package tg.plat;

import java.util.Vector;

import tg.crypto.Entropy;
import tg.diag.Diag;

/**
 * Measurement of the entropy sources {@link Entropy} scrapes, on the handset.
 *
 * <h3>Why this exists</h3>
 * {@code Entropy.gather()} returns a SHA-256 digest, so every raw quantity it
 * folds in - spin counts, hash codes, heap readings - is unobservable from
 * outside. The consequence is recorded in the project's own documentation: the
 * seeding "has not been verified on hardware", and
 * {@code Entropy.estimatedBitsPerGather()} returns 0 because claiming a number
 * would be dishonest. A previous run of {@code CryptoMidlet}'s entropy screen on
 * a physical handset produced "no obvious repetition; raw values not recorded",
 * which is not a measurement.
 *
 * This class produces the numbers instead, on the device, with no network
 * involved: results are computed here and displayed, because the handsets this
 * targets have no WiFi and metered GPRS.
 *
 * <h3>The five questions</h3>
 * <ol>
 *   <li>what is the real granularity of {@code System.currentTimeMillis()} -
 *       everything else depends on it, so it runs first;</li>
 *   <li>how much min-entropy is in a raw jitter spin count;</li>
 *   <li>does {@code new Object().hashCode()} vary, or is it a counter, or is it
 *       constant for the allocate-and-drop pattern {@code gather()} uses;</li>
 *   <li>does {@code freeMemory()} move at all;</li>
 *   <li>does a cold boot ever reproduce a seed - see {@link EntropyLog}.</li>
 * </ol>
 *
 * <h3>Conservatism</h3>
 * Every estimate here is a lower bound and every rounding goes down; see
 * {@link MinEntropy}. Bucketing merges distinct values, which raises the modal
 * count, which lowers the reported entropy. Histogram overflow is charged to the
 * mode. Where a measurement cannot support a figure - too few samples, a frozen
 * clock - the report says so rather than printing one.
 *
 * <h3>Not hanging is a requirement</h3>
 * A probe that wedges the test session is worse than no probe. Every loop here
 * is bounded by an iteration count as well as by time, all working memory is
 * allocated before sampling starts so an OOM lands somewhere recoverable, and
 * nothing allocates inside a sampling loop.
 */
public final class EntropyProbe
{
    /** Progress callback so the UI thread can repaint during a 20-second run. */
    public interface Progress
    {
        void step(String what, int done, int total);
    }

    /** Report lines are sized for a feature-phone screen. */
    public static final int MAX_LINE = 34;

    private static final int TOTAL_STEPS = 5;

    /** Clock calibration duration. Long enough to see a 20 ms tick many times. */
    private static final int CLOCK_SAMPLE_MS = 1500;

    /** Guard for every "spin until the clock moves" loop in this class. */
    private static final long SPIN_CAP = 4000000L;

    /** Upper bound on jitter samples; beyond this the tables, not the clock, bind. */
    private static final int JITTER_MAX_SAMPLES = 2000;

    /** Wall-clock ceiling for the jitter phase. */
    private static final int JITTER_BUDGET_MS = 20000;

    /** Below this the MCV estimate is noise; report it as unmeasured. */
    private static final int JITTER_MIN_SAMPLES = 64;

    /** 8 levels -> 64 pairs, dense enough to estimate at n in the low thousands. */
    private static final int COARSE_LEVELS = 8;

    private static final int OBJECTS = 256;
    private static final int MEM_READS = 256;

    // Cached across a run so the verdict block can be assembled at the end.
    private static int tickMillis = -1;
    private static boolean clockFrozen;
    private static int jitterEighths = -1;
    private static int samplesPerGather = -1;

    private EntropyProbe() { }

    /** The measured clock tick in ms, or -1 if {@link #clockReport} has not run. */
    public static int measuredTickMillis()
    {
        return tickMillis;
    }

    // ------------------------------------------------------------ the suite

    /**
     * Everything, in dependency order, with the verdict block first.
     *
     * Runs for up to about 25 seconds. Must not be called on the UI thread.
     *
     * @param p may be null
     */
    public static String[] run(Progress p)
    {
        Vector v = new Vector(80);

        // Reset, so a second run in the same session does not inherit the first
        // run's cached figures if a phase fails this time.
        tickMillis = -1;
        clockFrozen = false;
        jitterEighths = -1;
        samplesPerGather = -1;

        step(p, "clock", 1);
        String[] clock = clockReport();

        step(p, "jitter", 2);
        String[] jitter = jitterReport(tickMillis, JITTER_BUDGET_MS);

        step(p, "hashCode", 3);
        String[] hash = hashCodeReport(OBJECTS);

        step(p, "freeMemory", 4);
        String[] mem = memoryReport(MEM_READS);

        step(p, "restart log", 5);
        String[] restart = EntropyLog.report();

        appendAll(v, verdict());
        v.addElement("");
        v.addElement("-- a. clock --");
        appendAll(v, clock);
        v.addElement("");
        v.addElement("-- b. jitter spins --");
        appendAll(v, jitter);
        v.addElement("");
        v.addElement("-- c. hashCode --");
        appendAll(v, hash);
        v.addElement("");
        v.addElement("-- d. freeMemory --");
        appendAll(v, mem);
        v.addElement("");
        v.addElement("-- e. cross-restart --");
        appendAll(v, restart);

        return toArray(v);
    }

    /**
     * The headline. Deliberately built from the cached per-phase figures rather
     * than re-measuring, so it can never disagree with the detail below it.
     */
    private static String[] verdict()
    {
        Vector v = new Vector(12);
        v.addElement("== VERDICT ==");

        if (clockFrozen)
        {
            v.addElement("clock FROZEN - jitter is dead");
            v.addElement("on this runtime. seeding is");
            v.addElement("NOT SAFE. see section a.");
            return toArray(v);
        }

        if (jitterEighths < 0 || samplesPerGather < 0)
        {
            v.addElement("jitter unmeasured - see b.");
            v.addElement("no bits/gather figure can be");
            v.addElement("given.");
            return toArray(v);
        }

        int perGather = (jitterEighths * samplesPerGather) / 8;
        v.addElement("jitter " + MinEntropy.bits(jitterEighths) + " bits/sample");
        v.addElement("  x " + samplesPerGather + " samples per gather()");
        v.addElement("LOWER BOUND = " + perGather + " bits/gather");

        if (perGather <= 0)
        {
            v.addElement("ZERO. seeding is NOT SAFE.");
        }
        else
        {
            int gathers = (256 + perGather - 1) / perGather;
            v.addElement("256 bits needs " + gathers + " gather(s)");
            if (perGather >= 256)
            {
                v.addElement("one gather() suffices");
            }
            else
            {
                v.addElement("NOT SUFFICIENT AS ONE GATHER");
            }
        }
        v.addElement("jitter only; other sources are");
        v.addElement("counted at zero on purpose.");
        return toArray(v);
    }

    // -------------------------------------------------------------- a. clock

    /**
     * Granularity of {@code System.currentTimeMillis()}.
     *
     * The tick is the floor on everything else: a busy loop can only extract
     * jitter from a clock that moves, and an inter-key interval cannot resolve
     * anything finer than one tick. A handset whose clock advances in 20 ms
     * steps has 20 ms of quantisation in every timing-derived source here.
     */
    public static String[] clockReport()
    {
        Vector v = new Vector(10);
        IntHistogram deltas = new IntHistogram(128);

        long start = System.currentTimeMillis();
        long previous = start;
        long reads = 0;
        int changes = 0;
        boolean stalled = false;

        while (System.currentTimeMillis() - start < CLOCK_SAMPLE_MS)
        {
            long spins = 0;
            long now = previous;
            while (now == previous && spins < SPIN_CAP)
            {
                now = System.currentTimeMillis();
                spins++;
            }
            reads += spins;
            if (now == previous) { stalled = true; break; }

            long d = now - previous;
            deltas.add(d > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) d);
            previous = now;
            changes++;
        }

        if (stalled || changes == 0)
        {
            clockFrozen = true;
            tickMillis = -1;
            v.addElement("CLOCK DID NOT ADVANCE");
            v.addElement("in " + SPIN_CAP + " reads.");
            v.addElement("every timing source here is");
            v.addElement("dead on this runtime.");
            v.addElement("FATAL");
            return toArray(v);
        }

        clockFrozen = false;
        int tick = deltas.minValue();
        tickMillis = tick;

        v.addElement("changes = " + changes + " in " + CLOCK_SAMPLE_MS + " ms");
        v.addElement("min delta = " + tick + " ms  <- tick");
        v.addElement("max delta = " + deltas.maxValue() + " ms");
        v.addElement("distinct deltas = " + deltas.distinct());
        v.addElement("top: " + deltas.topValues(3, MAX_LINE - 5));
        v.addElement("reads per tick = " + (reads / changes));
        v.addElement(clockVerdict(tick));
        return toArray(v);
    }

    private static String clockVerdict(int tick)
    {
        if (tick <= 2) { return "FINE (<= 2 ms)"; }
        if (tick < 10) { return "USABLE (3-9 ms)"; }
        return "COARSE (>= 10 ms)";
    }

    // ------------------------------------------------------------- b. jitter

    /**
     * Min-entropy of a raw jitter spin count.
     *
     * Runs {@code Entropy.collectJitter} - the same call {@code Rng()} makes -
     * with an observing sink, so the numbers describe the production path and
     * not a copy of it.
     *
     * Two phases, because the spin range is unknown before the run: a short
     * calibration learns the range, then the measurement buckets against it.
     * Bucketing can only merge distinct values, so it can only lower the result.
     *
     * @param tick   measured clock tick in ms; -1 means the clock is frozen
     * @param budget wall-clock ceiling in ms
     */
    public static String[] jitterReport(int tick, int budget)
    {
        Vector v = new Vector(20);

        if (tick < 0 || clockFrozen)
        {
            v.addElement("skipped: the clock does not");
            v.addElement("advance, so there is nothing");
            v.addElement("to sample.");
            return toArray(v);
        }
        if (tick < 1) { tick = 1; }

        // Allocate everything before sampling: an OOM here is recoverable,
        // an OOM halfway through a run leaves nothing to report with.
        IntHistogram raw;
        IntHistogram coarse;
        IntHistogram pairs;
        IntHistogram calib;
        try
        {
            raw = new IntHistogram(1024);
            coarse = new IntHistogram(16);
            pairs = new IntHistogram(128);
            calib = new IntHistogram(1024);
        }
        catch (Throwable t)
        {
            v.addElement("insufficient memory for the");
            v.addElement("probe tables: " + Diag.className(t));
            return toArray(v);
        }

        int target = JITTER_MAX_SAMPLES;
        if (20000 / tick < target) { target = 20000 / tick; }
        if (target < 1) { target = 1; }

        // Calibration: enough samples to place quantile cuts, capped at 600 ms.
        int calibrateMs = 200 * tick;
        if (calibrateMs > 600) { calibrateMs = 600; }
        if (calibrateMs < tick) { calibrateMs = tick; }
        CalibrationSink range = new CalibrationSink(calib);
        Entropy.collectJitter(calibrateMs, range);

        if (range.count == 0)
        {
            v.addElement("calibration produced no");
            v.addElement("samples in " + calibrateMs + " ms.");
            v.addElement("unmeasured.");
            return toArray(v);
        }

        int lo = range.lo;
        int hi = range.hi;

        // Raw buckets: linear over the observed range. 512 buckets is ample
        // resolution for any plausible spread, so an outlier costs little here.
        int bandLo = lo;
        int bandWidth = (hi - lo) + 1;
        int step = (bandWidth / 512) + 1;

        // Coarse levels: equal population, not equal width. Spin counts are
        // bimodal - a full tick against a loop that entered near a boundary -
        // so any linear scale buries almost everything in one level and the
        // serial-correlation check has nothing to work with. See
        // IntHistogram.quantileCuts.
        int[] cuts = calib.quantileCuts(COARSE_LEVELS);

        int duration = target * tick;
        if (duration > budget) { duration = budget; }
        if (duration < tick) { duration = tick; }

        Collector c = new Collector(raw, coarse, pairs, bandLo, bandWidth, step, cuts);
        long began = System.currentTimeMillis();
        Entropy.collectJitter(duration, c);
        long elapsed = System.currentTimeMillis() - began;

        int n = c.count;
        v.addElement("n = " + n + " in " + elapsed + " ms");
        if (n == 0)
        {
            v.addElement("no samples. unmeasured.");
            return toArray(v);
        }

        v.addElement("range " + lo + ".." + hi + " step " + step);
        if (c.clamped > 0)
        {
            // Clamping folds an out-of-range sample into an end bucket, raising
            // its count and so lowering the reported entropy. Conservative, but
            // the reader should know how often it happened.
            v.addElement("clamped " + c.clamped + " outside range");
        }
        v.addElement("distinct " + raw.distinct()
                + " lvls " + (cuts.length + 1));
        v.addElement("first: " + c.firstValues(MAX_LINE - 7));

        if (n < JITTER_MIN_SAMPLES)
        {
            v.addElement("n < " + JITTER_MIN_SAMPLES + ": too few for MCV.");
            v.addElement("treat jitter as UNMEASURED.");
            v.addElement("the clock tick (" + tick + " ms) is");
            v.addElement("what limits the sample count.");
            return toArray(v);
        }

        int cmax = raw.conservativeMaxCount();
        int hRaw = MinEntropy.eighths(cmax, n);
        v.addElement("p_max = " + MinEntropy.permille(cmax, n) + " per 1000");
        v.addElement("H_raw = " + MinEntropy.bits(hRaw));

        int hConf = hRaw;
        if (n >= MinEntropy.MIN_SAMPLES_FOR_CONFIDENCE)
        {
            hConf = MinEntropy.eighthsWithConfidence(cmax, n);
            v.addElement("H_99% = " + MinEntropy.bits(hConf));
        }
        else
        {
            v.addElement("H_99% withheld (n < "
                    + MinEntropy.MIN_SAMPLES_FOR_CONFIDENCE + ")");
        }

        // Serial-correlation check. If adjacent pairs carry less than twice a
        // single sample's entropy, the samples are not independent and the MCV
        // figure is optimistic by exactly that ratio.
        //
        // Coarse levels rather than raw buckets because a pair estimator needs
        // enough samples per pair to mean anything: 8 levels give 64 pairs, so
        // n = 1700 leaves about 27 observations each. Raw buckets would give
        // thousands of near-unique pairs and a meaningless figure.
        int hCoarse = MinEntropy.eighths(coarse.conservativeMaxCount(), n);
        int hPairTotal = MinEntropy.eighths(pairs.conservativeMaxCount(), (int) pairs.total());
        int hPairPer = hPairTotal / 2;
        v.addElement("Hc = " + MinEntropy.bits(hCoarse)
                + " pair/2 = " + MinEntropy.bits(hPairPer));

        int headline = hConf;
        if (hCoarse <= 0)
        {
            // The coarse distribution is too concentrated for the ratio to mean
            // anything - the modal level holds essentially everything. Say so
            // rather than let a missing discount read as "no correlation found".
            v.addElement("Hc = 0: coarse levels too");
            v.addElement("concentrated (mode holds "
                    + MinEntropy.permille(coarse.conservativeMaxCount(), n)
                    + "/1000)");
            v.addElement("for the pair check to mean");
            v.addElement("anything. headline is");
            v.addElement("UNCHECKED for serial");
            v.addElement("correlation.");
        }
        else if (hPairPer < hCoarse)
        {
            int discounted = MinEntropy.discount(hConf, hPairPer, hCoarse);
            if (discounted < headline) { headline = discounted; }
            v.addElement("serial correlation detected;");
            v.addElement("discounted by pair/2 over Hc.");
        }
        v.addElement("lag1 repeats = "
                + MinEntropy.permille(c.repeats, n) + " per 1000");
        v.addElement("headline H = " + MinEntropy.bits(headline));

        jitterEighths = headline;
        samplesPerGather = measureSamplesPerGather();
        v.addElement("gather() takes " + samplesPerGather + " samples");
        v.addElement("=> " + ((headline * samplesPerGather) / 8) + " bits per gather()");
        v.addElement("note: MCV assumes IID. the pair");
        v.addElement("check is a correlation discount,");
        v.addElement("not the full SP 800-90B non-IID");
        v.addElement("track.");
        return toArray(v);
    }

    /**
     * How many jitter samples one {@code gather()} actually collects.
     *
     * Measured rather than derived, because {@code Entropy}'s sampling window is
     * private and a figure computed from the tick would drift the moment that
     * constant changed. This costs one real jitter window.
     */
    private static int measureSamplesPerGather()
    {
        CountingSink counter = new CountingSink();
        // Same window Entropy.gather() uses; it is private there, so the honest
        // way to learn the sample count is to run the real thing and count.
        Entropy.collectJitter(120, counter);
        return counter.count;
    }

    // ----------------------------------------------------------- c. hashCode

    /**
     * Behaviour of {@code new Object().hashCode()}, which {@code gather()} folds
     * in on the theory that it encodes an allocation address.
     *
     * Two variants, and the second is the one that decides the question:
     *
     * <ul>
     *   <li><b>held</b> - the objects stay reachable, so each gets a fresh
     *       address. A single dominant stride between consecutive values means
     *       the runtime is handing out a sequential counter, which a remote
     *       party can predict;</li>
     *   <li><b>dropped</b> - each object is discarded immediately. This is
     *       literally what {@code gather()} does, and if the allocator reuses the
     *       same slot then every call sees the same hash code and this source
     *       contributes exactly nothing.</li>
     * </ul>
     */
    public static String[] hashCodeReport(int n)
    {
        Vector v = new Vector(12);
        if (n < 2) { n = 2; }

        IntHistogram held = new IntHistogram(512);
        IntHistogram strides = new IntHistogram(128);
        IntHistogram dropped = new IntHistogram(512);
        Object[] keep = new Object[n];
        int[] codes = new int[n];

        for (int i = 0; i < n; i++)
        {
            keep[i] = new Object();
            codes[i] = keep[i].hashCode();
            held.add(codes[i]);
        }
        for (int i = 1; i < n; i++)
        {
            strides.add(codes[i] - codes[i - 1]);
        }

        StringBuffer first = new StringBuffer(24);
        for (int i = 0; i < 3 && i < n; i++)
        {
            if (i > 0) { first.append(' '); }
            first.append(Integer.toHexString(codes[i]));
        }

        v.addElement("held " + n + ": distinct " + held.distinct());
        v.addElement(" " + first.toString());
        int strideMax = strides.maxCount();
        int strideCount = (int) strides.total();
        v.addElement(" stride " + strides.modeValue() + " in "
                + strideMax + "/" + strideCount);
        if (strideCount > 0 && strideMax * 4 >= strideCount * 3)
        {
            v.addElement(" => SEQUENTIAL COUNTER");
        }
        else
        {
            v.addElement(" => H " + MinEntropy.bitsOf(strideMax, strideCount)
                    + " per allocation");
        }

        // One gc between the variants: it is not free on these VMs, and the
        // dropped-object measurement is about allocator reuse, which is exactly
        // what a collection makes visible.
        try { System.gc(); } catch (Throwable ignored) { }

        for (int i = 0; i < n; i++)
        {
            dropped.add(new Object().hashCode());
        }

        v.addElement("dropped " + n + ": distinct " + dropped.distinct());
        if (dropped.distinct() <= 1)
        {
            v.addElement(" => 0 bits inside gather()");
        }
        else
        {
            v.addElement(" => H " + MinEntropy.bitsOf(
                    dropped.conservativeMaxCount(), n) + " per call");
        }
        return toArray(v);
    }

    // --------------------------------------------------------- d. freeMemory

    /**
     * Whether the heap readings {@code gather()} folds in move at all.
     *
     * Three separate questions: does {@code freeMemory()} change when nothing is
     * allocated between reads (on most VMs it does not), does it change when
     * something is, and is {@code totalMemory()} fixed. A VM that answers "no"
     * to all three contributes nothing from this source, and a run-to-run
     * constant is worse than nothing because it looks like data.
     */
    public static String[] memoryReport(int n)
    {
        Vector v = new Vector(10);
        if (n < 2) { n = 2; }

        Runtime rt = Runtime.getRuntime();
        IntHistogram idle = new IntHistogram(256);
        IntHistogram busy = new IntHistogram(256);
        IntHistogram totals = new IntHistogram(32);

        for (int i = 0; i < n; i++)
        {
            idle.add((int) rt.freeMemory());
            totals.add((int) rt.totalMemory());
        }

        Object[] churn = new Object[n];
        for (int i = 0; i < n; i++)
        {
            churn[i] = new Object();
            busy.add((int) rt.freeMemory());
        }
        // Count the churn afterwards so it stays reachable past the readings.
        // Otherwise the VM is entitled to collect it mid-loop and the numbers
        // describe the collector rather than the allocation.
        int alive = 0;
        for (int i = 0; i < n; i++)
        {
            if (churn[i] != null) { alive++; }
        }

        v.addElement("allocated " + alive + " objects");
        v.addElement("idle reads: " + idle.distinct() + " distinct/" + n);
        v.addElement("after alloc: " + busy.distinct() + " distinct/" + n);
        v.addElement("spread = " + (busy.maxValue() - busy.minValue()) + " b");
        v.addElement("totalMemory: " + totals.distinct() + " distinct");
        v.addElement("free now = " + rt.freeMemory());

        int best = idle.distinct() > busy.distinct() ? idle.distinct() : busy.distinct();
        if (best <= 1)
        {
            v.addElement("=> 0 bits: constant");
        }
        else
        {
            int h = MinEntropy.eighths(busy.conservativeMaxCount(), n);
            v.addElement("=> <= " + MinEntropy.bits(h) + " bits per read");
            v.addElement("(drift, not unpredictability)");
        }
        return toArray(v);
    }

    // ------------------------------------------------------------ collectors

    /**
     * Calibration pass: builds the distribution the measurement pass is scaled
     * against. Keeps counts, not samples, so nothing retains raw entropy.
     */
    private static final class CalibrationSink implements Entropy.JitterSink
    {
        private final IntHistogram values;
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        int count;

        CalibrationSink(IntHistogram values)
        {
            this.values = values;
        }

        public void sample(long spins, long nowMillis)
        {
            int s = clamp(spins);
            values.add(s);
            if (s < lo) { lo = s; }
            if (s > hi) { hi = s; }
            count++;
        }
    }

    /** Measurement pass. Allocates nothing; the tables are supplied. */
    private static final class Collector implements Entropy.JitterSink
    {
        private final IntHistogram raw;
        private final IntHistogram coarse;
        private final IntHistogram pairs;
        private final int bandLo;
        private final int bandWidth;
        private final int step;
        private final int[] cuts;
        private final int levels;
        private final int[] firstFew = new int[5];

        int count;
        int repeats;
        int clamped;
        private int previousLevel = -1;
        private int previousRaw;
        private boolean hasPrevious;

        Collector(IntHistogram raw, IntHistogram coarse, IntHistogram pairs,
                  int bandLo, int bandWidth, int step, int[] cuts)
        {
            this.raw = raw;
            this.coarse = coarse;
            this.pairs = pairs;
            this.bandLo = bandLo;
            this.bandWidth = bandWidth < 1 ? 1 : bandWidth;
            this.step = step < 1 ? 1 : step;
            this.cuts = cuts == null ? new int[0] : cuts;
            this.levels = this.cuts.length + 1;
        }

        public void sample(long spins, long nowMillis)
        {
            int s = clamp(spins);
            if (count < firstFew.length) { firstFew[count] = s; }

            // Outside the calibrated range, fold into the nearest end bucket.
            // That inflates that bucket's count and so lowers the reported
            // entropy, which is the safe direction; the count of how often it
            // happened is reported so the reader can judge.
            int offset = s - bandLo;
            if (offset < 0) { offset = 0; clamped++; }
            else if (offset >= bandWidth) { offset = bandWidth - 1; clamped++; }

            raw.add(offset / step);

            // Equal-population levels: how many cut points this sample exceeds.
            // At most seven comparisons, so no search structure is warranted.
            int level = 0;
            while (level < cuts.length && s > cuts[level]) { level++; }
            coarse.add(level);

            if (previousLevel >= 0) { pairs.add(previousLevel * levels + level); }
            previousLevel = level;

            if (hasPrevious && s == previousRaw) { repeats++; }
            previousRaw = s;
            hasPrevious = true;

            count++;
        }

        /**
         * As many of the first samples as fit in {@code budget} characters.
         *
         * Bounded by width rather than by count because spin counts are six
         * digits on a fast desktop and three or four on the handset this
         * targets. Dropping a value is fine; a line that wraps, or a number
         * truncated mid-digit into a different number, is not.
         */
        String firstValues(int budget)
        {
            StringBuffer sb = new StringBuffer(budget);
            int shown = count < firstFew.length ? count : firstFew.length;
            for (int i = 0; i < shown; i++)
            {
                String s = String.valueOf(firstFew[i]);
                int extra = s.length() + (sb.length() == 0 ? 0 : 1);
                if (sb.length() + extra > budget) { break; }
                if (sb.length() > 0) { sb.append(' '); }
                sb.append(s);
            }
            return sb.toString();
        }
    }

    /** Counts samples and discards them - used to size one gather(). */
    private static final class CountingSink implements Entropy.JitterSink
    {
        int count;

        public void sample(long spins, long nowMillis)
        {
            count++;
        }
    }

    // --------------------------------------------------------------- helpers

    private static int clamp(long v)
    {
        if (v > Integer.MAX_VALUE) { return Integer.MAX_VALUE; }
        if (v < Integer.MIN_VALUE) { return Integer.MIN_VALUE; }
        return (int) v;
    }

    private static void step(Progress p, String what, int done)
    {
        if (p != null)
        {
            try { p.step(what, done, TOTAL_STEPS); }
            catch (Throwable ignored) { /* a broken UI must not stop the probe */ }
        }
    }

    private static void appendAll(Vector v, String[] lines)
    {
        if (lines == null) { return; }
        for (int i = 0; i < lines.length; i++)
        {
            v.addElement(lines[i] == null ? "" : lines[i]);
        }
    }

    private static String[] toArray(Vector v)
    {
        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }
}
