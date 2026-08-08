package tg.plat;

import java.io.IOException;
import java.util.Vector;

import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;

import tg.api.OutgoingMessage;
import tg.api.OutgoingStore;
import tg.api.Peer;
import tg.api.RecordEnvelope;
import tg.diag.Diag;
import tg.mt.Dc;

/**
 * The queue of messages the user has written and the server has not confirmed.
 *
 * The most valuable thing in RMS after the auth key: everything else here is a
 * cache of something Telegram still has, and this is the one store whose
 * contents exist nowhere else. A row lost is a message the user believes they
 * sent.
 *
 * <h3>What every mutation guarantees</h3>
 * It happened, or it reported failure. Never "reported success" for a write
 * that a restart would not show - which is what {@code setRecord} followed by
 * no read gives you on hardware that acknowledges a write into a buffer.
 * {@link #save} reads back and compares; {@link #remove} confirms the record is
 * gone; {@link #add} confirms the record is there. All three throw when the
 * store disagrees, and the caller is expected to tell the user.
 *
 * <h3>Replacement is add-then-delete</h3>
 * A state change writes a new record and only then removes the old one, so an
 * interruption leaves both rather than neither. {@link #list} resolves the pair
 * by {@code random_id}, keeping the higher record id - and record ids are never
 * reused, which is what makes that deterministic rather than a guess about
 * enumeration order.
 *
 * <h3>random_id is not ours to change</h3>
 * It is Telegram's deduplication key: the same value resent is the same
 * message, a new value is a second copy in the conversation. Nothing in
 * recovery, migration or retry regenerates one.
 *
 * <h3>SENDING does not survive a restart</h3>
 * It means "a request is on the wire", and after a restart no request is. A row
 * left SENDING is returned as QUEUED - same random_id, so if the original did
 * reach Telegram the retry is deduplicated rather than delivered twice.
 *
 * <h3>Damaged rows</h3>
 * Removed, not skipped. Skipping them left them consuming one of the sixty-four
 * slots for the life of the installation, and a store that is full of records
 * it cannot read reports itself full.
 */
public final class RmsOutgoingStore implements OutgoingStore
{
    private static final String STORE = "tgoutbox";
    private static final int MAX_ITEMS = 64;

    /** TGO3 - the envelope-wrapped generation. */
    private static final int MAGIC = 0x54474f33;
    private static final int VERSION = 1;

    /**
     * Who the rows belong to; 0 until the client knows.
     *
     * Written into every new record and checked on read. Zero on either side
     * matches anything, so a message queued before sign-in completes is not
     * lost the moment it does - see {@link RecordEnvelope}.
     */
    private long accountId;

    /** Damaged rows removed since launch. Diagnostics only. */
    private int damagedRemoved;

    /** Rows this build could not read and deliberately left alone. */
    private int unreadable;

    public synchronized void bindAccount(long accountId)
    {
        this.accountId = accountId;
    }

    public synchronized int damagedRemoved() { return damagedRemoved; }

    public synchronized int unreadableRows() { return unreadable; }

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

            // Counted over readable rows, after the damaged ones have gone.
            // getNumRecords() includes rubbish, and a store full of records
            // nobody can decode used to refuse every new message for ever.
            int live = sweep(store).size();
            if (live >= MAX_ITEMS)
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

            byte[] raw = envelope(message);
            int id = store.addRecord(raw, 0, raw.length);

