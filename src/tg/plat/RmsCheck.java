package tg.plat;

import java.util.Vector;

import javax.microedition.rms.RecordStore;

import tg.diag.Diag;

/**
 * RMS exercise and limit probe.
 *
 * RMS is where the auth_key, the session and the update state (pts/qts/seq)
 * will live, so "does it work" is not enough - we need to know how large a
 * single record may be and whether data really survives a restart. Both are
 * open questions on 2011 hardware.
 *
 * The persistence marker is deliberately written on one run and verified on the
 * next: a store that works within a session but is wiped at exit would silently
 * force a full auth_key regeneration on every launch.
 */
public final class RmsCheck
{
    private static final String STORE = "tgprobe";
    private static final String MARKER_STORE = "tgmarker";

    /** Upper bound for the record-size search; also a sanity ceiling. */
    private static final int MAX_RECORD_PROBE = 64 * 1024;

    /**
     * How much to write when asking whether the limit is shared. Well under the
     * 512 KiB seen so far, so a shared limit shows as an unmistakable drop
     * without risking filling the suite's storage to run the test.
     */
    private static final int QUOTA_PROBE_BYTES = 128 * 1024;

    private RmsCheck() { }

    /** Full CRUD pass plus a size probe. Returns report lines. */
    public static String[] run()
    {
        Vector v = new Vector(16);
        try
        {
            try { RecordStore.deleteRecordStore(STORE); }
            catch (Throwable ignored) { /* first run */ }

            RecordStore rs = RecordStore.openRecordStore(STORE, true);
            try
            {
                v.addElement("open = ok");
                v.addElement("version = " + rs.getVersion());
                v.addElement("sizeAvailable = " + rs.getSizeAvailable());

                byte[] payload = "mtproto-rms-probe".getBytes();
                int id = rs.addRecord(payload, 0, payload.length);
                v.addElement("addRecord = id " + id);

                byte[] back = rs.getRecord(id);
                boolean same = back != null && back.length == payload.length;
                if (same)
                {
                    for (int i = 0; i < payload.length; i++)
                    {
                        if (back[i] != payload[i]) { same = false; break; }
                    }
                }
                v.addElement("readBack = " + (same ? "identical" : "MISMATCH"));

                byte[] updated = "mtproto-rms-probe-updated".getBytes();
                rs.setRecord(id, updated, 0, updated.length);
                v.addElement("setRecord = " + rs.getRecordSize(id) + " bytes");

                rs.deleteRecord(id);
                v.addElement("deleteRecord = ok, numRecords " + rs.getNumRecords());

                v.addElement("largestRecord = " + largestRecord(rs) + " bytes");
                v.addElement("sizeAvailableAfter = " + rs.getSizeAvailable());
            }
            finally
            {
                try { rs.closeRecordStore(); } catch (Throwable ignored) { }
            }

            try { RecordStore.deleteRecordStore(STORE); }
            catch (Throwable ignored) { }

            // Whether that sizeAvailable is this store's headroom or the whole
            // suite's decides whether the client's cache budget fits.
            v.addElement("");
            v.addElement("-- limit shape --");
            String[] shape = quotaShape();
            for (int i = 0; i < shape.length; i++) { v.addElement(shape[i]); }
        }
        catch (Throwable t)
        {
            v.addElement("FAILED = " + Diag.className(t) + ": " + t.getMessage());
            Diag.error("rms probe failed", t);
        }

        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }

