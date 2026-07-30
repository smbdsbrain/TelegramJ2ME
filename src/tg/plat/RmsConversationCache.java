package tg.plat;

import java.io.IOException;

import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreNotFoundException;

import tg.api.AvatarRef;
import tg.api.Dialog;
import tg.api.ForwardInfo;
import tg.api.Media;
import tg.api.Message;
import tg.api.Peer;
import tg.api.ReactionSummary;
import tg.tl.TlReader;
import tg.tl.TlWriter;

/**
 * Bounded offline snapshots for the dialog list and recent conversations.
 *
 * Media bodies and full photo references are deliberately excluded. The cache
 * is for readable text during a bad connection, not an offline media archive.
 */
public final class RmsConversationCache
{
    private static final String DIALOGS = "tgdialogcache";
    private static final String HISTORY = "tghistorycache";
    private static final int DIALOG_MAGIC = 0x54474443; // TGDC
    private static final int HISTORY_MAGIC = 0x54474832; // TGH2
    private static final int MAX_CACHED_DIALOGS = 80;
    private static final int MAX_CACHED_MESSAGES = 30;
    private static final int MAX_HISTORIES = 6;
    private static final int MAX_HISTORY_TOTAL = 192 * 1024;
    private static final int MAX_RECORD = 64 * 1024;
    private static final int MAX_TEXT = 1024;

    public synchronized Dialog[] loadDialogs(long accountId, boolean test)
            throws IOException
    {
        RecordStore rs = null;
        try
        {
            rs = RecordStore.openRecordStore(DIALOGS, true);
            if (rs.getNumRecords() == 0) { return null; }
            TlReader r = new TlReader(rs.getRecord(1));
            if (r.readInt() != DIALOG_MAGIC
                    || r.readLong() != accountId
                    || (r.readInt() != 0) != test)
            {
                return null;
            }
            r.readLong();                    // saved-at
            int count = bounded(r.readInt(), MAX_CACHED_DIALOGS);
            Dialog[] out = new Dialog[count];
            for (int i = 0; i < count; i++) { out[i] = readDialog(r); }
            return out;
        }
        catch (Throwable t) { throw io("RMS dialog cache load", t); }
        finally { close(rs); }
    }

    public synchronized void saveDialogs(long accountId, boolean test,
                                         Dialog[] dialogs) throws IOException
    {
        if (dialogs == null) { return; }
        int count = Math.min(dialogs.length, MAX_CACHED_DIALOGS);
        TlWriter w = new TlWriter(Math.min(MAX_RECORD, 128 + count * 256));
        w.writeInt(DIALOG_MAGIC);
        w.writeLong(accountId);
        w.writeInt(test ? 1 : 0);
        w.writeLong(System.currentTimeMillis());
        w.writeInt(count);
        for (int i = 0; i < count; i++) { writeDialog(w, dialogs[i]); }
        saveSingle(DIALOGS, w.toByteArray(), "RMS dialog cache save");
    }

    public synchronized Message[] loadHistory(long accountId, boolean test,
                                              Peer peer) throws IOException
    {
        if (peer == null) { return null; }
        RecordStore rs = null;
        RecordEnumeration en = null;
        try
        {
            rs = RecordStore.openRecordStore(HISTORY, true);
            en = rs.enumerateRecords(null, null, false);
            while (en.hasNextElement())
            {
                byte[] raw = en.nextRecord();
                TlReader r = new TlReader(raw);
                if (r.readInt() != HISTORY_MAGIC) { continue; }
                long account = r.readLong();
                boolean environment = r.readInt() != 0;
                int kind = r.readInt();
                long id = r.readLong();
                r.readLong();                // saved-at
                int count = bounded(r.readInt(), MAX_CACHED_MESSAGES);
                if (account == accountId && environment == test
                        && kind == peer.kind && id == peer.id)
                {
                    Message[] out = new Message[count];
                    for (int i = 0; i < count; i++)
                    {
                        out[i] = readMessage(r);
                    }
                    return out;
                }
            }
            return null;
        }
        catch (Throwable t) { throw io("RMS history cache load", t); }
        finally { destroy(en); close(rs); }
    }

