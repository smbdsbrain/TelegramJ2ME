package tgtest;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

import javax.microedition.rms.InvalidRecordIDException;
import javax.microedition.rms.RecordComparator;
import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordFilter;
import javax.microedition.rms.RecordListener;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;
import javax.microedition.rms.RecordStoreNotFoundException;
import javax.microedition.rms.RecordStoreNotOpenException;

import org.microemu.MicroEmulator;
import org.microemu.RecordStoreManager;
import org.microemu.util.ExtendedRecordListener;
import org.microemu.util.RecordStoreImpl;

/**
 * An RMS that can be made to fail exactly where a handset would, on demand.
 *
 * <h3>What this is for</h3>
 * A store that survives a power cut has to be written in an order where every
 * interruption leaves either the old value or the new one, never half of
 * either. Nothing about that is observable from a passing test: the happy path
 * of a correct implementation and of a broken one are identical, and the
 * difference only appears on a phone that lost battery between two RMS calls.
 *
 * So the failure has to be manufactured. {@link EmulatorRecords#installUnreadable}
 * already did the blunt version - everything throws at {@code openRecordStore} -
 * which is enough to tell "unreadable" from "empty" and nothing else. What a
 * crash-consistency claim needs is narrower: fail the <em>second</em>
 * {@code addRecord} on {@code tgkeys}, and fail it <em>after</em> the record
 * landed, because "the write succeeded and the call reported failure" is a real
 * outcome and the one implementations get wrong.
 *
 * <h3>Where it sits</h3>
 * At the RMS primitives, not above them. A double that stubs {@code save} and
 * {@code load} can only prove that the code calls itself consistently; it
 * cannot tell an add-then-delete from a delete-then-add, which is the entire
 * question. This is a complete in-memory {@code RecordStore} model behind
 * MicroEmulator's {@code RecordStoreManager} - the same seam
 * {@code EmulatorRecords} uses, and the reason none of this reaches the JAR.
 *
 * <h3>Restart</h3>
 * {@link #restart} drops every open handle and keeps the records, which is what
 * a process death is from RMS's point of view. A test then builds a fresh
 * production store over the same data and asks what it can see.
 *
 * <h3>What it does not claim</h3>
 * Not an emulation of any particular vendor's RMS. Real implementations may be
 * more atomic than this - a real {@code setRecord} may well be all-or-nothing -
 * and they are certainly buggier in ways nobody has written down. The model is
 * deliberately conservative: it can tear where the specification permits
 * tearing, so a store that survives it survives a handset that is no worse.
 */
public final class FaultyRecords implements RecordStoreManager
{
    // ------------------------------------------------------ primitive names

    public static final String OPEN = "openRecordStore";
    public static final String CLOSE = "closeRecordStore";
    public static final String ADD = "addRecord";
    public static final String SET = "setRecord";
    public static final String GET = "getRecord";
    public static final String DELETE = "deleteRecord";
    public static final String DELETE_STORE = "deleteRecordStore";
    public static final String ENUMERATE = "enumerateRecords";
    public static final String NUM_RECORDS = "getNumRecords";
    public static final String RECORD_SIZE = "getRecordSize";

    /** Any store name, for {@link #failAt}. */
    public static final String ANY_STORE = "*";

    // --------------------------------------------------- enumeration orders

    /** Lowest record id first. What most handsets appear to do. */
    public static final int ASCENDING = 0;

    /** Highest first. Legal, and enough to break "the first one wins". */
    public static final int DESCENDING = 1;

    /**
     * Insertion order, which after deletes is neither of the above.
     *
     * The specification promises no order at all, so code that resolves
     * duplicates by "whichever came out first" is wrong on some handset
     * somewhere. Varying this is how that shows up here instead.
     */
    public static final int INSERTION = 2;

    private final Hashtable stores = new Hashtable();      // name -> StoreData
    private final Hashtable openHandles = new Hashtable(); // name -> Handle
    private final Vector faults = new Vector();
    private final Vector log = new Vector();

    private int order = ASCENDING;
    private boolean logging;

    // ------------------------------------------------------------ the plan

