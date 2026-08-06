package tg.plat;

import java.util.Vector;

import tg.crypto.IntHistogram;
import tg.crypto.MinEntropy;

/**
 * Min-entropy of key-press timing.
 *
 * <h3>Why this measurement matters more than the others</h3>
 * {@code tg.crypto.Entropy}'s class notes say plainly that the best source
 * available on a handset is the user: "key-press timing on a slow handset
 * carries real, physically unpredictable entropy in its low bits", and describe
 * an intended flow where a few seconds of keyboard interaction is folded into
 * the pool before an auth_key is generated. That flow is not implemented, and
 * {@code Entropy.fromUserInput} has no callers anywhere in the source tree. The
 * number this class produces is what decides whether implementing it is worth
 * anything on a given handset.
 *
 * <h3>The claim it can falsify</h3>
 * An inter-key interval cannot resolve anything finer than the clock tick. If
 * the runtime reports times in 15 ms steps then every delta is a multiple of 15,
 * the low bits are identically zero, and "the low bits carry entropy" is simply
 * false there. {@link #analyse} computes the gcd of the observed deltas for
 * exactly this reason: it measures the quantisation of the event timestamps,
 * which can be coarser still than the busy-loop tick.
 *
 * <h3>What the gcd test cannot see</h3>
 * It finds a <em>uniform</em> quantum and nothing else. On a runtime whose tick
 * alternates - the measured OT-810D advances by 4 ms and 5 ms in turn - sums of
 * those steps reach essentially every integer, so the gcd collapses to 1 while
 * the real resolution stays around 4 ms. A gcd of 1 therefore means "no single
 * quantum found", not "resolution is one millisecond"; the clock granularity
 * reported by {@link EntropyProbe#clockReport()} is the figure that bounds it.
 * The entropy estimate itself is unaffected, being taken over the intervals as
 * observed.
 *
 * The analysis is separated from the {@code Canvas} that collects the presses so
 * it can be exercised by the desktop test suite with synthetic timings.
 */
public final class KeyTimingProbe
{
    /** Presses below which the estimate is not worth quoting. */
    public static final int RELIABLE_MIN = 30;

    private KeyTimingProbe() { }

    /**
     * @param timestamps  press times in ms, in order
     * @param keyCodes    the key for each press, same order; may be null
     * @param count       how many entries of the arrays are populated
     * @param tickMillis  measured clock tick from {@link EntropyProbe}, or -1
     */
    public static String[] analyse(long[] timestamps, int[] keyCodes,
                                   int count, int tickMillis)
    {
        Vector v = new Vector(20);

        if (timestamps == null || count < 2)
        {
            v.addElement("n = " + (count < 0 ? 0 : count) + " presses");
            v.addElement("need at least 2 to measure an");
            v.addElement("interval. press some keys.");
            return toArray(v);
        }

        int intervals = count - 1;
        IntHistogram deltas = new IntHistogram(128);
        IntHistogram low4 = new IntHistogram(32);
        IntHistogram low5 = new IntHistogram(64);
        IntHistogram keys = new IntHistogram(64);

        long total = 0;
        long gcd = 0;
        int min = Integer.MAX_VALUE;
        int max = 0;

        for (int i = 1; i < count; i++)
        {
            long raw = timestamps[i] - timestamps[i - 1];
            if (raw < 0) { raw = 0; }
            if (raw > Integer.MAX_VALUE) { raw = Integer.MAX_VALUE; }
            int d = (int) raw;

            deltas.add(d);
            low4.add(d & 15);
            low5.add(d & 31);
            total += d;
            if (d < min) { min = d; }
            if (d > max) { max = d; }
            gcd = gcd(gcd, d);
        }
        if (keyCodes != null)
        {
            for (int i = 0; i < count; i++) { keys.add(keyCodes[i]); }
        }

        v.addElement("n = " + count + " presses over "
                + (total / 1000) + " s");
        // Split across two lines: a long idle gap makes max six digits, and all
        // three figures on one line then overflows a 34-column screen.
        v.addElement("delta " + min + ".." + max + " ms");
        v.addElement("mean " + (total / intervals) + " ms");
        v.addElement("distinct deltas = " + deltas.distinct());
        v.addElement("gcd(deltas) = " + gcd + " ms");

        int cmax = deltas.conservativeMaxCount();
        int hRaw = MinEntropy.eighths(cmax, intervals);
        v.addElement("H_raw = " + MinEntropy.bits(hRaw));

        int usable = hRaw;
        if (intervals >= MinEntropy.MIN_SAMPLES_FOR_CONFIDENCE)
        {
            usable = MinEntropy.eighthsWithConfidence(cmax, intervals);
            v.addElement("H_99% = " + MinEntropy.bits(usable));
        }

        int h4 = MinEntropy.eighths(low4.conservativeMaxCount(), intervals);
        int h5 = MinEntropy.eighths(low5.conservativeMaxCount(), intervals);
        v.addElement("d & 15 -> " + MinEntropy.bits(h4));
        v.addElement("d & 31 -> " + MinEntropy.bits(h5));

        if (h4 == 0)
        {
            v.addElement("low 4 bits are CONSTANT:");
            v.addElement("0 bits there.");
        }

        if (gcd > 1)
        {
            // The low bits are worth stating carefully. They are zero only when
            // the quantum is a multiple of 16; at a 15 ms quantum they cycle and
            // look random. They are still not a second source - they are a
            // function of d/gcd, already counted in H_raw. Adding them again
            // would double-count the same randomness, which is how an honest
            // measurement turns into an inflated one.
            v.addElement("deltas quantised at " + gcd + " ms:");
            v.addElement("only d/" + gcd + " is free. the low");
            v.addElement("bits are a function of it, not");
            v.addElement("a separate source. do not add");
            v.addElement("them to H_raw.");
            if (tickMillis > 0 && gcd > tickMillis)
            {
                v.addElement("event timestamps are coarser");
                v.addElement("than the " + tickMillis + " ms clock tick.");
            }
        }

        if (keyCodes != null && keys.total() > 0)
        {
            v.addElement("distinct keys = " + keys.distinct());
            if (keys.distinct() <= 1)
            {
                v.addElement("one key only - the timing is");
                v.addElement("rhythmic. retype varied.");
            }
        }

        if (intervals < RELIABLE_MIN)
        {
            v.addElement("n < " + RELIABLE_MIN + ": estimate is not");
            v.addElement("reliable. keep pressing.");
        }

        v.addElement("usable = " + MinEntropy.bits(usable) + " bits/press");
        if (usable <= 0)
        {
            v.addElement("=> keyboard entropy is ZERO");
            v.addElement("here. the flow described in");
            v.addElement("Entropy's javadoc cannot work");
            v.addElement("on this handset.");
        }
        else
        {
            int needed = (256 * 8 + usable - 1) / usable;
            v.addElement("256 bits needs " + needed + " presses");
        }
        return toArray(v);
    }

    /** Euclid. The quantisation of the event clock falls straight out of it. */
    private static long gcd(long a, long b)
    {
        if (a < 0) { a = -a; }
        if (b < 0) { b = -b; }
        while (b != 0)
        {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    private static String[] toArray(Vector v)
    {
        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }
}
