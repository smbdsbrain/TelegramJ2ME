package tgtest;

import tg.plat.HeapProbe;

/**
 * The heap probe is now on the client's startup path, not just in the probe
 * MIDlet, so two properties that used to be merely nice are load-bearing.
 *
 * It has to release everything. The probe deliberately allocates until the VM
 * refuses; if a single reference survives, the client it just measured has no
 * heap left to run in. Running it twice and getting a comparable answer is the
 * only way to observe that from outside.
 *
 * And the coarse mode has to agree with the exact one. The client trades
 * precision for time - seven collects instead of twenty-three - and a coarse
 * result that came out larger than the truth would size a buffer the VM cannot
 * hand over.
 *
 * Desktop heaps are large, but the probe stops at its own 8 MB ceiling, so this
 * suite is bounded and fast wherever it runs. What it cannot tell you is how
 * either number behaves on a handset - see docs/emulator-notes.md.
 */
public final class HeapProbeTest implements Test
{
    public String name() { return "mem/heap-probe"; }

    public void run()
    {
        releasesWhatItAllocated();
        coarseNeverOverstatesTheBlock();
        capacitySurvivesAnOccupiedHeap();
        argumentsAreDefensive();
    }

    private static void releasesWhatItAllocated()
    {
        HeapProbe.Result first = HeapProbe.run(64 * 1024, 64 * 1024);
        Assert.isTrue("the probe allocated something", first.totalAllocated > 0);
        Assert.isTrue("a largest block was found", first.largestSingle > 0);
        Assert.equal("the chunk size is reported", 64 * 1024, first.chunkSize);
        Assert.isTrue("a note is always set",
                first.note != null && first.note.length() > 0);
        Assert.equal("the report is nine lines", 9, first.lines().length);

        HeapProbe.Result second = HeapProbe.run(64 * 1024, 64 * 1024);

        // If the first run leaked, the second gets a visibly smaller heap. Half
        // is a generous margin: a leak of even one chunk vector would be total.
        Assert.isTrue("a second probe still finds most of the heap"
                        + " (" + second.totalAllocated + " after "
                        + first.totalAllocated + ")",
                second.totalAllocated * 2 >= first.totalAllocated);
        Assert.isTrue("a second probe still finds a large single block",
                (long) second.largestSingle * 2L >= (long) first.largestSingle);
    }

    private static void coarseNeverOverstatesTheBlock()
    {
        int exact = HeapProbe.run(64 * 1024).largestSingle;
        int coarse = HeapProbe.run(64 * 1024, 64 * 1024).largestSingle;

        Assert.isTrue("the exact search found a block", exact > 0);
        Assert.isTrue("the coarse search found a block", coarse > 0);

        // The coarse search returns a size it actually allocated, so it may be
        // below the exact answer but must never claim more. Allow a generous
        // upward margin only for genuine heap growth between the two runs.
        Assert.isTrue("coarse does not overstate the block by more than a"
                        + " granule (" + coarse + " vs " + exact + ")",
                (long) coarse <= (long) exact + 64L * 1024L);
    }

    /**
     * The ceiling has to mean capacity, not free space.
     *
     * This is what {@code peakTotal} exists for. {@code totalAllocated} is only
     * what the probe could hold on top of whatever was already resident, so on
     * a handset whose AMS is sitting on a megabyte it reports a ceiling a
     * megabyte short and every budget derived from it comes out small for no
     * reason.
     *
     * The interesting half of this can only be shown on a heap the probe can
     * actually exhaust. A desktop JVM usually stops it at its own 8 MB
     * self-imposed ceiling instead, and then holding a block changes nothing
     * because nothing was ever scarce - so that assertion is made only when
     * {@code hitOom} says the heap really ran out. The invariants below hold
     * either way, and it is those that would break if peakTotal were dropped.
     */
    private static void capacitySurvivesAnOccupiedHeap()
    {
        HeapProbe.Result empty = HeapProbe.run(64 * 1024, 64 * 1024);
        Assert.isTrue("a peak capacity was recorded", empty.peakTotal > 0);
        Assert.isTrue("capacity is never below what the probe held",
                empty.peakTotal >= empty.totalAllocated);

        int occupy = (int) Math.min(empty.totalAllocated / 4, 2L * 1024 * 1024);
        Assert.isTrue("the first probe left something to occupy", occupy > 0);

        byte[] resident = new byte[occupy];
        for (int i = 0; i < occupy; i += 4096) { resident[i] = 1; }
        try
        {
            HeapProbe.Result busy = HeapProbe.run(64 * 1024, 64 * 1024);

            Assert.isTrue("capacity is never below what the probe held,"
                            + " with memory in use",
                    busy.peakTotal >= busy.totalAllocated);

            // Capacity must not collapse to free space. Half is a wide margin:
            // the claim is "does not track what is resident", not an exact
            // figure, and the desktop VM keeps growing between runs.
            Assert.isTrue("capacity does not shrink because memory is in use"
                            + " (" + busy.peakTotal + " vs " + empty.peakTotal + ")",
                    busy.peakTotal * 2 >= empty.peakTotal);

            if (empty.hitOom && busy.hitOom)
            {
                // Only meaningful on a heap small enough to exhaust: there, the
                // ballast really does take room away from the probe.
                Assert.isTrue("an exhausted heap holds less when occupied"
                                + " (" + busy.totalAllocated + " vs "
                                + empty.totalAllocated + ")",
                        busy.totalAllocated < empty.totalAllocated);
            }
        }
        finally { resident = null; }
    }

    private static void argumentsAreDefensive()
    {
        // A caller passing nonsense must get a measurement, not an exception or
        // a hang: this runs on a background thread at first launch, where the
        // failure mode is a client that never finishes starting.
        HeapProbe.Result r = HeapProbe.run(0, 0);
        Assert.isTrue("a zero chunk size is floored", r.chunkSize >= 1024);
        Assert.isTrue("a zero granularity still terminates", r.largestSingle > 0);
    }
}