    /**
     * Fail the {@code nth} call of {@code op} on {@code store}.
     *
     * @param store  a store name, or {@link #ANY_STORE}
     * @param op     one of the primitive constants
     * @param nth    1 for the first matching call, 2 for the second, and so on
     * @param after  true to apply the change and <em>then</em> throw - the
     *               outcome a caller cannot distinguish from a clean failure,
     *               and the one that decides whether an implementation is
     *               crash-safe
     */
    public void failAt(String store, String op, int nth, boolean after)
    {
        faults.addElement(new Fault(store, op, nth, after, 1));
    }

    /** Fail every call of {@code op} on {@code store}, before it takes effect. */
    public void failEvery(String store, String op)
    {
        faults.addElement(new Fault(store, op, 1, false, Integer.MAX_VALUE));
    }

    /** Drop every scheduled failure. The data is untouched. */
    public void clearFaults()
    {
        faults.removeAllElements();
    }

    /** Which order {@code enumerateRecords} hands records back in. */
    public void enumerationOrder(int order)
    {
        this.order = order;
    }

    // ------------------------------------------------------- restart / data

    /**
     * Everything a process death does to RMS: the handles go, the data stays.
     *
     * Deliberately not a close: a store that was open when the battery came out
     * was not closed, and an implementation that only writes on
     * {@code closeRecordStore} has to fail here.
     */
    public void restart()
    {
        openHandles.clear();
        log.removeAllElements();
    }

    /** Record ids present in {@code store}, ascending. Empty when it is absent. */
    public int[] recordIds(String store)
    {
        StoreData data = (StoreData) stores.get(store);
        if (data == null) { return new int[0]; }
        return data.ids();
    }

    /** The bytes of one record, or null. Reads around any scheduled failure. */
    public byte[] peek(String store, int id)
    {
        StoreData data = (StoreData) stores.get(store);
        if (data == null) { return null; }
        byte[] value = (byte[]) data.records.get(new Integer(id));
        return value == null ? null : copy(value);
    }

    /** Write a record's bytes directly, around the store's own code. */
    public void poke(String store, int id, byte[] value)
    {
        StoreData data = data(store, true);
        Integer key = new Integer(id);
        if (data.records.get(key) == null) { data.insertion.addElement(key); }
        data.records.put(key, copy(value));
        if (id >= data.nextId) { data.nextId = id + 1; }
    }

    /** Whether the store exists at all, as distinct from existing and empty. */
    public boolean exists(String store)
    {
        return stores.get(store) != null;
    }

    /** Create an empty store without opening it, the way a first run leaves one. */
    public void createEmpty(String store)
    {
        data(store, true);
    }

    /** Forget a store completely, as a wipe would. */
    public void drop(String store)
    {
        stores.remove(store);
        openHandles.remove(store);
    }

    // --------------------------------------------------------- corruption

    /** Keep only the first {@code keepBytes} of a record. A torn write. */
    public void truncate(String store, int id, int keepBytes)
    {
        byte[] value = peek(store, id);
        if (value == null) { throw new IllegalStateException("no record " + id); }
        int keep = keepBytes < 0 ? 0 : (keepBytes > value.length ? value.length : keepBytes);
        byte[] cut = new byte[keep];
        System.arraycopy(value, 0, cut, 0, keep);
        poke(store, id, cut);
    }

    /** Flip one bit. Flash wear, and the one corruption a length check misses. */
    public void flipBit(String store, int id, int byteIndex, int bit)
    {
        byte[] value = peek(store, id);
        if (value == null) { throw new IllegalStateException("no record " + id); }
        if (byteIndex < 0 || byteIndex >= value.length)
        {
            throw new IllegalStateException("record " + id + " has no byte "
                    + byteIndex);
        }
        value[byteIndex] = (byte) (value[byteIndex] ^ (1 << (bit & 7)));
        poke(store, id, value);
    }

    /**
     * Add a second record with the same content under a new id.
     *
     * What an interrupted add-then-delete leaves behind, and what any store
     * with a "one record per key" assumption has to resolve deterministically.
     */
    public int duplicate(String store, int id)
    {
        byte[] value = peek(store, id);
        if (value == null) { throw new IllegalStateException("no record " + id); }
        StoreData data = data(store, true);
        int fresh = data.nextId++;
        poke(store, fresh, value);
        return fresh;
    }

