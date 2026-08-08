package tg.plat;

import java.io.IOException;

import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreNotFoundException;

import java.util.Vector;

import tg.api.AccountStore;
import tg.api.AvatarRef;
import tg.api.Cached;
import tg.api.Dialog;
import tg.api.ForwardInfo;
import tg.api.Media;
import tg.api.Message;
import tg.api.MessageEntity;
import tg.api.Peer;
import tg.api.ReactionSummary;
import tg.api.RecordEnvelope;
import tg.diag.Diag;
import tg.tl.TlReader;
import tg.tl.TlWriter;

/**
 * Bounded offline snapshots for the dialog list and recent conversations.
 *
 * Media bodies and full photo references are deliberately excluded. The cache
 * is for readable text during a bad connection, not an offline media archive.
 *
 * <h3>Cache loss is not failure</h3>
 * Everything here is a copy of something Telegram still has, so the recovery
 * for any damage is to drop the entry and fetch again. That is the difference
 * between this and the outbox: there, losing a row loses a message; here it
 * costs one request. So a damaged record is removed rather than repaired, and a
 * format this build does not recognise is discarded rather than migrated.
 *
 * What is <em>not</em> acceptable is a damaged record taking the rest of the
 * cache with it, which is what a single throw part-way through a load used to
 * do, or a cached conversation that looks freshly fetched - see {@link Cached}.
 *
 * <h3>Bounds come before allocation</h3>
 * Every count and length is checked against its limit before the array behind
 * it is allocated. A corrupt length field is otherwise an OutOfMemoryError
 * during a load, on the device with the smallest heap.
 */
public final class RmsConversationCache implements AccountStore
{
    private static final String DIALOGS = "tgdialogcache";
    private static final String HISTORY = "tghistorycache";

    /** TGD4/TGH4 - the envelope-wrapped generation. */
    private static final int DIALOG_MAGIC = 0x54474434;
    private static final int HISTORY_MAGIC = 0x54474834;
    private static final int DIALOG_VERSION = 1;
    private static final int HISTORY_VERSION = 3;
    private static final int MAX_CACHED_DIALOGS = 80;
    private static final int MAX_CACHED_MESSAGES = 30;
    private static final int MAX_HISTORIES = 6;
    private static final int MAX_HISTORY_TOTAL = 192 * 1024;
    private static final int MAX_RECORD = 64 * 1024;
    private static final int MAX_TEXT = 1024;

    /**
     * The cached dialog list, with the time it was written.
     *
     * @return null when there is nothing usable - which covers absent,
     *         damaged, another account and a format this build does not read
     */
    public synchronized Cached loadDialogs(long accountId, boolean test)
            throws IOException
    {
        RecordStore rs = null;
        RecordEnumeration en = null;
        try
        {
            rs = RecordStore.openRecordStore(DIALOGS, true);
            en = rs.enumerateRecords(null, null, false);
            Vector doomed = new Vector();
            Cached best = null;
            int bestId = 0;

            while (en.hasNextElement())
            {
                int id = en.nextRecordId();
                byte[] raw;
                try { raw = rs.getRecord(id); }
                catch (Throwable t) { continue; }

                RecordEnvelope envelope = RecordEnvelope.unwrap(raw,
                        DIALOG_MAGIC, DIALOG_VERSION, DIALOG_VERSION,
                        accountId, test);
                if (!envelope.isOk())
                {
                    // Anything that is not this build, this account and intact
                    // is refuse. Nothing here is worth keeping for a build that
                    // might come back for it.
                    doomed.addElement(new Integer(id));
                    dropped++;
                    continue;
                }
                try
                {
                    TlReader r = new TlReader(envelope.payload);
                    long savedAt = r.readLong();
                    int count = bounded(r.readInt(), MAX_CACHED_DIALOGS);
                    Dialog[] out = new Dialog[count];
                    for (int i = 0; i < count; i++) { out[i] = readDialog(r); }
                    if (id > bestId)
                    {
                        bestId = id;
                        best = Cached.of(out, savedAt);
                    }
                }
                catch (Throwable t)
                {
                    doomed.addElement(new Integer(id));
                    dropped++;
                }
            }

            purge(rs, doomed, "dialog cache");
            return best;
        }
        catch (Throwable t) { throw io("RMS dialog cache load", t); }
        finally { destroy(en); close(rs); }
    }

    public synchronized void saveDialogs(long accountId, boolean test,
                                         Dialog[] dialogs) throws IOException
    {
        if (dialogs == null) { return; }
        int count = Math.min(dialogs.length, MAX_CACHED_DIALOGS);
        TlWriter w = new TlWriter(Math.min(MAX_RECORD, 128 + count * 256));
        w.writeLong(System.currentTimeMillis());
        w.writeInt(count);
        for (int i = 0; i < count; i++) { writeDialog(w, dialogs[i]); }
        saveSingle(DIALOGS, RecordEnvelope.wrap(DIALOG_MAGIC, DIALOG_VERSION,
                accountId, test, w.toByteArray()), "RMS dialog cache save");
    }

