package tg.plat;

import java.io.IOException;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreNotFoundException;

import tg.api.UpdateState;
import tg.api.UpdateStateCodec;
import tg.api.UpdateStateStore;
import tg.diag.Diag;

/** One versioned RMS record containing the common and channel update cursors. */
public final class RmsUpdateStateStore implements UpdateStateStore
{
    private static final String STORE = "tgupdates";
    public synchronized UpdateState load(long accountId, boolean testEnvironment)
            throws IOException
    {
        RecordStore rs = null;
        try
        {
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() == 0) { return null; }
            UpdateState state = UpdateStateCodec.decode(
                    rs.getRecord(1), accountId, testEnvironment);
            if (state == null)
            {
                Diag.info("update state belongs to another account/environment");
                clearOpen(rs);
                return null;
            }
            return state;
        }
        catch (IOException e)
        {
            try { if (rs != null) { clearOpen(rs); } } catch (Throwable ignored) { }
            throw e;
        }
        catch (Throwable t)
        {
            try { if (rs != null) { clearOpen(rs); } } catch (Throwable ignored) { }
            throw io("RMS update-state load", t);
        }
        finally { close(rs); }
    }

    public synchronized void save(UpdateState state) throws IOException
    {
        if (state == null) { clear(); return; }
        RecordStore rs = null;
        try
        {
            byte[] raw = UpdateStateCodec.encode(state);
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() == 0)
            {
                rs.addRecord(raw, 0, raw.length);
            }
            else
            {
                rs.setRecord(1, raw, 0, raw.length);
            }
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

    private static void clearOpen(RecordStore rs) throws Exception
    {
        while (rs.getNumRecords() > 0)
        {
            // This store always owns record 1. Recreate it if a broken RMS
            // implementation has lost record ids rather than guessing.
            close(rs);
            try { RecordStore.deleteRecordStore(STORE); }
            catch (RecordStoreNotFoundException ignored) { }
            return;
        }
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
}