    /** Replace a record with bytes that are not a record of anything. */
    public void garble(String store, int id, int length)
    {
        byte[] junk = new byte[length];
        for (int i = 0; i < length; i++) { junk[i] = (byte) (0xA5 ^ i); }
        poke(store, id, junk);
    }

    // ------------------------------------------------------------ the log

    /** Record every primitive call, for a test that asserts on the order. */
    public void startLogging()
    {
        logging = true;
        log.removeAllElements();
    }

    /** The calls seen since {@link #startLogging}, as "store.op" strings. */
    public String[] calls()
    {
        String[] out = new String[log.size()];
        log.copyInto(out);
        return out;
    }

    // ------------------------------------------------- RecordStoreManager

    public String getName() { return "faulty"; }

    public RecordStore openRecordStore(String name, boolean create)
            throws RecordStoreException
    {
        note(name, OPEN);
        check(name, OPEN, false);
        StoreData data = (StoreData) stores.get(name);
        if (data == null)
        {
            if (!create)
            {
                throw new RecordStoreNotFoundException(name);
            }
            data = data(name, true);
        }
        check(name, OPEN, true);
        Handle handle = new Handle(name, data);
        openHandles.put(name, handle);
        return handle;
    }

    public void deleteRecordStore(String name) throws RecordStoreException
    {
        note(name, DELETE_STORE);
        check(name, DELETE_STORE, false);
        if (stores.get(name) == null)
        {
            throw new RecordStoreNotFoundException(name);
        }
        stores.remove(name);
        openHandles.remove(name);
        check(name, DELETE_STORE, true);
    }

    public String[] listRecordStores()
    {
        String[] names = new String[stores.size()];
        int i = 0;
        for (Enumeration e = stores.keys(); e.hasMoreElements(); )
        {
            names[i++] = (String) e.nextElement();
        }
        return names;
    }

    public void saveChanges(RecordStoreImpl store) { }
    public int getSizeAvailable(RecordStoreImpl store) { return 1 << 20; }
    public void init(MicroEmulator emulator) { }
    public void deleteStores() { stores.clear(); openHandles.clear(); }
    public void setRecordListener(ExtendedRecordListener listener) { }
    public void fireRecordStoreListener(int type, String name) { }

    // ------------------------------------------------------------ internals

    private StoreData data(String name, boolean create)
    {
        StoreData data = (StoreData) stores.get(name);
        if (data == null && create)
        {
            data = new StoreData();
            stores.put(name, data);
        }
        return data;
    }

    private void note(String store, String op)
    {
        if (logging) { log.addElement(store + "." + op); }
    }

    /**
     * Throw if a fault is due here.
     *
     * @param after which side of the change this call is - a fault scheduled
     *              {@code after} only fires on the second visit, once the
     *              mutation has been applied
     */
    private void check(String store, String op, boolean after)
            throws RecordStoreException
    {
        for (int i = 0; i < faults.size(); i++)
        {
            Fault fault = (Fault) faults.elementAt(i);
            if (fault.matches(store, op, after))
            {
                throw new RecordStoreException("injected failure: " + store
                        + "." + op + (after ? " (after the change)" : ""));
            }
        }
    }

    private static byte[] copy(byte[] value)
    {
        byte[] out = new byte[value.length];
        System.arraycopy(value, 0, out, 0, value.length);
        return out;
    }

    /** One scheduled failure. */
    private static final class Fault
    {
        private final String store;
        private final String op;
        private final int nth;
        private final boolean after;
        private final int times;

        private int seen;
        private int fired;

        Fault(String store, String op, int nth, boolean after, int times)
        {
            this.store = store;
            this.op = op;
            this.nth = nth;
            this.after = after;
            this.times = times;
        }

