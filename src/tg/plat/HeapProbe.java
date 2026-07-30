package tg.plat;

import java.util.Vector;

import tg.diag.Diag;

/**
 * Conservative heap measurement.
 *
 * Every memory budget in this project - receive buffers, TL vector caps, media
 * chunking - depends on a number nobody has measured yet. The phone's own
 * "Java settings -> Heap size" figure is a starting point, not the usable
 * ceiling, so we measure what a MIDlet can actually hold.
 *
 * Safety rules, because an OutOfMemoryError with no recovery path leaves the
 * user staring at a dead screen:
 *   - a reserve block is allocated first and released the moment we run out, so
 *     there is always room to build the report;
 *   - allocations are small and incremental, never one huge speculative block;
 *   - every reference is dropped and the collector nudged before returning;
 *   - a hard chunk ceiling stops the loop even on a device with a large heap.
 */
public final class HeapProbe
{
    /** Released as soon as an OutOfMemoryError arrives, to guarantee headroom. */
    private static final int RESERVE_BYTES = 16 * 1024;

    /** Stop after this much even if the device would happily continue. */
    private static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;

    public static final class Result
    {
        public long startTotal;
        public long startFree;
        public long lowestFree;
        public long totalAllocated;
        public int  chunkSize;
        public int  chunkCount;
        public int  largestSingle;
        public boolean hitOom;
        public String note = "";

        public String[] lines()
        {
            String[] out = new String[9];
            out[0] = "startTotal = " + startTotal;
            out[1] = "startFree = " + startFree;
            out[2] = "chunkSize = " + chunkSize;
            out[3] = "chunksHeld = " + chunkCount;
            out[4] = "totalAllocated = " + totalAllocated + " (" + (totalAllocated / 1024) + " KB)";
            out[5] = "lowestFree = " + lowestFree;
            out[6] = "largestSingleAlloc = " + largestSingle + " (" + (largestSingle / 1024) + " KB)";
            out[7] = "hitOutOfMemory = " + hitOom;
            out[8] = "note = " + note;
            return out;
        }
    }

    private HeapProbe() { }

    /**
     * Fill the heap with {@code chunkSize} blocks until it refuses, then let go
     * of everything.
     *
     * @param chunkSize bytes per allocation; 8-32 KB keeps the granularity
     *                  useful without a huge Vector
     */
    public static Result run(int chunkSize)
    {
        if (chunkSize < 1024) { chunkSize = 1024; }

        Result r = new Result();
        r.chunkSize = chunkSize;

        Runtime rt = Runtime.getRuntime();
        System.gc();
        r.startTotal = rt.totalMemory();
        r.startFree = rt.freeMemory();
        r.lowestFree = r.startFree;

        byte[] reserve = new byte[RESERVE_BYTES];
        Vector held = new Vector(64);
        int maxChunks = MAX_TOTAL_BYTES / chunkSize;

        try
        {
            while (held.size() < maxChunks)
            {
                byte[] block = new byte[chunkSize];
                // Touch both ends: some VMs only commit pages on first write,
                // which would make a lazily-allocated block look free.
                block[0] = 1;
                block[chunkSize - 1] = 1;
                held.addElement(block);

                long free = rt.freeMemory();
                if (free < r.lowestFree) { r.lowestFree = free; }
            }
            r.note = "stopped at the " + (MAX_TOTAL_BYTES / 1024) + " KB self-imposed ceiling, heap not exhausted";
        }
        catch (OutOfMemoryError oom)
        {
            r.hitOom = true;
            reserve = null;                 // give the report room to exist
            r.note = "heap exhausted";
        }
        catch (Throwable t)
        {
            r.note = "aborted: " + Diag.className(t);
        }

        r.chunkCount = held.size();
        r.totalAllocated = (long) r.chunkCount * chunkSize;

        held.removeAllElements();
        held = null;
        reserve = null;
        System.gc();

        r.largestSingle = largestSingleAllocation();
        System.gc();

        Diag.info("heap probe: allocated " + (r.totalAllocated / 1024)
                  + "k in " + r.chunkCount + " chunks, oom=" + r.hitOom);
        return r;
    }

    /**
     * Biggest contiguous byte[] the VM will hand out right now, by binary
     * search. Fragmentation makes this smaller than the total free heap, and it
     * is the number that actually constrains a single network receive buffer.
     */
    private static int largestSingleAllocation()
    {
        int lo = 0;
        int hi = MAX_TOTAL_BYTES;

        while (lo < hi)
        {
            int mid = lo + (hi - lo + 1) / 2;
            byte[] probe = null;
            try
            {
                probe = new byte[mid];
                probe[mid - 1] = 1;
                lo = mid;
            }
            catch (OutOfMemoryError oom)
            {
                hi = mid - 1;
            }
            finally
            {
                probe = null;
            }
            // Reclaim between attempts, otherwise the previous success is still
            // occupying the space the next attempt needs.
            System.gc();
        }
        return lo;
    }
}