    public synchronized void saveHistory(long accountId, boolean test,
                                         Peer peer, Message[] messages)
            throws IOException
    {
        if (peer == null || messages == null) { return; }
        int count = Math.min(messages.length, MAX_CACHED_MESSAGES);
        TlWriter w = new TlWriter(Math.min(MAX_RECORD, 128 + count * 512));
        w.writeInt(HISTORY_MAGIC);
        w.writeLong(accountId);
        w.writeInt(test ? 1 : 0);
        w.writeInt(peer.kind);
        w.writeLong(peer.id);
        w.writeLong(System.currentTimeMillis());
        w.writeInt(count);
        for (int i = 0; i < count; i++) { writeMessage(w, messages[i]); }
        byte[] raw = w.toByteArray();
        if (raw.length > MAX_RECORD) { return; }

        RecordStore rs = null;
        try
        {
            rs = RecordStore.openRecordStore(HISTORY, true);
            removeHistory(rs, accountId, test, peer);
            while (rs.getNumRecords() >= MAX_HISTORIES
                    || totalBytes(rs) + raw.length > MAX_HISTORY_TOTAL)
            {
                if (!removeOldest(rs)) { break; }
            }
            rs.addRecord(raw, 0, raw.length);
        }
        catch (Throwable t) { throw io("RMS history cache save", t); }
        finally { close(rs); }
    }

    public synchronized void clear() throws IOException
    {
        delete(DIALOGS);
        delete(HISTORY);
    }

    private static void writeDialog(TlWriter w, Dialog d)
    {
        if (d == null) { d = new Dialog(); }
        writePeer(w, d.peer);
        w.writeInt(d.topMessageId);
        w.writeInt(d.unreadCount);
        w.writeInt(d.pinned ? 1 : 0);
        w.writeInt(d.readInboxMaxId);
        w.writeInt(d.readOutboxMaxId);
        w.writeInt(d.channelPts);
        w.writeString(text(d.lastMessage));
        w.writeInt(d.date);
        w.writeInt(d.lastMessageOutgoing ? 1 : 0);
    }

    private static Dialog readDialog(TlReader r) throws IOException
    {
        Dialog d = new Dialog();
        d.peer = readPeer(r);
        d.topMessageId = r.readInt();
        d.unreadCount = r.readInt();
        d.pinned = r.readInt() != 0;
        d.readInboxMaxId = r.readInt();
        d.readOutboxMaxId = r.readInt();
        d.channelPts = r.readInt();
        d.lastMessage = r.readString();
        d.date = r.readInt();
        d.lastMessageOutgoing = r.readInt() != 0;
        return d;
    }

    private static void writeMessage(TlWriter w, Message m)
    {
        if (m == null) { m = new Message(); }
        w.writeInt(m.id);
        w.writeInt(m.date);
        int flags = (m.outgoing ? 1 : 0) | (m.service ? 2 : 0)
                | (m.read ? 4 : 0);
        w.writeInt(flags);
        w.writeString(text(m.text));
        writePeer(w, m.peer);
        writePeer(w, m.sender);
        if (m.media == null)
        {
            w.writeInt(0);
        }
        else
        {
            w.writeInt(1);
            w.writeInt(m.media.kind);
            w.writeString(text(m.media.label));
        }
        w.writeInt(m.replyToMessageId);
        ReactionSummary[] reactions = m.reactions == null
                ? new ReactionSummary[0] : m.reactions;
        int reactionCount = Math.min(reactions.length, 12);
        w.writeInt(reactionCount);
        for (int i = 0; i < reactionCount; i++)
        {
            ReactionSummary reaction = reactions[i];
            w.writeString(text(reaction == null ? "" : reaction.emoji));
            w.writeInt(reaction == null ? 0 : reaction.count);
            int reactionFlags = reaction != null && reaction.chosen ? 1 : 0;
            if (reaction != null && reaction.custom) { reactionFlags |= 2; }
            if (reaction != null && reaction.paid) { reactionFlags |= 4; }
            w.writeInt(reactionFlags);
            w.writeInt(reaction == null ? -1 : reaction.chosenOrder);
        }
        if (m.forwarded == null)
        {
            w.writeInt(0);
        }
        else
        {
            w.writeInt(1);
            w.writeString(text(m.forwarded.label));
            writePeer(w, m.forwarded.source);
            w.writeInt(m.forwarded.messageId);
        }
    }

    private static Message readMessage(TlReader r) throws IOException
    {
        Message m = new Message();
        m.id = r.readInt();
        m.date = r.readInt();
        int flags = r.readInt();
        m.outgoing = (flags & 1) != 0;
        m.service = (flags & 2) != 0;
        m.read = (flags & 4) != 0;
        m.text = r.readString();
        m.peer = readPeer(r);
        m.sender = readPeer(r);
        if (r.readInt() != 0)
        {
            m.media = new Media();
            m.media.kind = r.readInt();
            m.media.label = r.readString();
        }
        m.replyToMessageId = r.readInt();
        int count = bounded(r.readInt(), 12);
        m.reactions = new ReactionSummary[count];
        for (int i = 0; i < count; i++)
        {
            ReactionSummary reaction = new ReactionSummary();
            reaction.emoji = r.readString();
            reaction.count = r.readInt();
            int reactionFlags = r.readInt();
            reaction.chosen = (reactionFlags & 1) != 0;
            reaction.custom = (reactionFlags & 2) != 0;
            reaction.paid = (reactionFlags & 4) != 0;
            reaction.chosenOrder = r.readInt();
            m.reactions[i] = reaction;
        }
        if (r.readInt() != 0)
        {
            m.forwarded = new ForwardInfo();
            m.forwarded.label = r.readString();
            m.forwarded.source = readPeer(r);
            m.forwarded.messageId = r.readInt();
        }
        return m;
    }

