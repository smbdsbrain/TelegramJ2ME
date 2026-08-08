package tgtest;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;
import javax.microedition.rms.RecordStoreNotFoundException;

import tg.mt.AuthKey;
import tg.mt.AuthKeyLoad;
import tg.plat.RmsAuthKeyStore;

/**
 * The harness, and the first store put through it.
 *
 * A store that survives a power cut has to be written in an order where every
 * interruption leaves the old value or the new one, never half of either.
 * Nothing about that is visible from a passing test: a correct implementation
 * and a broken one have the same happy path, and they only diverge on a phone
 * that lost battery between two RMS calls. Waiting for that to happen is not a
 * test strategy, so {@link FaultyRecords} manufactures it - at the primitives,
 * because the question is which primitive ran and in what order, and a double
 * that stubs {@code save} and {@code load} cannot see any of it.
 *
 * Two halves below. The first proves the harness does what it says, including
 * the part that is easy to get wrong: a failure scheduled <em>after</em> the
 * change has to leave the change behind, because "the write landed and the call
 * reported failure" is a real outcome and the one implementations mishandle.
 * The second runs {@code RmsAuthKeyStore} through it and states the guarantee:
 * after a restart the store shows the old key or the new one, never neither.
 *
 * The last case substitutes a deliberately unsafe delete-then-add and shows the
 * assertion failing against it - a harness that cannot fail is not evidence.
 */
public final class RmsFaultTest implements Test
{
    private static final String STORE = "harness";
    private static final String KEYS = "tgkeys";

    /** Not 1 or 2: a live emulator profile on the same machine has those. */
    private static final int DC = 98;

    public String name() { return "storage/rms-faults"; }

    public void run() throws Exception
    {
        // The harness half.
        theModelBehavesLikeARecordStore();
        recordIdsAreNeverReused();
        aFailureBeforeTheChangeLeavesNothing();
        aFailureAfterTheChangeLeavesTheChange();
        theNthCallIsTheOneThatFails();
        failEveryKeepsFailing();
        restartKeepsTheDataAndDropsTheHandles();
        enumerationOrderIsNotToBeReliedOn();
        corruptionHelpersAreReproducible();

        // The store half.
        theAuthKeyStoreSurvivesARestart();
        aRefusedWriteKeepsTheOldKey();
        aWriteThatLandedButReportedFailureIsStillReadable();
        anInterruptedCleanupLeavesADuplicateAndTheNewestWins();
        anUnreadableStoreIsNotAnEmptyOne();
        deleteThenAddLosesBoth();
    }

    // ================================================================ harness

    private static void theModelBehavesLikeARecordStore() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            try
            {
                RecordStore.openRecordStore(STORE, false);
                Assert.isTrue("opening a store that does not exist must throw",
                        false);
            }
            catch (RecordStoreNotFoundException expected) { }

            RecordStore store = RecordStore.openRecordStore(STORE, true);
            Assert.equal("a fresh store is empty", 0, store.getNumRecords());

            int first = store.addRecord(bytes("alpha"), 0, 5);
            int second = store.addRecord(bytes("beta"), 0, 4);
            Assert.equal("two records", 2, store.getNumRecords());
            Assert.isTrue("ids ascend", second > first);
            Assert.equal("what went in comes out", "alpha",
                    text(store.getRecord(first)));
            Assert.equal("record size is the record's", 4,
                    store.getRecordSize(second));

            store.setRecord(first, bytes("gamma"), 0, 5);
            Assert.equal("set replaces", "gamma", text(store.getRecord(first)));

            store.deleteRecord(second);
            Assert.equal("delete removes", 1, store.getNumRecords());
            store.closeRecordStore();

