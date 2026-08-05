package tg.plat;

/**
 * What the wall clock and the scheduler on this handset are actually worth.
 *
 * Two subsystems depend on answers nobody has measured:
 *
 * <ul>
 *   <li><b>Every network timeout.</b> {@code tg.mt.MtClient} computes how long
 *       to wait for a reply as {@code sentAt + REPLY_TIMEOUT_MS -
 *       System.currentTimeMillis()}. That arithmetic assumes the clock only
 *       moves forwards and at roughly one millisecond per millisecond. A
 *       handset that resynchronises from the network mid-session - or whose
 *       user sets the time - can make that difference arbitrarily negative and
 *       time out a request that had barely been sent.</li>
 *   <li><b>RNG seeding.</b> {@code tg.crypto.AuthKeySeeding} harvests jitter,
 *       and its yield is set by the clock tick, not by the noise: 4 ms on an
 *       Alcatel OT-810D gave 58 bits per gather, 12 ms on a Samsung GT-C3592
 *       gave 21. Sizing the barrier for the slowest supported clock needs more
 *       than two points.</li>
 * </ul>
 *
 * Deliberately cheap and bounded - about four seconds, no allocation to speak
 * of - so it can run in the automatic sweep next to the heap probe.
 */
public final class ClockProbe
{
    /** Spins looking for a tick. Bounded so a stopped clock cannot hang us. */
    private static final int TICK_SAMPLES = 24;
    private static final long TICK_SPIN_LIMIT = 2000;

    /** Sleep requests to time, and what each one asks for. */
    private static final int SLEEP_SAMPLES = 8;
    private static final long SLEEP_MS = 250;

    private ClockProbe() { }

    public static String[] run()
    {
        String[] out = new String[16];
        int w = 0;

        out[w++] = "clock and scheduler";
        out[w++] = "";

        long tick = measureTick();
        out[w++] = "currentTimeMillis tick = "
                + (tick < 0 ? "not observed in " + TICK_SPIN_LIMIT + " ms"
                            : tick + " ms");
        out[w++] = "  (4 ms -> 58 bits/gather, 12 ms -> 21)";

        Sleep sleep = measureSleep();
        out[w++] = "";
        out[w++] = "Thread.sleep(" + SLEEP_MS + ") x" + SLEEP_SAMPLES + ":";
        out[w++] = "  min/avg/max = " + sleep.min() + "/" + sleep.avg()
                + "/" + sleep.max + " ms";
        out[w++] = "  worst overshoot = " + (sleep.max - SLEEP_MS) + " ms";
        out[w++] = "  early returns = " + sleep.early
                + (sleep.early > 0 ? "  <- sleep is NOT a lower bound" : "");

        // The measurement that matters most for timeouts: over the whole run
        // above, did the clock ever fail to move forwards?
        out[w++] = "";
        out[w++] = "monotonicity over " + sleep.readings + " readings:";
        out[w++] = "  backwards steps = " + sleep.backwards;
        out[w++] = "  largest jump = " + sleep.largestJump + " ms";
        out[w++] = sleep.backwards > 0
                ? "  VERDICT: wall clock is NOT usable for timeouts"
                : "  VERDICT: no regression seen in this window";
        out[w++] = "";
        out[w++] = "a jump here also invalidates msg_id time sync.";

        // Trimmed rather than sized by hand, so a line added later cannot leave
        // a null that reaches the collector as the word "null".
        String[] trimmed = new String[w];
        System.arraycopy(out, 0, trimmed, 0, w);
        return trimmed;
    }

    /**
     * The granularity of the clock: how far it moves when it moves at all.
     *
     * Sampling the smallest non-zero delta rather than an average, because a
     * coarse clock reads as a run of identical values followed by one step, and
     * that step is the real resolution.
     */
    private static long measureTick()
    {
        long smallest = Long.MAX_VALUE;
        long deadline = System.currentTimeMillis() + TICK_SPIN_LIMIT;
        int seen = 0;

        while (seen < TICK_SAMPLES && System.currentTimeMillis() < deadline)
        {
            long from = System.currentTimeMillis();
            long to = from;
            // Spin until it moves. The deadline check keeps a frozen clock from
            // turning this into an infinite loop.
            while (to == from && System.currentTimeMillis() < deadline)
            {
                to = System.currentTimeMillis();
            }
            long delta = to - from;
            if (delta > 0)
            {
                seen++;
                if (delta < smallest) { smallest = delta; }
            }
        }
        return seen == 0 ? -1 : smallest;
    }

    private static Sleep measureSleep()
    {
        Sleep s = new Sleep();
        long previous = System.currentTimeMillis();
        s.readings = 1;

        for (int i = 0; i < SLEEP_SAMPLES; i++)
        {
            long t0 = System.currentTimeMillis();
            try
            {
                Thread.sleep(SLEEP_MS);
            }
            catch (InterruptedException ignored)
            {
                // Recorded as whatever elapsed; an interrupted probe is still
                // a measurement, just a short one.
            }
            long t1 = System.currentTimeMillis();
            s.readings++;

            long step = t1 - previous;
            if (step < 0) { s.backwards++; }
            else if (step > s.largestJump) { s.largestJump = step; }
            previous = t1;

            long elapsed = t1 - t0;
            if (elapsed < 0) { continue; } // covered by the backwards count
            if (elapsed < SLEEP_MS) { s.early++; }
            if (elapsed < s.smallest) { s.smallest = elapsed; }
            if (elapsed > s.max) { s.max = elapsed; }
            s.total += elapsed;
            s.counted++;
        }
        return s;
    }

    private static final class Sleep
    {
        long smallest = Long.MAX_VALUE;
        long max;
        long total;
        int counted;
        int early;
        int backwards;
        long largestJump;
        int readings;

        long min() { return counted == 0 ? 0 : smallest; }
        long avg() { return counted == 0 ? 0 : total / counted; }
    }
}
