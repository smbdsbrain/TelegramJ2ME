package tg.mem;

import tg.diag.Diag;

/**
 * Acting on the measured heap before the allocation, instead of catching the
 * OutOfMemoryError after it.
 *
 * <h3>Why freeMemory() alone is not the answer</h3>
 * On CLDC {@code totalMemory()} is usually the heap as it stands, not the
 * maximum - it grows on demand. So {@code freeMemory()} understates how much
 * room there is, sometimes by megabytes, and a client that budgeted from it
 * would refuse work it could easily have done. The measured ceiling is what
 * turns those two numbers into an answer:
 *
 * <pre>
 *   used     = totalMemory() - freeMemory()
 *   headroom = ceiling - used
 * </pre>
 *
 * That counts uncollected garbage as used, which is deliberate - see below.
 *
 * <h3>Why System.gc() appears here and nowhere else</h3>
 * This project avoids explicit collection: a stop-the-world pass on a 208 MHz
 * VM costs hundreds of milliseconds and sprinkling it makes the UI stutter.
 * The exception is here, and only after {@link #headroom} has already said the
 * work will not fit. At that point the alternative is not a smooth client, it
 * is an OutOfMemoryError. The collect is also what makes the refusal honest:
 * without it, headroom is pessimistic by exactly the amount of garbage nobody
 * has swept yet.
 *
 * Never called from the MtClient reader thread. A stall there delays every
 * pending RPC and can trip a read timeout, which trades a clean exception for
 * a stall plus, probably, the same exception. The protection on those paths is
 * the smaller budget, not a shed.
 */
public final class MemoryPressure
{
    /**
     * Never plan to use the last of the heap. The client still has to build an
     * alert, a log line and a crash entry after any decision made here.
     */
    private static final int RESERVE_BYTES = 96 * 1024;

    private static MemoryRelief relief;
    private static int shedEvents;
    private static long shedBytes;

    private MemoryPressure() { }

    /**
     * Register the client's caches. Pass null on shutdown, or this static holds
     * the whole object graph alive past teardown.
     */
    public static synchronized void setRelief(MemoryRelief value)
    {
        relief = value;
    }

    /**
     * Bytes believed available, without side effects.
     *
     * Safe to call anywhere and cheap enough to put in a message. Callers that
     * intend to allocate should use {@link #reserve} instead, which may be able
     * to make the answer better.
     */
    public static long headroom()
    {
        Runtime rt = Runtime.getRuntime();
        long free = rt.freeMemory();
        long ceiling = MemoryBudget.ceiling();
        if (ceiling <= 0)
        {
            // Unmeasured. freeMemory is understated on a growing heap, but it
            // is the only number that is certainly true.
            return free;
        }
        long headroom = ceiling - (rt.totalMemory() - free);

        // A ceiling that under-reports - a stored measurement from a launch
        // where the VM was smaller - must never make us claim less room than
        // the VM is offering right now.
        return headroom < free ? free : headroom;
    }

    /** Whether {@code bytes} would fit right now. No side effects. */
    public static boolean fits(long bytes)
    {
        if (bytes <= 0) { return true; }
        return headroom() >= bytes + RESERVE_BYTES;
    }

    /**
     * Make room for {@code bytes}, shedding caches if that is what it takes.
     *
     * @return false when even an empty cache set leaves too little room, in
     *         which case the caller must refuse the work and say so
     */
    public static boolean reserve(long bytes)
    {
        if (fits(bytes)) { return true; }

        MemoryRelief target;
        synchronized (MemoryPressure.class) { target = relief; }

        // Cheapest first: a collect alone is often enough, and costs nothing
        // the caller was not already about to pay.
        System.gc();
        if (fits(bytes)) { return true; }
        if (target == null) { return false; }

        int levels = target.levels();
        for (int level = 1; level <= levels; level++)
        {
            long before = headroom();
            try { target.release(level); }
            catch (Throwable t) { Diag.warn("shed level " + level + " failed"); }
            System.gc();
            long after = headroom();

            record(after - before);
            Diag.info("shed level=" + level + " freed=" + ((after - before) / 1024)
                      + "k headroom=" + (after / 1024) + "k need="
                      + (bytes / 1024) + "k");

            if (fits(bytes)) { return true; }
        }
        return false;
    }

    private static synchronized void record(long freed)
    {
        shedEvents++;
        if (freed > 0) { shedBytes += freed; }
    }

    /**
     * How often the ladder has fired this session.
     *
     * Worth reporting. If a handset sheds constantly the budgets are wrong for
     * it, and that is a fact better learned from a diagnostic upload than
     * guessed at.
     */
    public static synchronized int shedEvents() { return shedEvents; }

    public static synchronized long shedBytes() { return shedBytes; }

    /** Diagnostic lines. Contains no user data. */
    public static String[] lines()
    {
        return new String[] {
            "headroom = " + (headroom() / 1024) + " KB",
            "sheds = " + shedEvents() + " freeing " + (shedBytes() / 1024) + " KB"
        };
    }
}
