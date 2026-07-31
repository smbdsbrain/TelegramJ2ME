package tg.plat;

/**
 * Fixed-capacity frequency table for int samples.
 *
 * Counting how often each value occurs is the whole of the most-common-value
 * estimator, and it is the reason no raw sample array is ever retained: a
 * 2000-sample jitter run costs O(distinct) here, not O(n). That matters less for
 * memory - the handset has 5 MB - than for the guarantee it provides. There is
 * no array of unhashed entropy samples anywhere in the process, so there is
 * nothing for a later careless caller to feed back into {@code Rng} as key
 * material.
 *
 * Open addressing with linear probing, two parallel int[]. CLDC has Hashtable,
 * but it would need an Integer per distinct value and CLDC has no autoboxing, so
 * this is both smaller and less garbage.
 *
 * <h3>Behaviour when full</h3>
 * The table never grows. Once every slot is occupied, further <em>new</em>
 * values are counted in {@link #overflow()} rather than stored, and
 * {@link #conservativeMaxCount()} then assumes every dropped sample belonged to
 * the mode. That assumption can only lower the entropy the caller reports, which
 * is the direction an estimator is allowed to be wrong in.
 */
public final class IntHistogram
{
    private final int mask;
    private final int[] keys;
    private final int[] counts;
    private final boolean[] used;

    private int distinct;
    private int overflow;
    private long total;
    private long sum;
    private int minValue = Integer.MAX_VALUE;
    private int maxValue = Integer.MIN_VALUE;

    /** @param slots capacity, rounded up to a power of two, minimum 8 */
    public IntHistogram(int slots)
    {
        int n = 8;
        while (n < slots && n < (1 << 20)) { n <<= 1; }
        this.mask = n - 1;
        this.keys = new int[n];
        this.counts = new int[n];
        this.used = new boolean[n];
    }

    public void add(int value)
    {
        total++;
        sum += value;
        if (value < minValue) { minValue = value; }
        if (value > maxValue) { maxValue = value; }

        // Mask, never modulo: '%' on Integer.MIN_VALUE yields a negative index
        // and an AIOOBE halfway through a measurement run.
        int h = value ^ (value >>> 16);
        int idx = h & mask;

        for (int probe = 0; probe <= mask; probe++)
        {
            if (!used[idx])
            {
                used[idx] = true;
                keys[idx] = value;
                counts[idx] = 1;
                distinct++;
                return;
            }
            if (keys[idx] == value)
            {
                counts[idx]++;
                return;
            }
            idx = (idx + 1) & mask;
        }

        // Table full and this value is not in it.
        overflow++;
    }

    public int distinct()
    {
        return distinct;
    }

    public int maxCount()
    {
        int best = 0;
        for (int i = 0; i <= mask; i++)
        {
            if (used[i] && counts[i] > best) { best = counts[i]; }
        }
        return best;
    }

    public int modeValue()
    {
        int best = 0;
        int at = 0;
        for (int i = 0; i <= mask; i++)
        {
            if (used[i] && counts[i] > best) { best = counts[i]; at = keys[i]; }
        }
        return at;
    }

    /** Samples dropped because the table was full when a new value arrived. */
    public int overflow()
    {
        return overflow;
    }

    /**
     * The count to feed {@link MinEntropy#eighths}: the observed mode plus every
     * dropped sample, as though all of them had been the mode too.
     */
    public int conservativeMaxCount()
    {
        return maxCount() + overflow;
    }

    public long total()
    {
        return total;
    }

    public long sum()
    {
        return sum;
    }

    public int minValue()
    {
        return total == 0 ? 0 : minValue;
    }

    public int maxValue()
    {
        return total == 0 ? 0 : maxValue;
    }

    public long mean()
    {
        return total == 0 ? 0 : sum / total;
    }

