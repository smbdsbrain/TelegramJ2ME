package tg.plat;

import java.io.IOException;

import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;

import tg.api.DraftStore;
import tg.api.Peer;
import tg.tl.TlReader;
import tg.tl.TlWriter;

/** UTF-8 per-peer drafts in a dedicated record store. */
public final class RmsDraftStore implements DraftStore
{
    private static final String STORE = "tgdrafts";
    private static final int MAGIC = 0x54474432; // TGD2

    public synchronized String load(Peer peer) throws IOException
    {
        RecordStore store = null;
        RecordEnumeration records = null;
        try
        {
            store = RecordStore.openRecordStore(STORE, true);
            records = store.enumerateRecords(null, null, false);
            while (records.hasNextElement())
            {
                TlReader reader = new TlReader(records.nextRecord());
                if (reader.readInt() == MAGIC
                        && reader.readInt() == peer.kind
                        && reader.readLong() == peer.id)
                {
                    return reader.readString();
                }
            }
            return "";
        }
        catch (Throwable t) { throw io("RMS draft load", t); }
        finally
        {
            if (records != null) { try { records.destroy(); } catch (Throwable ignored) { } }
            close(store);
        }
    }

    public synchronized void save(Peer peer, String text) throws IOException
    {
        RecordStore store = null;
        RecordEnumeration records = null;
        try
        {
            store = RecordStore.openRecordStore(STORE, true);
            records = store.enumerateRecords(null, null, false);
            while (records.hasNextElement())
            {
                int id = records.nextRecordId();
                try
                {
                    TlReader reader = new TlReader(store.getRecord(id));
                    if (reader.readInt() == MAGIC
                            && reader.readInt() == peer.kind
                            && reader.readLong() == peer.id)
                    {
                        store.deleteRecord(id);
                    }
                }
                catch (Throwable ignored) { }
            }
            if (text != null && text.length() > 0)
            {
                TlWriter writer = new TlWriter(text.length() * 3 + 24);
                writer.writeInt(MAGIC);
                writer.writeInt(peer.kind);
                writer.writeLong(peer.id);
                writer.writeString(text);
                byte[] raw = writer.toByteArray();
                store.addRecord(raw, 0, raw.length);
            }
        }
        catch (Throwable t) { throw io("RMS draft save", t); }
        finally
        {
            if (records != null) { try { records.destroy(); } catch (Throwable ignored) { } }
            close(store);
        }
    }

    public synchronized void clear() throws IOException
    {
        try { RecordStore.deleteRecordStore(STORE); }
        catch (javax.microedition.rms.RecordStoreNotFoundException ignored) { }
        catch (Throwable t) { throw io("RMS drafts clear", t); }
    }

    private static IOException io(String operation, Throwable t)
    {
        return new IOException(operation + ": " + t.getClass().getName()
                + ": " + String.valueOf(t.getMessage()));
    }

    private static void close(RecordStore store)
    {
        if (store != null) { try { store.closeRecordStore(); } catch (Throwable ignored) { } }
    }
}