            // Read back before telling the caller it is queued. On a handset
            // this is the difference between "your message is waiting" and a
            // message that was never written at all.
            if (!identical(store, id, raw))
            {
                try { store.deleteRecord(id); } catch (Throwable ignored) { }
                throw new IOException("outbox row did not read back after add");
            }
            message.localId = id;
            return message;
        }
        catch (IOException e) { throw e; }
        catch (Throwable t) { throw io("RMS outbox add", t); }
        finally { close(store); }
    }

    public synchronized OutgoingMessage[] list() throws IOException
    {
        RecordStore store = null;
        Vector values;
        try
        {
            store = RecordStore.openRecordStore(STORE, true);
            values = sweep(store);
        }
        catch (Throwable t) { throw io("RMS outbox list", t); }
        finally { close(store); }

        OutgoingMessage[] out = new OutgoingMessage[values.size()];
        values.copyInto(out);
        for (int i = 0; i < out.length; i++)
        {
            // A request that was on the wire when the power went is not on the
            // wire now. Same random_id, so a retry Telegram already saw is
            // deduplicated rather than delivered twice.
            if (out[i].state == OutgoingMessage.SENDING)
            {
                out[i].state = OutgoingMessage.QUEUED;
            }
        }
        sortByAge(out);
        return out;
    }

    public synchronized void save(OutgoingMessage message) throws IOException
    {
        RecordStore store = null;
        try
        {
            store = RecordStore.openRecordStore(STORE, true);
            byte[] raw = envelope(message);

            // Add the new row before removing the old one. Interrupted here,
            // the store holds two rows for one random_id and list() keeps the
            // newer; interrupted the other way round it would hold none.
            int fresh = store.addRecord(raw, 0, raw.length);
            if (!identical(store, fresh, raw))
            {
                try { store.deleteRecord(fresh); } catch (Throwable ignored) { }
                throw new IOException("outbox row did not read back after save");
            }

            int previous = message.localId;
            message.localId = fresh;
            if (previous != 0 && previous != fresh)
            {
                try { store.deleteRecord(previous); }
                catch (javax.microedition.rms.InvalidRecordIDException gone) { }
                catch (Throwable t)
                {
                    // The new row is committed and readable, so the message is
                    // safe; the old one becomes a duplicate that list() will
                    // resolve and the next save will try again to remove.
                    Diag.warn("outbox left a duplicate row " + previous + ": "
                            + t.getClass().getName());
                }
            }
        }
        catch (IOException e) { throw e; }
        catch (Throwable t) { throw io("RMS outbox save", t); }
        finally { close(store); }
    }

    public synchronized void remove(int localId) throws IOException
    {
        RecordStore store = null;
        try
        {
            store = RecordStore.openRecordStore(STORE, true);
            try { store.deleteRecord(localId); }
            catch (javax.microedition.rms.InvalidRecordIDException gone) { }

            // Confirmed, because a delete that silently did nothing means the
            // message is sent and still queued - and the next drain sends it
            // again under a random_id Telegram has already retired.
            byte[] still = null;
            try { still = store.getRecord(localId); }
            catch (Throwable expected) { }
            if (still != null)
            {
                throw new IOException("outbox row " + localId
                        + " is still there after remove");
            }
        }
        catch (IOException e) { throw e; }
        catch (Throwable t) { throw io("RMS outbox remove", t); }
        finally { close(store); }
    }

    public synchronized void clear() throws IOException
    {
        try { RecordStore.deleteRecordStore(STORE); }
        catch (javax.microedition.rms.RecordStoreNotFoundException ignored) { }
        catch (Throwable t) { throw io("RMS outbox clear", t); }
        damagedRemoved = 0;
        unreadable = 0;
    }

    // ------------------------------------------------------------ internals

    /**
     * Every readable row, with damaged ones removed and duplicates resolved.
     *
     * The one place that decides what the outbox contains, so add, list and the
     * capacity check cannot disagree about it.
     */
    private Vector sweep(RecordStore store) throws Exception
    {
        Vector values = new Vector();
        Vector damaged = new Vector();
        Vector legacy = new Vector();
        RecordEnumeration records = null;
        try
        {
            records = store.enumerateRecords(null, null, false);
            while (records.hasNextElement())
            {
                int id = records.nextRecordId();
                byte[] raw;
                try { raw = store.getRecord(id); }
                catch (Throwable t)
                {
                    Diag.warn("outbox row " + id + " unreadable: "
                            + t.getClass().getName());
                    continue;
                }

                RecordEnvelope envelope = RecordEnvelope.unwrap(raw, MAGIC,
                        VERSION, VERSION, accountId, Dc.isTest());
                if (envelope.isOk())
                {
                    try
                    {
                        values.addElement(OutgoingMessage.decode(id, envelope.payload));
                    }
                    catch (Throwable t)
                    {
                        Diag.warn("outbox row " + id + " failed to decode: "
                                + t.getClass().getName());
                        damaged.addElement(new Integer(id));
                    }
                    continue;
                }

                if (envelope.outcome == RecordEnvelope.DAMAGED)
                {
                    damaged.addElement(new Integer(id));
                    continue;
                }

                // Before this store had an envelope a row was a bare TGO2
                // record. Those are the user's unsent messages, so they are
                // migrated rather than left to expire: the alternative is a
                // build upgrade that silently drops the queue.
                OutgoingMessage old = legacyRow(id, raw);
                if (old != null)
                {
                    values.addElement(old);
                    legacy.addElement(old);
                    continue;
                }

                // Genuinely someone else's, or a schema this build does not
                // read. Left exactly where it is: the build that wrote it may
                // be the one that runs next.
                unreadable++;
                Diag.info("outbox row " + id + " left alone: "
                        + envelope.describe());
            }
        }
        finally
        {
            if (records != null) { try { records.destroy(); } catch (Throwable ignored) { } }
        }

        // Removed rather than skipped: a damaged row that stays consumes one of
        // sixty-four slots for the life of the installation.
        for (int i = 0; i < damaged.size(); i++)
        {
            int id = ((Integer) damaged.elementAt(i)).intValue();
            try
            {
                store.deleteRecord(id);
                damagedRemoved++;
                Diag.warn("removed damaged outbox row " + id);
            }
            catch (Throwable t)
            {
                Diag.warn("could not remove damaged outbox row " + id);
            }
        }

        migrate(store, legacy);
        return dedupe(values);
    }

    /** A pre-envelope row, or null when these bytes are not one. */
    private static OutgoingMessage legacyRow(int id, byte[] raw)
    {
        try { return OutgoingMessage.decode(id, raw); }
        catch (Throwable notOurs) { return null; }
    }

    /**
     * Rewrite legacy rows in the current format, add-then-delete.
     *
     * Failing here is not fatal and does not lose anything: the row is already
     * in the result and its old record is still on disk, so the next sweep
     * tries again. What must not happen is the random_id changing, and it does
     * not - the message object is re-encoded, not rebuilt.
     */
    private void migrate(RecordStore store, Vector legacy)
    {
        for (int i = 0; i < legacy.size(); i++)
        {
            OutgoingMessage message = (OutgoingMessage) legacy.elementAt(i);
            int previous = message.localId;
            try
            {
                byte[] raw = envelope(message);
                int fresh = store.addRecord(raw, 0, raw.length);
                if (!identical(store, fresh, raw))
                {
                    try { store.deleteRecord(fresh); } catch (Throwable ignored) { }
                    continue;
                }
                message.localId = fresh;
                store.deleteRecord(previous);
                Diag.info("migrated outbox row " + previous + " to " + fresh);
            }
            catch (Throwable t)
            {
                Diag.warn("could not migrate outbox row " + previous + ": "
                        + t.getClass().getName());
            }
        }
    }

    /**
     * One row per random_id, keeping the highest record id.
     *
     * What an interrupted add-then-delete leaves. Ids are never reused, so the
     * higher one is always the newer write - no clock, and no reliance on the
     * order the handset enumerated in.
     */
    private static Vector dedupe(Vector values)
    {
        Vector out = new Vector();
        for (int i = 0; i < values.size(); i++)
        {
            OutgoingMessage candidate = (OutgoingMessage) values.elementAt(i);
            boolean replaced = false;
            for (int j = 0; j < out.size(); j++)
            {
                OutgoingMessage kept = (OutgoingMessage) out.elementAt(j);
                if (kept.randomId != candidate.randomId) { continue; }
                if (candidate.localId > kept.localId)
                {
                    out.setElementAt(candidate, j);
                }
                replaced = true;
                break;
            }
            if (!replaced) { out.addElement(candidate); }
        }
        return out;
    }

    private byte[] envelope(OutgoingMessage message) throws IOException
    {
        return RecordEnvelope.wrap(MAGIC, VERSION, accountId, Dc.isTest(),
                OutgoingMessage.encode(message));
    }

    private static boolean identical(RecordStore store, int id, byte[] expected)
    {
        byte[] actual;
        try { actual = store.getRecord(id); }
        catch (Throwable t) { return false; }
        if (actual == null || actual.length != expected.length) { return false; }
        for (int i = 0; i < expected.length; i++)
        {
            if (actual[i] != expected[i]) { return false; }
        }
        return true;
    }

    private static void sortByAge(OutgoingMessage[] out)
    {
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
