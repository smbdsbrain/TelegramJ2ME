package tg.diag;

import javax.microedition.rms.RecordStore;

/**
 * Crash persistence.
 *
 * A MIDlet that dies on the handset takes its log with it, and there is no
 * console to read afterwards. Before rethrowing (or giving up) we drop the
 * exception, the subsystem that was running, the heap state and the tail of the
 * diagnostic ring into RMS, so the next launch can display what happened.
 *
 * Kept deliberately small: RMS record sizes on 2011 hardware are an open
 * question, so we store at most MAX_ENTRIES entries of at most MAX_BYTES each.
 */
public final class CrashLog
{
    private static final String STORE = "tgcrash";

    /** Keep a short history: the first failure is usually the informative one. */
    private static final int MAX_ENTRIES = 3;

    /** Hard cap per entry until RMS limits are measured on real hardware. */
    private static final int MAX_BYTES = 4096;

    /** How many tail lines of the diagnostic ring to attach. */
    private static final int TAIL_LINES = 25;

    private CrashLog() { }

    /**
     * Record a failure. Never throws: it is called from catch blocks that are
     * already handling something worse.
     */
    public static void save(String subsystem, Throwable t)
    {
        RecordStore rs = null;
        try
        {
            StringBuffer sb = new StringBuffer(512);
            sb.append("subsystem=").append(subsystem).append('\n');
            sb.append("class=").append(Diag.className(t)).append('\n');
            sb.append("message=").append(t == null ? "null" : String.valueOf(t.getMessage()));
            sb.append('\n');

            Runtime rt = Runtime.getRuntime();
            sb.append("heapTotal=").append(rt.totalMemory()).append('\n');
            sb.append("heapFree=").append(rt.freeMemory()).append('\n');
            sb.append("--- log tail ---\n");

            String[] lines = Diag.snapshot();
            int from = lines.length - TAIL_LINES;
            if (from < 0) { from = 0; }
            for (int i = from; i < lines.length; i++)
            {
                sb.append(lines[i]).append('\n');
                if (sb.length() > MAX_BYTES) { break; }
            }

            byte[] data = sb.toString().getBytes();
            if (data.length > MAX_BYTES)
            {
                byte[] cut = new byte[MAX_BYTES];
                System.arraycopy(data, 0, cut, 0, MAX_BYTES);
                data = cut;
            }

            rs = RecordStore.openRecordStore(STORE, true);
            trim(rs, MAX_ENTRIES - 1);
            rs.addRecord(data, 0, data.length);
        }
        catch (Throwable ignored)
        {
            // Persistence is best effort. Failing here must not mask the
            // original crash.
        }
        finally
        {
            closeQuietly(rs);
        }
    }

    /** All stored entries, oldest first. Empty array when nothing was recorded. */
    public static String[] load()
    {
        RecordStore rs = null;
        try
        {
            rs = RecordStore.openRecordStore(STORE, true);
            int n = rs.getNumRecords();
            if (n == 0) { return new String[0]; }

            String[] out = new String[n];
            int w = 0;
            int next = rs.getNextRecordID();
            for (int id = 1; id < next && w < n; id++)
            {
                try
                {
                    byte[] b = rs.getRecord(id);
                    if (b != null) { out[w++] = new String(b); }
                }
                catch (Throwable ignored) { /* deleted id */ }
            }
            if (w == out.length) { return out; }
            String[] trimmed = new String[w];
            System.arraycopy(out, 0, trimmed, 0, w);
            return trimmed;
        }
        catch (Throwable t)
        {
            return new String[] { "crash log unreadable: " + Diag.className(t) };
        }
        finally
        {
            closeQuietly(rs);
        }
    }

    public static void clear()
    {
        try { RecordStore.deleteRecordStore(STORE); }
        catch (Throwable ignored) { /* not present */ }
    }

    // ------------------------------------------------------------ internal

    /** Delete oldest records until at most {@code keep} remain. */
    private static void trim(RecordStore rs, int keep) throws Exception
    {
        int next = rs.getNextRecordID();
        int n = rs.getNumRecords();
        for (int id = 1; id < next && n > keep; id++)
        {
            try
            {
                rs.deleteRecord(id);
                n--;
            }
            catch (Throwable ignored) { /* already gone */ }
        }
    }

    private static void closeQuietly(RecordStore rs)
    {
        if (rs != null)
        {
            try { rs.closeRecordStore(); }
            catch (Throwable ignored) { }
        }
    }
}
