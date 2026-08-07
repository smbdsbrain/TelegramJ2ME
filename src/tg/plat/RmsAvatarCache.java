package tg.plat;

import java.io.IOException;

import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreNotFoundException;

import tg.api.AccountStore;
import tg.api.Peer;
import tg.tl.TlReader;
import tg.tl.TlWriter;

/**
 * Bounded RMS cache of compressed dialog-avatar thumbnails.
 *
 * At most twelve records and 256 KiB are retained. Full-size photos never
 * enter this store.
 */
public final class RmsAvatarCache implements AccountStore
{
    private static final String STORE = "tgavatars";
    private static final int MAGIC = 0x54474131; // TGA1
    private static final int MAX_ENTRIES = 12;
    private static final int MAX_TOTAL = 256 * 1024;
    private static final int MAX_ENTRY = 64 * 1024;

    public synchronized byte[] load(long accountId, boolean test, Peer peer)
            throws IOException
    {
        if (!valid(peer)) { return null; }
        RecordStore rs = null;
        RecordEnumeration en = null;
        try
        {
            rs = RecordStore.openRecordStore(STORE, true);
            en = rs.enumerateRecords(null, null, false);
            while (en.hasNextElement())
            {
                int id = en.nextRecordId();
                Entry entry = decode(rs.getRecord(id));
                if (entry != null && entry.accountId == accountId
                        && entry.test == test && entry.kind == peer.kind
                        && entry.peerId == peer.id
                        && entry.photoId == peer.avatar.photoId)
                {
                    entry.savedAt = System.currentTimeMillis();
                    byte[] touched = encode(entry);
                    rs.setRecord(id, touched, 0, touched.length);
                    return entry.bytes;
                }
            }
            return null;
        }
        catch (Throwable t) { throw io("RMS avatar load", t); }
        finally { destroy(en); close(rs); }
    }

    public synchronized void save(long accountId, boolean test, Peer peer,
                                  byte[] bytes) throws IOException
    {
        if (!valid(peer) || bytes == null || bytes.length < 4
                || bytes.length > MAX_ENTRY)
        {
            return;
        }
        RecordStore rs = null;
        try
        {
            Entry entry = new Entry();
            entry.accountId = accountId;
            entry.test = test;
            entry.kind = peer.kind;
            entry.peerId = peer.id;
            entry.photoId = peer.avatar.photoId;
            entry.savedAt = System.currentTimeMillis();
            entry.bytes = bytes;
            byte[] raw = encode(entry);
            rs = RecordStore.openRecordStore(STORE, true);
            removeSamePeer(rs, accountId, test, peer);
            while (rs.getNumRecords() >= MAX_ENTRIES
                    || totalBytes(rs) + raw.length > MAX_TOTAL)
            {
                if (!removeOldest(rs)) { break; }
            }
            rs.addRecord(raw, 0, raw.length);
        }
        catch (Throwable t) { throw io("RMS avatar save", t); }
        finally { close(rs); }
    }

    public synchronized void clear() throws IOException
    {
        try { RecordStore.deleteRecordStore(STORE); }
        catch (RecordStoreNotFoundException ignored) { }
        catch (Throwable t) { throw io("RMS avatars clear", t); }
    }

    private static void removeSamePeer(RecordStore rs, long accountId,
                                       boolean test, Peer peer)
            throws Exception
    {
        RecordEnumeration en = null;
        try
        {
            en = rs.enumerateRecords(null, null, false);
            while (en.hasNextElement())
            {
                int id = en.nextRecordId();
                Entry entry = decode(rs.getRecord(id));
                if (entry == null || (entry.accountId == accountId
                        && entry.test == test && entry.kind == peer.kind
                        && entry.peerId == peer.id))
                {
                    rs.deleteRecord(id);
                }
            }
        }
        finally { destroy(en); }
    }

    private static int totalBytes(RecordStore rs) throws Exception
    {
        int total = 0;
        RecordEnumeration en = null;
        try
        {
            en = rs.enumerateRecords(null, null, false);
            while (en.hasNextElement())
            {
                total += rs.getRecordSize(en.nextRecordId());
            }
        }
        finally { destroy(en); }
        return total;
    }

    private static boolean removeOldest(RecordStore rs) throws Exception
    {
        int oldestId = -1;
        long oldestAt = Long.MAX_VALUE;
        RecordEnumeration en = null;
        try
        {
            en = rs.enumerateRecords(null, null, false);
            while (en.hasNextElement())
            {
                int id = en.nextRecordId();
                Entry entry = decode(rs.getRecord(id));
                long savedAt = entry == null ? Long.MIN_VALUE : entry.savedAt;
                if (oldestId < 0 || savedAt < oldestAt)
                {
                    oldestId = id;
                    oldestAt = savedAt;
                }
            }
        }
        finally { destroy(en); }
        if (oldestId < 0) { return false; }
        rs.deleteRecord(oldestId);
        return true;
    }

    private static Entry decode(byte[] raw)
    {
        try
        {
            TlReader reader = new TlReader(raw);
            if (reader.readInt() != MAGIC) { return null; }
            Entry out = new Entry();
            out.accountId = reader.readLong();
            out.test = reader.readInt() != 0;
            out.kind = reader.readInt();
            out.peerId = reader.readLong();
            out.photoId = reader.readLong();
            out.savedAt = reader.readLong();
            out.bytes = reader.readBytes();
            if (out.bytes == null || out.bytes.length > MAX_ENTRY) { return null; }
            return out;
        }
        catch (Throwable ignored) { return null; }
    }

    private static byte[] encode(Entry entry)
    {
        TlWriter writer = new TlWriter(entry.bytes.length + 48);
        writer.writeInt(MAGIC);
        writer.writeLong(entry.accountId);
        writer.writeInt(entry.test ? 1 : 0);
        writer.writeInt(entry.kind);
        writer.writeLong(entry.peerId);
        writer.writeLong(entry.photoId);
        writer.writeLong(entry.savedAt);
        writer.writeBytes(entry.bytes);
        return writer.toByteArray();
    }

    private static boolean valid(Peer peer)
    {
        return peer != null && peer.avatar != null
                && peer.avatar.photoId != 0;
    }

    private static IOException io(String operation, Throwable t)
    {
        return new IOException(operation + ": " + t.getClass().getName()
                + ": " + String.valueOf(t.getMessage()));
    }

    private static void destroy(RecordEnumeration en)
    {
        if (en != null) { try { en.destroy(); } catch (Throwable ignored) { } }
    }

    private static void close(RecordStore rs)
    {
        if (rs != null) { try { rs.closeRecordStore(); } catch (Throwable ignored) { } }
    }

    private static final class Entry
    {
        long accountId;
        boolean test;
        int kind;
        long peerId;
        long photoId;
        long savedAt;
        byte[] bytes;
    }
}
