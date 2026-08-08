package tg.plat;

import java.io.IOException;

import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreNotFoundException;

import tg.api.RecordEnvelope;
import tg.api.UpdateState;
import tg.api.UpdateStateCodec;
import tg.api.UpdateStateStore;
import tg.diag.Diag;
import tg.mt.Dc;

/**
 * Where the update stream was left, so a restart resumes instead of resyncing.
 *
 * The cheapest store to lose and the most expensive to get wrong. Losing it
 * costs one {@code updates.getState} and a fetch of whatever arrived while the
 * client was away; <em>believing a wrong one</em> costs correctness - a pts
 * from another account, or half-written after a power cut, tells Telegram the
 * client has seen updates it has not, and the difference is never requested.
 *
 * So there is no middle answer here. The record verifies, or it is removed and
 * the caller is told there is no state - which sends it down the path it
 * already has for a first launch: ask for a snapshot.
 *
 * <h3>Replacement is add-then-delete</h3>
 * {@code setRecord} in place has no interrupted state that is still readable.
 * Writing a new record and removing the old one leaves either the previous
 * cursor or the new one, and {@link #load} keeps the higher record id - which
 * is the newer, because RMS never reuses an id.
 */
public final class RmsUpdateStateStore implements UpdateStateStore
{
    private static final String STORE = "tgupdates";

    /** TGU3 - the envelope-wrapped generation. */
    private static final int MAGIC = 0x54475533;
    private static final int VERSION = 1;

    /**
     * Whether the last load found something it had to throw away.
     *
     * Read by diagnostics. "No state" and "state that could not be trusted"
     * take the same recovery path, and they are worth telling apart in a device
     * report because only the second one says the storage is misbehaving.
     */
    private boolean lastLoadReset;

    private String lastResetReason = "";

    public synchronized boolean lastLoadWasReset() { return lastLoadReset; }

    public synchronized String lastResetReason() { return lastResetReason; }

    public synchronized UpdateState load(long accountId, boolean testEnvironment)
            throws IOException
    {
        lastLoadReset = false;
        lastResetReason = "";
        RecordStore rs = null;
        RecordEnumeration records = null;
        try
        {
            rs = RecordStore.openRecordStore(STORE, true);

            int bestId = 0;
            UpdateState best = null;
            String reject = null;

            records = rs.enumerateRecords(null, null, false);
            while (records.hasNextElement())
            {
                int id = records.nextRecordId();
                byte[] raw;
                try { raw = rs.getRecord(id); }
                catch (Throwable t) { reject = "unreadable row"; continue; }

                UpdateState state = read(raw, accountId, testEnvironment);
                if (state == null)
                {
                    // Legacy rows are the bare codec output, from before the
                    // envelope. Read once and then rewritten by the next save.
                    state = legacy(raw, accountId, testEnvironment);
                }
                if (state == null)
                {
                    reject = "a record that is not this account's or is damaged";
                    continue;
                }
                if (id > bestId) { bestId = id; best = state; }
            }

            if (best == null)
            {
                if (reject != null)
                {
                    // Not "there is no state": there was one and it could not
                    // be trusted. Removed, so the next launch does not pay to
                    // reject it again, and reported so the caller asks for a
                    // snapshot rather than resuming from nothing.
                    lastLoadReset = true;
                    lastResetReason = reject;
                    Diag.warn("update state reset: " + reject);
                    close(records);
                    records = null;
                    close(rs);
                    rs = null;
                    clear();
                }
                return null;
            }
            return best;
        }
        catch (IOException e) { throw e; }
        catch (Throwable t) { throw io("RMS update-state load", t); }
        finally
        {
            close(records);
            close(rs);
        }
    }

    public synchronized void save(UpdateState state) throws IOException
    {
        if (state == null) { clear(); return; }
        RecordStore rs = null;
        try
        {
            byte[] raw = RecordEnvelope.wrap(MAGIC, VERSION, state.accountId,
                    Dc.isTest(), UpdateStateCodec.encode(state));
            rs = RecordStore.openRecordStore(STORE, true);

            int fresh = rs.addRecord(raw, 0, raw.length);
            if (!identical(rs, fresh, raw))
            {
                try { rs.deleteRecord(fresh); } catch (Throwable ignored) { }
                throw new IOException("update state did not read back");
            }
            removeOthers(rs, fresh);
        }
        catch (IOException e) { throw e; }
        catch (Throwable t) { throw io("RMS update-state save", t); }
        finally { close(rs); }
    }

    public synchronized void clear() throws IOException
    {
        try { RecordStore.deleteRecordStore(STORE); }
        catch (RecordStoreNotFoundException ignored) { }
        catch (Throwable t) { throw io("RMS update-state clear", t); }
    }

    // ------------------------------------------------------------ internals

    private static UpdateState read(byte[] raw, long accountId,
                                    boolean testEnvironment)
    {
        RecordEnvelope envelope = RecordEnvelope.unwrap(raw, MAGIC, VERSION,
                VERSION, accountId, Dc.isTest());
        if (!envelope.isOk()) { return null; }
        try { return UpdateStateCodec.decode(envelope.payload, accountId, testEnvironment); }
        catch (Throwable t) { return null; }
    }

    private static UpdateState legacy(byte[] raw, long accountId,
                                      boolean testEnvironment)
    {
        try { return UpdateStateCodec.decode(raw, accountId, testEnvironment); }
        catch (Throwable notOurs) { return null; }
    }

    /**
     * Leave exactly one record. Failing here is survivable - the newest wins on
     * the next load - so it warns rather than throwing over a committed write.
     */
    private static void removeOthers(RecordStore rs, int keep)
    {
        RecordEnumeration records = null;
        try
        {
            records = rs.enumerateRecords(null, null, false);
            java.util.Vector doomed = new java.util.Vector();
            while (records.hasNextElement())
            {
                int id = records.nextRecordId();
                if (id != keep) { doomed.addElement(new Integer(id)); }
            }
            for (int i = 0; i < doomed.size(); i++)
            {
                int id = ((Integer) doomed.elementAt(i)).intValue();
                try { rs.deleteRecord(id); }
                catch (Throwable t) { Diag.warn("update state left row " + id); }
            }
        }
        catch (Throwable t) { Diag.warn("update state cleanup failed"); }
        finally { close(records); }
    }

    private static boolean identical(RecordStore rs, int id, byte[] expected)
    {
        byte[] actual;
        try { actual = rs.getRecord(id); }
        catch (Throwable t) { return false; }
        if (actual == null || actual.length != expected.length) { return false; }
        for (int i = 0; i < expected.length; i++)
        {
            if (actual[i] != expected[i]) { return false; }
        }
        return true;
    }

    private static IOException io(String operation, Throwable t)
    {
        return new IOException(operation + ": " + t.getClass().getName()
                + ": " + String.valueOf(t.getMessage()));
    }

    private static void close(RecordStore rs)
    {
        if (rs != null) { try { rs.closeRecordStore(); } catch (Throwable ignored) { } }
    }

    private static void close(RecordEnumeration records)
    {
        if (records != null) { try { records.destroy(); } catch (Throwable ignored) { } }
    }
}