        boolean matches(String name, String call, boolean side)
        {
            if (!op.equals(call)) { return false; }
            if (!ANY_STORE.equals(store) && !store.equals(name)) { return false; }
            // A "before" fault is decided on the way in; an "after" fault has
            // to let the call through first, so only its second visit counts.
            if (side != after) { return false; }
            seen++;
            if (seen < nth || fired >= times) { return false; }
            fired++;
            return true;
        }
    }

    /** One store's contents. Survives {@link FaultyRecords#restart}. */
    private static final class StoreData
    {
        final Hashtable records = new Hashtable();   // Integer -> byte[]
        final Vector insertion = new Vector();       // Integer, in order added
        int nextId = 1;
        int version;

        int[] ids()
        {
            int[] out = new int[records.size()];
            int i = 0;
            for (Enumeration e = records.keys(); e.hasMoreElements(); )
            {
                out[i++] = ((Integer) e.nextElement()).intValue();
            }
            sort(out);
            return out;
        }

        int size()
        {
            int total = 0;
            for (Enumeration e = records.elements(); e.hasMoreElements(); )
            {
                total += ((byte[]) e.nextElement()).length;
            }
            return total;
        }

        static void sort(int[] values)
        {
            for (int i = 1; i < values.length; i++)
            {
                int v = values[i];
                int j = i - 1;
                while (j >= 0 && values[j] > v) { values[j + 1] = values[j]; j--; }
                values[j + 1] = v;
            }
        }
    }

    /**
     * One open handle on a store.
     *
     * Extends {@code RecordStore} rather than MicroEmulator's
     * {@code RecordStoreImpl}: every method here is the model's own, so there
     * is no inherited behaviour to reason about and record ids, enumeration
     * order and the exact point a failure lands are all decided in this file.
     */
    private final class Handle extends RecordStore
    {
        private final String name;
        private final StoreData data;
        private boolean open = true;

        Handle(String name, StoreData data)
        {
            this.name = name;
            this.data = data;
        }

        private void mustBeOpen() throws RecordStoreNotOpenException
        {
            if (!open) { throw new RecordStoreNotOpenException(name); }
        }

        private byte[] record(int id) throws RecordStoreException
        {
            byte[] value = (byte[]) data.records.get(new Integer(id));
            if (value == null) { throw new InvalidRecordIDException("no record " + id); }
            return value;
        }

        public void closeRecordStore() throws RecordStoreException
        {
            note(name, CLOSE);
            check(name, CLOSE, false);
            mustBeOpen();
            open = false;
            openHandles.remove(name);
            check(name, CLOSE, true);
        }

        public String getName() throws RecordStoreNotOpenException
        {
            mustBeOpen();
            return name;
        }

        public int getVersion() throws RecordStoreNotOpenException
        {
            mustBeOpen();
            return data.version;
        }

        public int getNumRecords() throws RecordStoreNotOpenException
        {
            note(name, NUM_RECORDS);
            mustBeOpen();
            try { check(name, NUM_RECORDS, false); }
            catch (RecordStoreException e)
            {
                // The signature does not allow it; the store still has to cope
                // with a count it cannot get, so report it as not open.
                throw new RecordStoreNotOpenException(e.getMessage());
            }
            return data.records.size();
        }

        public int getSize() throws RecordStoreNotOpenException
        {
            mustBeOpen();
            return data.size();
        }

        public int getSizeAvailable() throws RecordStoreNotOpenException
        {
            mustBeOpen();
            return 1 << 20;
        }

        public long getLastModified() throws RecordStoreNotOpenException
        {
            mustBeOpen();
            return 0;
        }

        public void addRecordListener(RecordListener listener) { }
        public void removeRecordListener(RecordListener listener) { }

        public int getNextRecordID() throws RecordStoreException
        {
            mustBeOpen();
            return data.nextId;
        }

        public int addRecord(byte[] value, int offset, int length)
                throws RecordStoreException
        {
            note(name, ADD);
            check(name, ADD, false);
            mustBeOpen();
            byte[] stored = new byte[length];
            if (value != null) { System.arraycopy(value, offset, stored, 0, length); }
            // Ids are never reused, which is what lets a store resolve
            // duplicates by "highest id wins" after an interrupted write.
            int id = data.nextId++;
            Integer key = new Integer(id);
            data.records.put(key, stored);
            data.insertion.addElement(key);
            data.version++;
            check(name, ADD, true);
            return id;
        }