    private static void writePeer(TlWriter w, Peer peer)
    {
        if (peer == null)
        {
            w.writeInt(0);
            return;
        }
        w.writeInt(1);
        w.writeInt(peer.kind);
        w.writeLong(peer.id);
        w.writeLong(peer.accessHash);
        w.writeString(text(peer.title));
        w.writeString(text(peer.firstName));
        w.writeString(text(peer.lastName));
        w.writeString(text(peer.username));
        w.writeInt(peer.self ? 1 : 0);
        if (peer.avatar == null)
        {
            w.writeInt(0);
        }
        else
        {
            w.writeInt(1);
            w.writeLong(peer.avatar.photoId);
            w.writeInt(peer.avatar.dcId);
            byte[] stripped = peer.avatar.strippedThumb;
            w.writeBytes(stripped == null ? new byte[0] : stripped);
        }
    }

    private static Peer readPeer(TlReader r) throws IOException
    {
        if (r.readInt() == 0) { return null; }
        Peer peer = new Peer(r.readInt(), r.readLong());
        peer.accessHash = r.readLong();
        peer.title = r.readString();
        peer.firstName = r.readString();
        peer.lastName = r.readString();
        peer.username = emptyToNull(r.readString());
        peer.self = r.readInt() != 0;
        if (r.readInt() != 0)
        {
            peer.avatar = new AvatarRef();
            peer.avatar.photoId = r.readLong();
            peer.avatar.dcId = r.readInt();
            byte[] stripped = r.readBytes();
            peer.avatar.strippedThumb = stripped.length == 0 ? null : stripped;
        }
        return peer;
    }

    private static String text(String value)
    {
        if (value == null) { return ""; }
        return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
    }

    private static String emptyToNull(String value)
    {
        return value == null || value.length() == 0 ? null : value;
    }

    private static int bounded(int value, int max) throws IOException
    {
        if (value < 0 || value > max)
        {
            throw new IOException("cached item count out of bounds");
        }
        return value;
    }

    private static void saveSingle(String name, byte[] raw, String operation)
            throws IOException
    {
        if (raw.length > MAX_RECORD) { return; }
        RecordStore rs = null;
        try
        {
            try { RecordStore.deleteRecordStore(name); }
            catch (RecordStoreNotFoundException ignored) { }
            rs = RecordStore.openRecordStore(name, true);
            rs.addRecord(raw, 0, raw.length);
        }
        catch (Throwable t) { throw io(operation, t); }
        finally { close(rs); }
    }

    private static void removeHistory(RecordStore rs, long accountId,
                                      boolean test, Peer peer) throws Exception
    {
        RecordEnumeration en = null;
        try
        {
            en = rs.enumerateRecords(null, null, false);
            while (en.hasNextElement())
            {
                int id = en.nextRecordId();
                Header h = header(rs.getRecord(id));
                if (h == null || (h.accountId == accountId && h.test == test
                        && h.kind == peer.kind && h.peerId == peer.id))
                {
                    rs.deleteRecord(id);
                }
            }
        }
        finally { destroy(en); }
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
                Header h = header(rs.getRecord(id));
                long savedAt = h == null ? Long.MIN_VALUE : h.savedAt;
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

    private static Header header(byte[] raw)
    {
        try
        {
            TlReader r = new TlReader(raw);
            if (r.readInt() != HISTORY_MAGIC) { return null; }
            Header h = new Header();
            h.accountId = r.readLong();
            h.test = r.readInt() != 0;
            h.kind = r.readInt();
            h.peerId = r.readLong();
            h.savedAt = r.readLong();
            return h;
        }
        catch (Throwable ignored) { return null; }
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

    private static void delete(String name) throws IOException
    {
        try { RecordStore.deleteRecordStore(name); }
        catch (RecordStoreNotFoundException ignored) { }
        catch (Throwable t) { throw io("RMS cache clear", t); }
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

    private static final class Header
    {
        long accountId;
        boolean test;
        int kind;
        long peerId;
        long savedAt;
    }
}
