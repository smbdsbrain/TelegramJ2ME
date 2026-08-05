package tgtest;

import javax.microedition.rms.RecordStore;

import tg.mt.AuthKey;
import tg.plat.RmsAuthKeyStore;
import tg.tl.Utf8;

/**
 * The store that carries the login, which until now had no test at all.
 *
 * It is the only store in the client that could not report a failure: a write
 * that threw was caught, logged to an in-memory ring that the next launch
 * discards, and then announced as {@code "persisted ..."} anyway. It also
 * deleted the old record before writing the new one, so a refused write lost
 * both copies. A handset where that happened presents as "the app forgot my
 * login", which is exactly the report that came back from a Nokia C3-00 - and
 * exactly the claim nothing here could check.
 *
 * These run against MicroEmulator's file-backed RecordStore rather than a fake,
 * because the behaviour under test is RMS semantics - record ids, enumeration
 * order, delete - and a fake would only be a restatement of what the code
 * already assumes.
 */
public final class RmsAuthKeyStoreTest implements Test
{
    /**
     * Not a real data centre.
     *
     * The store is shared with whatever emulator profile is on this machine -
     * {@code tgkeys} is one store, and MicroEmulator's file-backed manager
     * writes it under {@code user.home}. Telegram numbers its data centres 1 to
     * 5, so entries under 99 cannot collide with a signed-in session, and this
     * suite deletes only what it wrote. Wiping the store would have been simpler
     * and would have logged a developer out of their emulator profile every
     * time they ran the tests.
     */
    private static final int DC = 99;

    public String name() { return "plat/rms-auth-key-store"; }

    public void run() throws Exception
    {
        EmulatorHarness.installRecordStore();
        try
        {
            keySurvivesAStoreAndLoad();
            writesAreVerifiedAndReported();
            theNewestRecordWins();
            nonAsciiValuesSurvive();
            clearRemovesTheKey();
        }
        finally
        {
            cleanUp();
        }
    }

    /** The whole point of the class: a key written comes back identical. */
    private static void keySurvivesAStoreAndLoad()
    {
        RmsAuthKeyStore store = new RmsAuthKeyStore();
        AuthKey key = key((byte) 7);

        store.save(key);
        AuthKey back = store.load(DC, false);

        Assert.isTrue("a stored key loads back", back != null);
        Assert.bytesEqual("the key bytes round trip", key.bytes(), back.bytes());
        Assert.equal("the key id round trips", key.keyId(), back.keyId());
        Assert.equal("the dc round trips", DC, back.dcId());
        Assert.isTrue("a healthy store reports no write failures",
                store.writeFailureSummary() == null);
    }

    private static void writesAreVerifiedAndReported()
    {
        RmsAuthKeyStore store = new RmsAuthKeyStore();

        Assert.isTrue("a good write reports success",
                store.saveVerified("probe.value", "42"));
        Assert.equal("and is readable", "42", store.loadString("probe.value"));

        // A delete is a write that stores nothing; it must still succeed.
        Assert.isTrue("a delete reports success",
                store.saveVerified("probe.value", null));
        Assert.isTrue("and the value is gone",
                store.loadString("probe.value") == null);

        Assert.isTrue("no failures were recorded",
                store.writeFailureSummary() == null);
    }

    /**
     * Writes add before they delete, so a lost delete leaves two records for one
     * name. The reader has to prefer the newest, or a failed cleanup pins the
     * client to a stale auth key forever.
     */
    private static void theNewestRecordWins() throws Exception
    {
        RmsAuthKeyStore store = new RmsAuthKeyStore();
        store.saveVerified("probe.dup", "old");

        // Plant the duplicate the way a half-completed write would leave it:
        // a second record with the same name and a higher id.
        RecordStore rs = RecordStore.openRecordStore("tgkeys", true);
        try
        {
            byte[] data = Utf8.encode("probe.dup=new");
            rs.addRecord(data, 0, data.length);
        }
        finally
        {
            rs.closeRecordStore();
        }

        Assert.equal("the highest record id wins", "new",
                store.loadString("probe.dup"));

        // And writing again must collapse the duplicates rather than pile up.
        store.saveVerified("probe.dup", "newest");
        Assert.equal("a rewrite resolves cleanly", "newest",
                store.loadString("probe.dup"));
        Assert.equal("only one record is left for the name", 1,
                countRecords("probe.dup"));

        store.saveVerified("probe.dup", null);
    }

    /**
     * Records are written with Utf8, not String.getBytes().
     *
     * Every handset measured reports {@code microedition.encoding=ISO8859-1},
     * where the platform conversion turns Cyrillic into question marks. Values
     * here are usually ASCII hex, but a proxy host or a cached display name is
     * not guaranteed to be.
     */
    private static void nonAsciiValuesSurvive()
    {
        RmsAuthKeyStore store = new RmsAuthKeyStore();
        String sample = "привет 👋";

        Assert.isTrue("a non-ASCII value is accepted",
                store.saveVerified("probe.text", sample));
        Assert.equal("a non-ASCII value round trips", sample,
                store.loadString("probe.text"));

        store.saveVerified("probe.text", null);
    }

    private static void clearRemovesTheKey()
    {
        RmsAuthKeyStore store = new RmsAuthKeyStore();
        store.save(key((byte) 9));
        Assert.isTrue("the key is there before the clear",
                store.load(DC, false) != null);

        store.clear(DC, false);
        Assert.isTrue("clear removes the key", store.load(DC, false) == null);

        // Production and test keys are separate entries; clearing one must not
        // touch the other.
        store.save(key((byte) 3));
        Assert.isTrue("clearing the test key leaves production alone",
                store.load(DC, false) != null);
        store.clear(DC, true);
        Assert.isTrue("the production key is still there",
                store.load(DC, false) != null);
        store.clear(DC, false);
    }

    // --------------------------------------------------------------- helpers

    private static AuthKey key(byte seed)
    {
        byte[] raw = new byte[AuthKey.KEY_SIZE];
        for (int i = 0; i < raw.length; i++)
        {
            raw[i] = (byte) (i * seed + seed);
        }
        return new AuthKey(raw, DC, false);
    }

    private static int countRecords(String name) throws Exception
    {
        RecordStore rs = RecordStore.openRecordStore("tgkeys", true);
        try
        {
            int found = 0;
            int next = rs.getNextRecordID();
            for (int id = 1; id < next; id++)
            {
                try
                {
                    byte[] raw = rs.getRecord(id);
                    if (raw == null) { continue; }
                    String line = Utf8.decode(raw);
                    int eq = line.indexOf('=');
                    if (eq > 0 && line.substring(0, eq).equals(name)) { found++; }
                }
                catch (Throwable ignored) { /* deleted id */ }
            }
            return found;
        }
        finally
        {
            rs.closeRecordStore();
        }
    }

    /** Remove exactly what this suite wrote, and nothing else. */
    private static void cleanUp()
    {
        RmsAuthKeyStore store = new RmsAuthKeyStore();
        store.clear(DC, false);
        store.clear(DC, true);
        String[] names = { "probe.value", "probe.dup", "probe.text" };
        for (int i = 0; i < names.length; i++)
        {
            try { store.saveString(names[i], null); }
            catch (Throwable ignored) { }
        }
    }
}