        public void deleteRecord(int id) throws RecordStoreException
        {
            note(name, DELETE);
            check(name, DELETE, false);
            mustBeOpen();
            record(id);
            data.records.remove(new Integer(id));
            data.insertion.removeElement(new Integer(id));
            data.version++;
            check(name, DELETE, true);
        }

        public int getRecordSize(int id) throws RecordStoreException
        {
            note(name, RECORD_SIZE);
            check(name, RECORD_SIZE, false);
            mustBeOpen();
            return record(id).length;
        }

        public int getRecord(int id, byte[] buffer, int offset)
                throws RecordStoreException
        {
            byte[] value = getRecord(id);
            System.arraycopy(value, 0, buffer, offset, value.length);
            return value.length;
        }

        public byte[] getRecord(int id) throws RecordStoreException
        {
            note(name, GET);
            check(name, GET, false);
            mustBeOpen();
            return copy(record(id));
        }

        public void setMode(int authmode, boolean writable) { }

        public void setRecord(int id, byte[] value, int offset, int length)
                throws RecordStoreException
        {
            note(name, SET);
            check(name, SET, false);
            mustBeOpen();
            record(id);
            byte[] stored = new byte[length];
            if (value != null) { System.arraycopy(value, offset, stored, 0, length); }
            data.records.put(new Integer(id), stored);
            data.version++;
            check(name, SET, true);
        }

        public RecordEnumeration enumerateRecords(RecordFilter filter,
                                                  RecordComparator comparator,
                                                  boolean keepUpdated)
                throws RecordStoreNotOpenException
        {
            note(name, ENUMERATE);
            mustBeOpen();
            try { check(name, ENUMERATE, false); }
            catch (RecordStoreException e)
            {
                throw new RecordStoreNotOpenException(e.getMessage());
            }

            Vector selected = new Vector();
            if (order == INSERTION)
            {
                for (int i = 0; i < data.insertion.size(); i++)
                {
                    selected.addElement(data.insertion.elementAt(i));
                }
            }
            else
            {
                int[] ids = data.ids();
                for (int i = 0; i < ids.length; i++)
                {
                    int id = order == DESCENDING ? ids[ids.length - 1 - i] : ids[i];
                    selected.addElement(new Integer(id));
                }
            }

            if (filter != null)
            {
                Vector kept = new Vector();
                for (int i = 0; i < selected.size(); i++)
                {
                    Integer id = (Integer) selected.elementAt(i);
                    byte[] value = (byte[]) data.records.get(id);
                    if (value != null && filter.matches(value)) { kept.addElement(id); }
                }
                selected = kept;
            }
            return new Enumerator(this, selected);
        }
    }

    /** The three methods the client ever uses, and honest refusals elsewhere. */
    private static final class Enumerator implements RecordEnumeration
    {
        private final RecordStore store;
        private final Vector ids;
        private int at;

        Enumerator(RecordStore store, Vector ids)
        {
            this.store = store;
            this.ids = ids;
        }

        public int numRecords() { return ids.size(); }

        public boolean hasNextElement() { return at < ids.size(); }

        public boolean hasPreviousElement() { return at > 0; }

        public int nextRecordId() throws InvalidRecordIDException
        {
            if (at >= ids.size()) { throw new InvalidRecordIDException("past the end"); }
            return ((Integer) ids.elementAt(at++)).intValue();
        }

        public byte[] nextRecord() throws RecordStoreException
        {
            return store.getRecord(nextRecordId());
        }

        public int previousRecordId() throws InvalidRecordIDException
        {
            if (at <= 0) { throw new InvalidRecordIDException("before the start"); }
            return ((Integer) ids.elementAt(--at)).intValue();
        }

        public byte[] previousRecord() throws RecordStoreException
        {
            return store.getRecord(previousRecordId());
        }

        public void reset() { at = 0; }
        public void rebuild() { }
        public void keepUpdated(boolean keep) { }
        public boolean isKeptUpdated() { return false; }
        public void destroy() { }
    }
}