    /**
     * One conversation from the cache, with the time it was written.
     *
     * A damaged entry takes itself out and nothing else with it. The whole load
     * used to abort on the first record that would not parse, so one corrupt
     * conversation hid every other cached one until the store was cleared.
     */
    public synchronized Cached loadHistory(long accountId, boolean test,
                                           Peer peer) throws IOException
    {
        if (peer == null) { return null; }
        RecordStore rs = null;
        RecordEnumeration en = null;
        try
        {
            rs = RecordStore.openRecordStore(HISTORY, true);
            en = rs.enumerateRecords(null, null, false);
            Vector doomed = new Vector();
            Cached found = null;

            while (en.hasNextElement())
            {
                int recordId = en.nextRecordId();
                byte[] raw;
                try { raw = rs.getRecord(recordId); }
                catch (Throwable t) { continue; }

                RecordEnvelope envelope = RecordEnvelope.unwrap(raw,
                        HISTORY_MAGIC, 1, HISTORY_VERSION, accountId, test);
                if (!envelope.isOk())
                {
                    doomed.addElement(new Integer(recordId));
                    dropped++;
                    continue;
                }
                if (found != null) { continue; }
                try
                {
                    TlReader r = new TlReader(envelope.payload);
                    int kind = r.readInt();
                    long id = r.readLong();
                    long savedAt = r.readLong();
                    if (kind != peer.kind || id != peer.id) { continue; }
                    int count = bounded(r.readInt(), MAX_CACHED_MESSAGES);
                    Message[] out = new Message[count];
                    for (int i = 0; i < count; i++)
                    {
                        out[i] = readMessage(r, envelope.version);
                    }
                    found = Cached.of(out, savedAt);
                }
                catch (Throwable t)
                {
                    doomed.addElement(new Integer(recordId));
                    dropped++;
                }
            }

            purge(rs, doomed, "history cache");
            return found;
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
        w.writeInt(peer.kind);
        w.writeLong(peer.id);
        w.writeLong(System.currentTimeMillis());
        w.writeInt(count);
        for (int i = 0; i < count; i++) { writeMessage(w, messages[i]); }
        byte[] raw = RecordEnvelope.wrap(HISTORY_MAGIC, HISTORY_VERSION,
                accountId,
                test, w.toByteArray());
        if (raw.length > MAX_RECORD) { return; }

        RecordStore rs = null;
        try
        {
            rs = RecordStore.openRecordStore(HISTORY, true);
            removeHistory(rs, accountId, test, peer);
            while (rs.getNumRecords() >= MAX_HISTORIES
                    || totalBytes(rs) + raw.length > MAX_HISTORY_TOTAL)
            {
                if (!evictOldest(rs)) { break; }
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
        dropped = 0;
        evicted = 0;
    }

    /**
     * Cache records this run has thrown away, by reason.
     *
     * Counts only - no peer, no text. They go into a device report, and the
     * question a device report has to answer is whether the storage is
     * misbehaving, not what was in it.
     */
    public synchronized int droppedRecords() { return dropped; }

    public synchronized int evictedRecords() { return evicted; }

    private int dropped;
    private int evicted;

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
        MessageEntity[] entities = m.entities == null
                ? new MessageEntity[0] : m.entities;
        int entityCount = Math.min(entities.length, 8);
        w.writeInt(entityCount);
        for (int i = 0; i < entityCount; i++)
        {
            MessageEntity entity = entities[i];
            w.writeInt(entity == null ? 0 : entity.type);
            w.writeInt(entity == null ? 0 : entity.offset);
            w.writeInt(entity == null ? 0 : entity.length);
            w.writeString(text(entity == null ? "" : entity.value));
            w.writeLong(entity == null ? 0 : entity.userId);
        }
        w.writeInt(m.editDate);
    }

    private static Message readMessage(TlReader r, int version)
            throws IOException
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
        if (version >= 2)
        {
            int entityCount = bounded(r.readInt(), 8);
            m.entities = new MessageEntity[entityCount];
            for (int i = 0; i < entityCount; i++)
            {
                MessageEntity entity = new MessageEntity();
                entity.type = r.readInt();
                entity.offset = r.readInt();
                entity.length = r.readInt();
                entity.value = emptyToNull(r.readString());
                entity.userId = r.readLong();
                if (!MessageEntity.validRange(m.text, entity.offset,
                        entity.length))
                {
                    throw new IOException("invalid cached message entity");
                }
                m.entities[i] = entity;
            }
        }
        if (version >= 3) { m.editDate = r.readInt(); }
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

    /** {@link #removeOldest} with the eviction counted. */
    private boolean evictOldest(RecordStore rs) throws Exception
    {
        if (!removeOldest(rs)) { return false; }
        evicted++;
        return true;
    }

    /**
     * Enough of a history record to evict by, without decoding the messages.
     *
     * Read with an owner of 0, which matches any account: eviction has to be
     * able to see - and reclaim - space held by an account that is no longer
     * signed in.
     */
    private static Header header(byte[] raw)
    {
        try
        {
            RecordEnvelope envelope = RecordEnvelope.unwrapAnyOwner(raw,
                    HISTORY_MAGIC, 1, HISTORY_VERSION);
            if (!envelope.isOk()) { return null; }
            TlReader r = new TlReader(envelope.payload);
            Header h = new Header();
            h.accountId = envelope.accountId;
            h.test = envelope.testEnvironment;
            h.kind = r.readInt();
            h.peerId = r.readLong();
            h.savedAt = r.readLong();
            return h;
        }
        catch (Throwable ignored) { return null; }
    }

    /**
     * Delete the records a load decided were refuse.
     *
     * After the enumeration rather than during it: deleting under an open
     * enumeration is undefined on some handsets, and this runs on the display
     * thread where a thrown exception costs a screen.
     */
    private void purge(RecordStore rs, Vector doomed, String what)
    {
        for (int i = 0; i < doomed.size(); i++)
        {
            int id = ((Integer) doomed.elementAt(i)).intValue();
            try { rs.deleteRecord(id); }
            catch (Throwable t)
            {
                Diag.warn(what + ": could not remove damaged record " + id);
            }
        }
        if (doomed.size() > 0)
        {
            Diag.info(what + ": removed " + doomed.size() + " unusable record(s)");
        }
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
