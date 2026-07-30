package tg.plat;

import java.io.IOException;
import java.util.Vector;

import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;

import tg.api.OutgoingMessage;
import tg.api.OutgoingStore;
import tg.api.Peer;
import tg.diag.Diag;

/** One versioned binary RMS record per queued or failed message. */
public final class RmsOutgoingStore implements OutgoingStore
{
    private static final String STORE = "tgoutbox";
    private static final int MAX_ITEMS = 64;

    public synchronized OutgoingMessage add(Peer peer, String text,
            long randomId, long createdAt) throws IOException
    {
        return add(peer, text, 0, randomId, createdAt);
    }

    public synchronized OutgoingMessage add(Peer peer, String text,
            int replyToMessageId, long randomId, long createdAt)
            throws IOException
    {
        RecordStore store = null;
        try
        {
            store = RecordStore.openRecordStore(STORE, true);
            if (store.getNumRecords() >= MAX_ITEMS)
            {
                throw new IOException("outbox is full (" + MAX_ITEMS + " messages)");
            }
            OutgoingMessage message = new OutgoingMessage();
            message.peerKind = peer.kind;
            message.peerId = peer.id;
            message.accessHash = peer.accessHash;
            message.peerTitle = peer.title == null ? "" : peer.title;
            message.text = text;
            message.replyToMessageId = replyToMessageId;
            message.randomId = randomId;
            message.createdAt = createdAt;
            byte[] raw = OutgoingMessage.encode(message);
            message.localId = store.addRecord(raw, 0, raw.length);
            return message;
        }
        catch (IOException e) { throw e; }
        catch (Throwable t) { throw io("RMS outbox add", t); }
        finally { close(store); }
    }

    public synchronized OutgoingMessage[] list() throws IOException
    {
        RecordStore store = null;
        RecordEnumeration records = null;
        Vector values = new Vector();
        try
        {
            store = RecordStore.openRecordStore(STORE, true);
            records = store.enumerateRecords(null, null, false);
            while (records.hasNextElement())
            {
                int id = records.nextRecordId();
                try
                {
                    values.addElement(OutgoingMessage.decode(id, store.getRecord(id)));
                }
                catch (Throwable t)
                {
                    Diag.error("skipping corrupt outbox record " + id, t);
                }
            }
        }
        catch (Throwable t) { throw io("RMS outbox list", t); }
        finally
        {
            if (records != null) { try { records.destroy(); } catch (Throwable ignored) { } }
            close(store);
        }
        OutgoingMessage[] out = new OutgoingMessage[values.size()];
        values.copyInto(out);
        for (int i = 1; i < out.length; i++)
        {
            OutgoingMessage value = out[i];
            int j = i - 1;
            while (j >= 0 && later(out[j], value))
            {
                out[j + 1] = out[j];
                j--;
            }
            out[j + 1] = value;
        }
        return out;
    }

    public synchronized void save(OutgoingMessage message) throws IOException
    {
        RecordStore store = null;
        try
        {
            store = RecordStore.openRecordStore(STORE, true);
            byte[] raw = OutgoingMessage.encode(message);
            store.setRecord(message.localId, raw, 0, raw.length);
        }
        catch (Throwable t) { throw io("RMS outbox save", t); }
        finally { close(store); }
    }

    public synchronized void remove(int localId) throws IOException
    {
        RecordStore store = null;
        try
        {
            store = RecordStore.openRecordStore(STORE, true);
            store.deleteRecord(localId);
        }
        catch (Throwable t) { throw io("RMS outbox remove", t); }
        finally { close(store); }
    }

    public synchronized void clear() throws IOException
    {
        try { RecordStore.deleteRecordStore(STORE); }
        catch (javax.microedition.rms.RecordStoreNotFoundException ignored) { }
        catch (Throwable t) { throw io("RMS outbox clear", t); }
    }

    private static boolean later(OutgoingMessage a, OutgoingMessage b)
    {
        return a.createdAt > b.createdAt
                || (a.createdAt == b.createdAt && a.localId > b.localId);
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
