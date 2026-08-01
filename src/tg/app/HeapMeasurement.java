package tg.app;

import tg.diag.Diag;
import tg.mem.MemoryBudget;
import tg.mt.AuthKeyStore;
import tg.plat.HeapProbe;

/**
 * Measures the heap once, remembers the answer, and hands it to
 * {@link MemoryBudget}.
 *
 * <h3>Why this is not in tg.mem</h3>
 * {@code AuthKeyStore} lives in {@code tg.mt}, and {@code tg.mem} has to stay
 * importable from every package including that one. Keeping the persistence
 * here leaves the budget holder with no dependencies at all.
 *
 * <h3>Why not AppSettings</h3>
 * {@code AppSettings} is saved wholesale from the settings screen. A probe
 * thread writing through it would race with a preference the user just changed
 * and could put the old value back. These four keys are written individually
 * through {@link AuthKeyStore#saveString}, which {@code RmsAuthKeyStore}
 * synchronizes per key.
 *
 * <h3>Surviving a measurement that kills the VM</h3>
 * The probe deliberately allocates until the VM refuses. That is safe on both
 * handsets measured so far, and there is no promise it is safe on the next one.
 * So an attempt counter is written <i>before</i> the probe runs and cleared
 * after it returns. A client that starts and finds attempts already at the
 * limit knows the last two tries did not come back, stops trying, and keeps the
 * reference profile - the configuration that was validated on hardware.
 */
public final class HeapMeasurement
{
    /** Bump when the probe's meaning changes, to discard stored values. */
    private static final int PROBE_VERSION = 1;

    /**
     * Two, not one. A probe that does not return is at least as likely to have
     * been interrupted by an incoming call or a user-initiated exit as to have
     * killed the VM, and permanently refusing to measure on that evidence would
     * leave a genuinely small handset on the reference profile forever.
     */
    private static final int MAX_ATTEMPTS = 2;

    private static final String KEY_CEILING = "heap.ceiling";
    private static final String KEY_BLOCK = "heap.block";
    private static final String KEY_VERSION = "heap.probe.version";
    private static final String KEY_ATTEMPTS = "heap.probe.attempts";

    /**
     * Chunky on purpose: 64 KB blocks measure a five megabyte heap in about
     * eighty allocations instead of six hundred, and nothing derived from the
     * result needs finer resolution than that.
     */
    private static final int CHUNK_BYTES = 64 * 1024;
    private static final int BLOCK_GRANULARITY = 64 * 1024;

    private HeapMeasurement() { }

    /**
     * Install a stored measurement, if there is one.
     *
     * @return true when a stored value was applied, so no probe is needed
     */
    public static boolean applyStored(AuthKeyStore store)
    {
        if (store == null) { return false; }
        try
        {
            if (readInt(store, KEY_VERSION, 0) != PROBE_VERSION) { return false; }
            int ceiling = readInt(store, KEY_CEILING, 0);
            if (ceiling <= 0) { return false; }
            int block = readInt(store, KEY_BLOCK, 0);
            MemoryBudget.init(ceiling, block, MemoryBudget.SOURCE_STORED);
            Diag.info("heap: stored ceiling " + (ceiling / 1024) + "k, block "
                      + (block / 1024) + "k");
            return true;
        }
        catch (Throwable t)
        {
            Diag.warn("heap: stored measurement unreadable, using defaults");
            return false;
        }
    }

    /** True when {@link #measure} would refuse because earlier probes died. */
    public static boolean exhausted(AuthKeyStore store)
    {
        return store != null && readInt(store, KEY_ATTEMPTS, 0) >= MAX_ATTEMPTS;
    }

    /**
     * Run the probe and persist the result.
     *
     * Caller's responsibility: run this off the UI thread and with nothing else
     * allocating. It fills the heap, and whichever thread asks for memory at
     * the wrong moment is the one that receives the OutOfMemoryError.
     *
     * @return true when a new measurement was installed
     */
    public static boolean measure(AuthKeyStore store)
    {
        if (store == null) { return false; }

        int attempts = readInt(store, KEY_ATTEMPTS, 0);
        if (attempts >= MAX_ATTEMPTS)
        {
            Diag.warn("heap: " + attempts + " probes did not return; keeping"
                      + " the default budget profile");
            return false;
        }

        // Written first. If the probe takes the VM down, the next launch sees
        // this and knows not to try the same thing again. A store that cannot
        // hold it is not a reason to skip the measurement - it only means this
        // handset re-measures every launch, which it would anyway, because the
        // result has nowhere to be kept either.
        try { store.saveString(KEY_ATTEMPTS, String.valueOf(attempts + 1)); }
        catch (Throwable t) { Diag.warn("heap: cannot record probe attempts"); }

        try
        {
            HeapProbe.Result r = HeapProbe.run(CHUNK_BYTES, BLOCK_GRANULARITY);

            // peakTotal is the capacity the VM grew to while the probe pushed
            // it, so it is right whether the heap grows on demand or not, and
            // it does not shrink just because something else was already
            // holding memory when the probe started.
            //
            // This used to be max(totalAllocated, startTotal), and both terms
            // are wrong in a way that matters. startTotal is only what was
            // committed beforehand - on a lazily growing VM that understates
            // the heap badly. totalAllocated is what the probe could hold on
            // top of whatever was already resident, so on a phone whose AMS is
            // sitting on a megabyte it reports a ceiling a megabyte too small,
            // and every budget derived from it comes out short.
            long ceiling = r.peakTotal;
            if (r.totalAllocated > ceiling) { ceiling = r.totalAllocated; }
            if (ceiling <= 0)
            {
                Diag.warn("heap: probe measured nothing; keeping defaults");
                try { store.saveString(KEY_ATTEMPTS, "0"); }
                catch (Throwable ignored) { }
                return false;
            }

            MemoryBudget.init(ceiling, r.largestSingle, MemoryBudget.SOURCE_MEASURED);
            Diag.info("heap: measured ceiling " + (MemoryBudget.ceiling() / 1024)
                      + "k, block " + (MemoryBudget.largestBlock() / 1024)
                      + "k, oom=" + r.hitOom);

            // The measurement is already installed. Failing to persist it costs
            // a repeat probe next launch, not this session.
            try
            {
                store.saveString(KEY_CEILING, String.valueOf(MemoryBudget.ceiling()));
                store.saveString(KEY_BLOCK, String.valueOf(MemoryBudget.largestBlock()));
                store.saveString(KEY_VERSION, String.valueOf(PROBE_VERSION));
                store.saveString(KEY_ATTEMPTS, "0");
            }
            catch (Throwable t)
            {
                Diag.warn("heap: measurement could not be stored; it will be"
                          + " taken again on the next launch");
            }
            return true;
        }
        catch (Throwable t)
        {
            // Including OutOfMemoryError. The budget keeps whatever it had,
            // which is the profile that shipped, and the attempt counter stays
            // incremented so a probe that fails repeatedly stops being tried.
            Diag.error("heap: probe failed", t);
            return false;
        }
    }

    /** Diagnostic lines for the report header. No user data. */
    public static String[] lines()
    {
        return MemoryBudget.lines();
    }

    private static int readInt(AuthKeyStore store, String key, int fallback)
    {
        try
        {
            String value = store.loadString(key);
            if (value == null) { return fallback; }
            return Integer.parseInt(value.trim());
        }
        catch (Throwable ignored) { return fallback; }
    }
}