            // The model's own view, around the store's code.
            Assert.equal("one record left in the model", 1,
                    rms.recordIds(STORE).length);
            Assert.equal("and it is the one that was set", "gamma",
                    text(rms.peek(STORE, first)));
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * Ids are never reused, which is the whole reason "highest id wins" can
     * resolve an interrupted write. A model that recycled them would make a
     * broken store look correct.
     */
    private static void recordIdsAreNeverReused() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RecordStore store = RecordStore.openRecordStore(STORE, true);
            int first = store.addRecord(bytes("one"), 0, 3);
            store.deleteRecord(first);
            int second = store.addRecord(bytes("two"), 0, 3);
            Assert.isTrue("a deleted id is not handed out again: " + first
                    + " then " + second, second > first);
            store.closeRecordStore();
        }
        finally { EmulatorRecords.restore(); }
    }

    private static void aFailureBeforeTheChangeLeavesNothing() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RecordStore store = RecordStore.openRecordStore(STORE, true);
            rms.failAt(STORE, FaultyRecords.ADD, 1, false);
            try
            {
                store.addRecord(bytes("never"), 0, 5);
                Assert.isTrue("the add must have thrown", false);
            }
            catch (RecordStoreException expected) { }
            Assert.equal("and nothing was written", 0,
                    rms.recordIds(STORE).length);
            store.closeRecordStore();
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * The interesting one. The caller sees a failure and the record is there
     * anyway - which is what a handset that wrote the block and then lost power
     * before acknowledging looks like on the next boot.
     */
    private static void aFailureAfterTheChangeLeavesTheChange() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RecordStore store = RecordStore.openRecordStore(STORE, true);
            rms.failAt(STORE, FaultyRecords.ADD, 1, true);
            try
            {
                store.addRecord(bytes("landed"), 0, 6);
                Assert.isTrue("the add must have thrown", false);
            }
            catch (RecordStoreException expected) { }

            int[] ids = rms.recordIds(STORE);
            Assert.equal("the record is there despite the failure", 1, ids.length);
            Assert.equal("with its content", "landed", text(rms.peek(STORE, ids[0])));
            store.closeRecordStore();
        }
        finally { EmulatorRecords.restore(); }
    }

    private static void theNthCallIsTheOneThatFails() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RecordStore store = RecordStore.openRecordStore(STORE, true);
            rms.failAt(STORE, FaultyRecords.ADD, 3, false);

            store.addRecord(bytes("a"), 0, 1);
            store.addRecord(bytes("b"), 0, 1);
            try
            {
                store.addRecord(bytes("c"), 0, 1);
                Assert.isTrue("the third add must have thrown", false);
            }
            catch (RecordStoreException expected) { }

            // Once only: a fault that keeps firing would make every later
            // assertion about recovery meaningless.
            store.addRecord(bytes("d"), 0, 1);
            Assert.equal("two before the failure, one after", 3,
                    rms.recordIds(STORE).length);
            store.closeRecordStore();
        }
        finally { EmulatorRecords.restore(); }
    }

    private static void failEveryKeepsFailing() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            rms.failEvery(FaultyRecords.ANY_STORE, FaultyRecords.OPEN);
            for (int i = 0; i < 3; i++)
            {
                try
                {
                    RecordStore.openRecordStore(STORE, true);
                    Assert.isTrue("open " + i + " must have thrown", false);
                }
                catch (RecordStoreException expected) { }
            }
            rms.clearFaults();
            RecordStore store = RecordStore.openRecordStore(STORE, true);
            Assert.isTrue("and clearing the plan lets it open", store != null);
            store.closeRecordStore();
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * A restart is not a close. A store that only writes on
     * {@code closeRecordStore} has to lose data here, which is exactly what a
     * handset does when the battery comes out.
     */
    private static void restartKeepsTheDataAndDropsTheHandles() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RecordStore store = RecordStore.openRecordStore(STORE, true);
            int id = store.addRecord(bytes("survives"), 0, 8);

            rms.restart();                       // no close: the power went

            RecordStore reopened = RecordStore.openRecordStore(STORE, false);
            Assert.equal("the record survived", "survives",
                    text(reopened.getRecord(id)));
            Assert.equal("and so did the count", 1, reopened.getNumRecords());
            reopened.closeRecordStore();
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * The specification promises no enumeration order at all. Code that
     * resolves duplicates by "whichever came out first" is wrong on some
     * handset; varying the order is how that surfaces here instead.
     */
    private static void enumerationOrderIsNotToBeReliedOn() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RecordStore store = RecordStore.openRecordStore(STORE, true);
            store.addRecord(bytes("1"), 0, 1);
            store.addRecord(bytes("2"), 0, 1);
            store.addRecord(bytes("3"), 0, 1);

            rms.enumerationOrder(FaultyRecords.ASCENDING);
            String up = order(store);
            rms.enumerationOrder(FaultyRecords.DESCENDING);
            String down = order(store);

            Assert.equal("ascending", "123", up);
            Assert.equal("descending", "321", down);
            store.closeRecordStore();
        }
        finally { EmulatorRecords.restore(); }
    }

    private static void corruptionHelpersAreReproducible() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RecordStore store = RecordStore.openRecordStore(STORE, true);
            int id = store.addRecord(bytes("abcdefgh"), 0, 8);

            rms.truncate(STORE, id, 3);
            Assert.equal("truncated to three bytes", "abc",
                    text(rms.peek(STORE, id)));

            rms.poke(STORE, id, bytes("abcdefgh"));
            rms.flipBit(STORE, id, 0, 0);
            Assert.equal("one bit of one byte moved", (byte) ('a' ^ 1),
                    rms.peek(STORE, id)[0]);
            Assert.equal("and nothing else did", (byte) 'b',
                    rms.peek(STORE, id)[1]);

            rms.poke(STORE, id, bytes("abcdefgh"));
            int copy = rms.duplicate(STORE, id);
            Assert.isTrue("the duplicate got a new id", copy != id);
            Assert.equal("with the same bytes", "abcdefgh",
                    text(rms.peek(STORE, copy)));

            rms.garble(STORE, id, 16);
            Assert.equal("garbled to the length asked for", 16,
                    rms.peek(STORE, id).length);
            store.closeRecordStore();
        }
        finally { EmulatorRecords.restore(); }
    }

    // ================================================================== store

    private static void theAuthKeyStoreSurvivesARestart() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            new RmsAuthKeyStore().save(key(1));

            rms.restart();
            AuthKeyLoad load = new RmsAuthKeyStore().load(DC, true);

            Assert.isTrue("the key is found after a restart", load.isFound());
            Assert.isTrue("and it is the one that was written",
                    sameKey(load.key, key(1)));
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * Old-valid. The write is refused before it lands, and what was there
     * before is still there - which is why the store adds before it deletes.
     */
    private static void aRefusedWriteKeepsTheOldKey() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            new RmsAuthKeyStore().save(key(1));

            rms.failEvery(KEYS, FaultyRecords.ADD);
            new RmsAuthKeyStore().save(key(2));
            rms.clearFaults();

            rms.restart();
            AuthKeyLoad load = new RmsAuthKeyStore().load(DC, true);

            Assert.isTrue("something is still stored", load.isFound());
            Assert.isTrue("and it is the old key, not half of the new one",
                    sameKey(load.key, key(1)));
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * New-valid. The record landed and the call reported failure, so the store
     * believes it did not write - and the next boot has to read the new key
     * rather than a mixture.
     */
    private static void aWriteThatLandedButReportedFailureIsStillReadable()
            throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            new RmsAuthKeyStore().save(key(1));

            rms.failAt(KEYS, FaultyRecords.ADD, 1, true);
            new RmsAuthKeyStore().save(key(2));
            rms.clearFaults();

            rms.restart();
            AuthKeyLoad load = new RmsAuthKeyStore().load(DC, true);

            Assert.isTrue("a key is readable", load.isFound());
            Assert.isTrue("and it is one of the two, not a mixture",
                    sameKey(load.key, key(1)) || sameKey(load.key, key(2)));
            Assert.isTrue("the newer record wins", sameKey(load.key, key(2)));
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * The interrupted cleanup: the new record is in, the old one has not been
     * removed yet, and the power goes. Two records for one name, and the store
     * has to pick the same one every time - by id, not by whatever order the
     * handset enumerates in.
     */
    private static void anInterruptedCleanupLeavesADuplicateAndTheNewestWins()
            throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            new RmsAuthKeyStore().save(key(1));

            // Let the add through, refuse the delete of the older duplicate.
            rms.failEvery(KEYS, FaultyRecords.DELETE);
            new RmsAuthKeyStore().save(key(2));
            rms.clearFaults();
            rms.restart();

            Assert.isTrue("both records are present",
                    rms.recordIds(KEYS).length >= 2);

            for (int pass = 0; pass < 2; pass++)
            {
                rms.enumerationOrder(pass == 0 ? FaultyRecords.ASCENDING
                        : FaultyRecords.DESCENDING);
                AuthKeyLoad load = new RmsAuthKeyStore().load(DC, true);
                Assert.isTrue("a key is readable in pass " + pass, load.isFound());
                Assert.isTrue("the newest wins regardless of enumeration order"
                        + " (pass " + pass + ")", sameKey(load.key, key(2)));
            }
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * The distinction the whole {@link AuthKeyLoad} type exists for: a store
     * that will not open is not a store with nothing in it, and answering the
     * second turns one bad read into a lost session.
     */
    private static void anUnreadableStoreIsNotAnEmptyOne() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            new RmsAuthKeyStore().save(key(1));
            rms.restart();

            rms.failEvery(KEYS, FaultyRecords.OPEN);
            AuthKeyLoad load = new RmsAuthKeyStore().load(DC, true);

            Assert.isFalse("an unreadable store did not find a key",
                    load.isFound());
            Assert.isFalse("and it does not claim there is nothing stored",
                    load.isNotFound());
            Assert.isTrue("it reports a storage failure", load.isIoError());
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * The harness proving it has teeth.
     *
     * This is what {@code RmsAuthKeyStore} would be if it deleted before it
     * added - the order that reads more naturally and loses the key. Written
     * out here rather than described, because a fault injector that cannot fail
     * a wrong implementation is not evidence that the right one is right.
     */
    private static void deleteThenAddLosesBoth() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RecordStore store = RecordStore.openRecordStore(STORE, true);
            int old = store.addRecord(bytes("old value"), 0, 9);
            store.closeRecordStore();

            // The unsafe save: remove, then write.
            rms.failAt(STORE, FaultyRecords.ADD, 1, false);
            store = RecordStore.openRecordStore(STORE, true);
            store.deleteRecord(old);
            try
            {
                store.addRecord(bytes("new value"), 0, 9);
                Assert.isTrue("the add was supposed to fail", false);
            }
            catch (RecordStoreException expected) { }
            rms.restart();

            Assert.equal("delete-then-add leaves neither value after a failure"
                    + " - which is the loss add-then-delete exists to avoid",
                    0, rms.recordIds(STORE).length);
        }
        finally { EmulatorRecords.restore(); }
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] bytes(String s)
    {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) { out[i] = (byte) s.charAt(i); }
        return out;
    }

    private static String text(byte[] value)
    {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < value.length; i++) { sb.append((char) (value[i] & 0xff)); }
        return sb.toString();
    }

    private static String order(RecordStore store) throws Exception
    {
        StringBuffer sb = new StringBuffer();
        javax.microedition.rms.RecordEnumeration e =
                store.enumerateRecords(null, null, false);
        while (e.hasNextElement()) { sb.append(text(e.nextRecord())); }
        e.destroy();
        return sb.toString();
    }

    /** A distinguishable 256-byte key. The value is what the test compares. */
    private static AuthKey key(int seed)
    {
        byte[] material = new byte[256];
        for (int i = 0; i < material.length; i++)
        {
            material[i] = (byte) (seed * 31 + i);
        }
        return new AuthKey(material, DC, true);
    }

    private static boolean sameKey(AuthKey a, AuthKey b)
    {
        if (a == null || b == null) { return false; }
        byte[] x = a.bytes();
        byte[] y = b.bytes();
        if (x == null || y == null || x.length != y.length) { return false; }
        for (int i = 0; i < x.length; i++)
        {
            if (x[i] != y[i]) { return false; }
        }
        return true;
    }
}
