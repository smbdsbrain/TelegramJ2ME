package tg.plat;

import java.io.IOException;
import java.util.Vector;

import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;

import tg.api.DraftStore;
import tg.api.Peer;
import tg.api.RecordEnvelope;
import tg.diag.Diag;
import tg.mt.Dc;
import tg.tl.TlReader;
import tg.tl.TlWriter;

/**
 * What the user typed and did not send, per chat.
 *
 * Less critical than the outbox - a draft is text nobody has been promised -
 * and more visible than a cache, because losing one loses something the user
 * wrote. So the same rule applies with a lighter touch: after any interruption
 * a chat shows the old draft or the new one, never neither and never one
 * belonging to a different chat.
 *
 * <h3>Replacement is add-then-delete</h3>
 * It used to be the other way round: delete every matching row, then write.
 * Interrupted between the two - a battery on a handset that spends its life at
 * 5% - that is a draft the user typed and the client threw away. Now the new
 * row goes in first and {@link #load} resolves the pair by keeping the higher
 * record id, which is the newer write because RMS never reuses an id.
 *
 * <h3>Deleting a draft is verified</h3>
 * An empty draft means the text was sent or cleared, and a delete that quietly
 * did nothing brings it back the next time the chat is opened - on top of the
 * message that was already sent.
 */
public final class RmsDraftStore implements DraftStore
{
    private static final String STORE = "tgdrafts";

    /** TGD3 - the envelope-wrapped generation. */
    private static final int MAGIC = 0x54474433;
    private static final int VERSION = 1;

    /** The pre-envelope format, still read so an upgrade keeps its drafts. */
    private static final int LEGACY_MAGIC = 0x54474432; // TGD2

    /**
     * Whose drafts these are; 0 until the client knows.
     *
     * A draft carries a contact's words, so a second account must not read the
     * first one's. Zero on either side matches, which keeps a draft typed
     * before the account id is known from vanishing once it is.
     */
    private long accountId;

    public synchronized void bindAccount(long accountId)
    {
        this.accountId = accountId;
    }

    public synchronized String load(Peer peer) throws IOException
    {
        RecordStore store = null;
        try
        {
            store = RecordStore.openRecordStore(STORE, true);
            Row best = find(store, peer, null);
            return best == null ? "" : best.text;
        }
        catch (Throwable t) { throw io("RMS draft load", t); }
        finally { close(store); }
    }

    public synchronized void save(Peer peer, String text) throws IOException
    {
        RecordStore store = null;
        try
        {
            store = RecordStore.openRecordStore(STORE, true);
            Vector mine = new Vector();
            find(store, peer, mine);

            int fresh = 0;
            if (text != null && text.length() > 0)
            {
                byte[] raw = RecordEnvelope.wrap(MAGIC, VERSION, accountId,
                        Dc.isTest(), payload(peer, text));
                fresh = store.addRecord(raw, 0, raw.length);
                if (!identical(store, fresh, raw))
                {
                    try { store.deleteRecord(fresh); } catch (Throwable ignored) { }
                    throw new IOException("draft did not read back after save");
                }
            }

            // Only after the replacement is committed, or there was nothing to
            // replace it with. Interrupted before this point the chat still has
            // its previous draft; interrupted after it, the new one.
            for (int i = 0; i < mine.size(); i++)
            {
                int id = ((Row) mine.elementAt(i)).id;
                if (id == fresh) { continue; }
                try { store.deleteRecord(id); }
                catch (javax.microedition.rms.InvalidRecordIDException gone) { }
                catch (Throwable t)
                {
                    if (fresh == 0)
                    {
                        // Clearing a draft, and the old row is still there. The
                        // caller has to hear about it: silently keeping it
                        // brings the text back on top of the message that was
                        // just sent.
                        throw new IOException("draft row " + id
                                + " could not be removed");
                    }
                    Diag.warn("draft left a duplicate row " + id);
                }
            }

            if (fresh == 0)
            {
                // Verified, not assumed. A delete that reported success and did
                // nothing is the failure this catches.
                Row left = find(store, peer, null);
                if (left != null)
                {
                    throw new IOException("draft is still stored after clearing");
                }
            }
        }
        catch (IOException e) { throw e; }
        catch (Throwable t) { throw io("RMS draft save", t); }
        finally { close(store); }
    }

    public synchronized void clear() throws IOException
    {
        try { RecordStore.deleteRecordStore(STORE); }
        catch (javax.microedition.rms.RecordStoreNotFoundException ignored) { }
        catch (Throwable t) { throw io("RMS drafts clear", t); }
    }

    // ------------------------------------------------------------ internals

    /** One stored draft row. */
    private static final class Row
    {
        final int id;
        final String text;

        Row(int id, String text) { this.id = id; this.text = text; }
    }

    /**
     * The newest row for {@code peer}, collecting every row for it on the way.
     *
     * Newest is the highest record id: ids are never reused, so the higher one
     * is the later write whatever order the handset enumerated in. Damaged rows
     * are removed as they are found - a draft is recoverable by retyping, and
     * one that cannot be read is only taking up space.
     *
     * @param mine when non-null, every row belonging to this peer is added
     */
    private Row find(RecordStore store, Peer peer, Vector mine) throws Exception
    {
        Row best = null;
        Vector damaged = new Vector();
        RecordEnumeration records = null;
        try
        {
            records = store.enumerateRecords(null, null, false);
            while (records.hasNextElement())
            {
                int id = records.nextRecordId();
                byte[] raw;
                try { raw = store.getRecord(id); }
                catch (Throwable t) { continue; }

                RecordEnvelope envelope = RecordEnvelope.unwrap(raw, MAGIC,
                        VERSION, VERSION, accountId, Dc.isTest());

                String text = null;
                if (envelope.isOk())
                {
                    text = textFor(envelope.payload, peer, false);
                }
                else if (envelope.outcome == RecordEnvelope.DAMAGED)
                {
                    damaged.addElement(new Integer(id));
                    continue;
                }
                else
                {
                    // A pre-envelope row. Read for this peer, and left in place
                    // rather than rewritten: it is replaced the next time the
                    // chat's draft is saved, and rewriting it here would mean
                    // writing during a load.
                    text = textFor(raw, peer, true);
                }

                if (text == null) { continue; }
                Row row = new Row(id, text);
                if (mine != null) { mine.addElement(row); }
                if (best == null || row.id > best.id) { best = row; }
            }
        }
        finally
        {
            if (records != null) { try { records.destroy(); } catch (Throwable ignored) { } }
        }

        for (int i = 0; i < damaged.size(); i++)
        {
            int id = ((Integer) damaged.elementAt(i)).intValue();
            try
            {
                store.deleteRecord(id);
                Diag.warn("removed damaged draft row " + id);
            }
            catch (Throwable ignored) { }
        }
        return best;
    }

    /** The draft text in {@code raw} if it is this peer's, else null. */
    private static String textFor(byte[] raw, Peer peer, boolean legacy)
    {
        try
        {
            TlReader reader = new TlReader(raw);
            if (reader.readInt() != (legacy ? LEGACY_MAGIC : MAGIC)) { return null; }
            if (reader.readInt() != peer.kind) { return null; }
            if (reader.readLong() != peer.id) { return null; }
            return reader.readString();
        }
        catch (Throwable notOurs) { return null; }
    }

    private static byte[] payload(Peer peer, String text)
    {
        TlWriter writer = new TlWriter(text.length() * 3 + 24);
        writer.writeInt(MAGIC);
        writer.writeInt(peer.kind);
        writer.writeLong(peer.id);
        writer.writeString(text);
        return writer.toByteArray();
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