    /**
     * Read the persistence marker written by a previous launch, then write a
     * fresh one. Run this once per startup; the value it returns is evidence
     * that RMS survives MIDlet exit.
     */
    public static String checkPersistenceMarker()
    {
        RecordStore rs = null;
        try
        {
            rs = RecordStore.openRecordStore(MARKER_STORE, true);
            String previous;
            int launches;

            if (rs.getNumRecords() == 0)
            {
                previous = "none - this is the first launch";
                launches = 0;
            }
            else
            {
                int id = rs.getNextRecordID() - 1;
                byte[] b = null;
                while (id >= 1 && b == null)
                {
                    try { b = rs.getRecord(id); }
                    catch (Throwable ignored) { id--; }
                }
                previous = b == null ? "unreadable" : new String(b);
                launches = parseLaunches(previous);
            }

            launches++;
            byte[] marker = ("launch#" + launches + " at " + System.currentTimeMillis()).getBytes();
            if (rs.getNumRecords() == 0)
            {
                rs.addRecord(marker, 0, marker.length);
            }
            else
            {
                int id = rs.getNextRecordID() - 1;
                try { rs.setRecord(id, marker, 0, marker.length); }
                catch (Throwable t) { rs.addRecord(marker, 0, marker.length); }
            }
            return "previous marker: " + previous;
        }
        catch (Throwable t)
        {
            return "marker unavailable: " + Diag.className(t);
        }
        finally
        {
            if (rs != null)
            {
                try { rs.closeRecordStore(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * Is the storage limit per record store, or shared across the suite?
     *
     * A Nokia C3-00 reports {@code getSizeAvailable() = 524288} - exactly
     * 512 KiB - and the figure tracks *that store's* own size, not the total of
     * the suite's eight stores. Those two readings mean very different things:
     * shared, the client's caches (256 KiB avatars + 192 KiB history + dialogs)
     * do not fit and can starve the auth key out of {@code tgkeys}; per store,
     * they fit with room to spare and there is nothing to fix.
     *
     * {@code getSizeAvailable()} cannot tell them apart on its own, because an
     * implementation that subtracts only the current store looks identical
     * either way. Filling one store and watching another can: if the limit is
     * shared, the untouched store's headroom drops by what was written.
     *
     * Bounded to {@value #QUOTA_PROBE_BYTES} and deleted afterwards.
     */
    public static String[] quotaShape()
    {
        final String filler = "tgquotafill";
        final String witness = "tgquotawitness";
        Vector v = new Vector(8);
        try
        {
            // A second store that stays empty, to read headroom from.
            long before = headroomOf(witness, true);
            v.addElement("witness before = " + before);

            int written = fill(filler, QUOTA_PROBE_BYTES);
            v.addElement("wrote into a second store = " + written + " B");

            long after = headroomOf(witness, false);
            v.addElement("witness after = " + after);

            if (before < 0 || after < 0)
            {
                v.addElement("VERDICT: unreadable, cannot tell");
            }
            else if (written <= 0)
            {
                v.addElement("VERDICT: nothing was written, cannot tell");
            }
            else
            {
                long dropped = before - after;
                v.addElement("headroom dropped = " + dropped);
                // Allow slack for per-record overhead in the witness store.
                v.addElement(dropped > written / 2
                        ? "VERDICT: SHARED across the suite"
                        : "VERDICT: PER RECORD STORE");
            }
        }
        catch (Throwable t)
        {
            v.addElement("quota probe failed: " + Diag.className(t));
        }
        finally
        {
            drop(filler);
            drop(witness);
        }

        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }

    private static long headroomOf(String name, boolean create)
    {
        RecordStore rs = null;
        try
        {
            rs = RecordStore.openRecordStore(name, create);
            return rs.getSizeAvailable();
        }
        catch (Throwable t)
        {
            return -1;
        }
        finally
        {
            if (rs != null)
            {
                try { rs.closeRecordStore(); } catch (Throwable ignored) { }
            }
        }
    }

    /** @return bytes actually accepted, which may be less than asked for */
    private static int fill(String name, int bytes)
    {
        RecordStore rs = null;
        int written = 0;
        try
        {
            rs = RecordStore.openRecordStore(name, true);
            byte[] block = new byte[8192];
            while (written < bytes)
            {
                rs.addRecord(block, 0, block.length);
                written += block.length;
            }
        }
        catch (Throwable t)
        {
            // A store that fills early is itself the answer; report what fit.
        }
        finally
        {
            if (rs != null)
            {
                try { rs.closeRecordStore(); } catch (Throwable ignored) { }
            }
        }
        return written;
    }

    private static void drop(String name)
    {
        try { RecordStore.deleteRecordStore(name); }
        catch (Throwable ignored) { /* never existed */ }
    }

    /**
     * A non-destructive look at stores that already exist.
     *
     * {@link #run()} deletes and recreates its own store and binary-searches up
     * to 64 KiB, which is right for the probe build and wrong for the
     * messenger: record stores are scoped per MIDlet suite, so the messenger
     * has to answer "is my storage healthy" using its own quota, and it must
     * not spend that quota to find out. Reports what is there and nothing more.
     *
     * @param storeNames stores to describe; missing ones are reported as absent
     */
    public static String[] storageLines(String[] storeNames)
    {
        String[] out = new String[storeNames.length];

        for (int i = 0; i < storeNames.length; i++)
        {
            RecordStore rs = null;
            try
            {
                // createIfNecessary=false: describing storage must not create
                // any, or the answer changes by being asked.
                rs = RecordStore.openRecordStore(storeNames[i], false);
                // Headroom per store, not once for the first one. Reporting a
                // single figure hid the question this section exists to
                // answer: if every store reports its own 512 KiB the limit is
                // per store, and if they all report one shrinking number it is
                // shared and the cache budget is over it.
                out[i] = storeNames[i] + " = " + rs.getNumRecords()
                        + " rec, " + rs.getSize() + " B, free "
                        + rs.getSizeAvailable();
            }
            catch (Throwable t)
            {
                out[i] = storeNames[i] + " = absent (" + Diag.className(t) + ")";
            }
            finally
            {
                if (rs != null)
                {
                    try { rs.closeRecordStore(); } catch (Throwable ignored) { }
                }
            }
        }

        return out;
    }

    // ------------------------------------------------------------ internal

    /** Binary search for the largest record this store will accept. */
    private static int largestRecord(RecordStore rs)
    {
        int lo = 0;
        int hi = MAX_RECORD_PROBE;
        while (lo < hi)
        {
            int mid = lo + (hi - lo + 1) / 2;
            int id = -1;
            try
            {
                byte[] blob = new byte[mid];
                id = rs.addRecord(blob, 0, mid);
                lo = mid;
            }
            catch (Throwable t)
            {
                // RecordStoreFullException, or OutOfMemoryError building the blob
                hi = mid - 1;
            }
            finally
            {
                if (id > 0)
                {
                    try { rs.deleteRecord(id); } catch (Throwable ignored) { }
                }
            }
        }
        return lo;
    }

    private static int parseLaunches(String marker)
    {
        int hash = marker.indexOf('#');
        if (hash < 0) { return 0; }
        int end = hash + 1;
        while (end < marker.length() && marker.charAt(end) >= '0' && marker.charAt(end) <= '9')
        {
            end++;
        }
        try
        {
            return Integer.parseInt(marker.substring(hash + 1, end));
        }
        catch (Throwable t)
        {
            return 0;
        }
    }
}