    /**
     * Cut points splitting the observed distribution into {@code levels} groups
     * of roughly equal population.
     *
     * <h3>Why equal-frequency and not equal-width</h3>
     * The coarse levels exist so a pair estimator can look for serial
     * dependence. Equal-width bins cannot do that job on the distribution a
     * handset actually produces: jitter spin counts are bimodal - a full tick
     * gives around 1200, a loop that entered near a tick boundary gives almost
     * nothing - so any linear scale, whether taken from min..max or from a
     * deviation band, drops 95% of samples into one bin. The measured Alcatel
     * OT-810D did exactly that under both, leaving the check with nothing to
     * compare and the headline figure shipped marked UNCHECKED.
     *
     * Splitting by population instead makes the marginal distribution of levels
     * uniform <em>by construction</em>. Whatever deficit the pair estimator then
     * reports is serial dependence and nothing else, which is precisely the
     * question being asked.
     *
     * Ties are not split: if one value holds more than a whole share, several
     * cuts land on it and the levels between them come out empty. That lowers
     * the reported figure rather than raising it, which is the safe direction.
     *
     * @return {@code levels - 1} ascending cut points, or an empty array if
     *         there is nothing to split
     */
    public int[] quantileCuts(int levels)
    {
        if (levels < 2 || distinct == 0) { return new int[0]; }

        int[] k = new int[distinct];
        int[] c = new int[distinct];
        int w = 0;
        for (int i = 0; i <= mask && w < distinct; i++)
        {
            if (used[i]) { k[w] = keys[i]; c[w] = counts[i]; w++; }
        }

        // Shell sort: CLDC 1.1 has no java.util.Arrays, and plain insertion
        // sort over up to 1024 distinct values is slow enough to notice on a
        // 208 MHz CPU.
        for (int gap = w / 2; gap > 0; gap /= 2)
        {
            for (int i = gap; i < w; i++)
            {
                int kv = k[i];
                int cv = c[i];
                int j = i;
                while (j >= gap && k[j - gap] > kv)
                {
                    k[j] = k[j - gap];
                    c[j] = c[j - gap];
                    j -= gap;
                }
                k[j] = kv;
                c[j] = cv;
            }
        }

        int[] cuts = new int[levels - 1];
        long seen = 0;
        int next = 1;
        for (int i = 0; i < w && next < levels; i++)
        {
            seen += c[i];
            while (next < levels && seen * levels >= total * next)
            {
                cuts[next - 1] = k[i];
                next++;
            }
        }
        while (next < levels) { cuts[next - 1] = k[w - 1]; next++; }
        return cuts;
    }

    /**
     * Mean absolute deviation about {@code centre}, rounded down.
     *
     * Exists to scale a bucketing to where the samples actually are. The
     * obvious alternative - spread the buckets linearly across min..max - is
     * destroyed by a single outlier, and jitter sampling produces them: one
     * spin count of 1, where the clock happened to tick on the first read,
     * stretches a 1100..1281 distribution across a 1..1281 scale and collapses
     * every real sample into the top bucket or two. A deviation-based band
     * ignores the outlier instead of being defined by it.
     */
    public long meanAbsoluteDeviation(long centre)
    {
        if (total == 0) { return 0; }
        long acc = 0;
        for (int i = 0; i <= mask; i++)
        {
            if (!used[i]) { continue; }
            long d = keys[i] - centre;
            if (d < 0) { d = -d; }
            acc += d * counts[i];
        }
        return acc / total;
    }

    /** Count for one value, 0 if unseen. Used by the "top three deltas" line. */
    public int countOf(int value)
    {
        int h = value ^ (value >>> 16);
        int idx = h & mask;
        for (int probe = 0; probe <= mask; probe++)
        {
            if (!used[idx]) { return 0; }
            if (keys[idx] == value) { return counts[idx]; }
            idx = (idx + 1) & mask;
        }
        return 0;
    }

    /** The {@code want} most frequent values, unbounded in width. */
    public String topValues(int want)
    {
        return topValues(want, Integer.MAX_VALUE);
    }

    /**
     * The {@code want} most frequent values, most frequent first, as
     * {@code "value(count)"} pairs joined by spaces, stopping before
     * {@code budget} characters would be exceeded. Selection sort over the
     * table - {@code want} is 3, so an O(want * slots) scan is cheaper than
     * building and sorting a copy.
     *
     * Width-bounded for the same reason the caller's other lines are: on a
     * 34-column screen a long line wraps and the report stops lining up. Showing
     * two entries instead of three loses nothing that matters; splitting a
     * number across a wrap does.
     */
    public String topValues(int want, int budget)
    {
        StringBuffer sb = new StringBuffer(32);
        int taken = 0;
        int ceiling = Integer.MAX_VALUE;
        int ceilingValue = Integer.MAX_VALUE;

        while (taken < want)
        {
            int bestCount = 0;
            int bestValue = 0;
            boolean found = false;
            for (int i = 0; i <= mask; i++)
            {
                if (!used[i]) { continue; }
                int c = counts[i];
                // Strictly below the previous pick, with the value as a
                // tie-break so equal counts do not repeat forever.
                if (c > ceiling || (c == ceiling && keys[i] >= ceilingValue)) { continue; }
                if (!found || c > bestCount || (c == bestCount && keys[i] > bestValue))
                {
                    bestCount = c;
                    bestValue = keys[i];
                    found = true;
                }
            }
            if (!found) { break; }

            String entry = bestValue + "(" + bestCount + ")";
            int extra = entry.length() + (taken > 0 ? 1 : 0);
            if (sb.length() + extra > budget) { break; }

            if (taken > 0) { sb.append(' '); }
            sb.append(entry);
            ceiling = bestCount;
            ceilingValue = bestValue;
            taken++;
        }
        return sb.toString();
    }
}
