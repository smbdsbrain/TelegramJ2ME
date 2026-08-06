package tgtest;

import javax.microedition.rms.RecordStore;

import tg.io.Hex;
import tg.mt.AuthKey;
import tg.mt.AuthKeyLoad;
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
            aMissingKeyIsNotAFailure();
            writesAreVerifiedAndReported();
            theNewestRecordWins();
            theNewestKeyRecordWinsAndStillValidates();
            nonAsciiValuesSurvive();
            clearRemovesTheKey();
            aCorruptRecordIsKeptForDiagnosis();
            wrongLengthAndNonHexAreCorrupt();
            anUnreadableStoreIsNotAMissingKey();
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
        AuthKeyLoad loaded = store.load(DC, false);

        Assert.isTrue("a stored key loads back: " + loaded.describe(),
                loaded.isFound());
        AuthKey back = loaded.key;
        Assert.bytesEqual("the key bytes round trip", key.bytes(), back.bytes());
        Assert.equal("the key id round trips", key.keyId(), back.keyId());
        Assert.equal("the dc round trips", DC, back.dcId());
        Assert.isFalse("a production key is not marked test",
                back.isTestEnvironment());
        Assert.isTrue("a healthy store reports no write failures",
                store.writeFailureSummary() == null);

        // The environment is half of what a key is bound to, and it is carried
        // by the entry name. A production key must not answer for the test one.
        Assert.isTrue("the test environment has no key of its own",
                store.load(DC, true).isNotFound());

        AuthKey testKey = key((byte) 11, true);
        store.save(testKey);
        AuthKeyLoad testLoaded = store.load(DC, true);
        Assert.isTrue("a test-environment key loads back", testLoaded.isFound());
        Assert.bytesEqual("the test key bytes round trip",
                testKey.bytes(), testLoaded.key.bytes());
        Assert.isTrue("and it is marked test",
                testLoaded.key.isTestEnvironment());
        Assert.bytesEqual("storing it left production alone",
                key.bytes(), store.load(DC, false).key.bytes());

        store.clear(DC, true);
        store.clear(DC, false);
    }

    /** An empty store is an answer, not a failure. */
    private static void aMissingKeyIsNotAFailure()
    {
        RmsAuthKeyStore store = new RmsAuthKeyStore();
        store.clear(DC, false);

        AuthKeyLoad loaded = store.load(DC, false);
        Assert.isTrue("nothing stored reads as not-found: " + loaded.describe(),
                loaded.isNotFound());
        Assert.isTrue("and carries no key", loaded.key == null);
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
     * A duplicated key entry resolves to the newest one and is still checked.
     *
     * Writes add before they delete, so an interrupted write leaves two records
     * for one key. Picking the newest is only half the answer: the value that
     * wins still has to be validated, or a half-written duplicate would be
     * handed to the session layer as a key.
     */
    private static void theNewestKeyRecordWinsAndStillValidates()
            throws Exception
    {
        String name = "authkey.prod." + DC;
        RmsAuthKeyStore store = new RmsAuthKeyStore();
        AuthKey older = key((byte) 5);
        store.save(older);

        AuthKey newer = key((byte) 13);
        plant(name, Hex.encode(newer.bytes()));
        Assert.bytesEqual("the newest key record wins", newer.bytes(),
                store.load(DC, false).key.bytes());

        // Now make the newest one the damaged one: preferring it must not mean
        // trusting it.
        plant(name, halfAKey());
        Assert.isTrue("a damaged newest record is corrupt, not a fallback",
                store.load(DC, false).isCorrupt());

        store.saveString(name, null);
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
                store.load(DC, false).isFound());

        store.clear(DC, false);
        Assert.isTrue("clear removes the key",
                store.load(DC, false).isNotFound());

        // Production and test keys are separate entries; clearing one must not
        // touch the other.
        store.save(key((byte) 3));
        Assert.isTrue("clearing the test key leaves production alone",
                store.load(DC, false).isFound());
        store.clear(DC, true);
        Assert.isTrue("the production key is still there",
                store.load(DC, false).isFound());
        store.clear(DC, false);
    }

    /**
     * A key that cannot be decoded is evidence, and evidence is not deleted.
     *
     * The store used to call {@code clear()} on the record it had just failed to
     * read, so the only copy of a truncated or half-flushed key was gone before
     * anyone could look at its shape - and the next connect, seeing nothing
     * stored, generated a fresh key and left the old session behind. Whether the
     * client goes on to regenerate is the caller's decision; destroying the
     * record while reading it is not the store's to make.
     */
    private static void aCorruptRecordIsKeptForDiagnosis() throws Exception
    {
        String name = "authkey.prod." + DC;
        plant(name, halfAKey());

        RmsAuthKeyStore store = new RmsAuthKeyStore();
        AuthKeyLoad first = store.load(DC, false);

        Assert.isTrue("a truncated key reads as corrupt: " + first.describe(),
                first.isCorrupt());
        Assert.isTrue("and carries no key", first.key == null);
        Assert.isTrue("an unusable record survives being read",
                store.loadString(name) != null);
        Assert.isTrue("so a second read says the same thing",
                store.load(DC, false).isCorrupt());

        store.saveString(name, null);
    }

    /**
     * Everything that is not 512 hex characters is corrupt, and says which.
     *
     * The length is checked before decoding rather than after, so the answer
     * names the damage - a short value points at a per-record cap or a partial
     * flush, a stray character at the wrong encoding on the way in - without
     * the value reaching a message that diagnostics may upload.
     */
    private static void wrongLengthAndNonHexAreCorrupt() throws Exception
    {
        String name = "authkey.prod." + DC;
        String[] bad = { "", "0", halfAKey(), halfAKey() + halfAKey() + "ff",
                         nearlyAKey() + "zz" };

        for (int i = 0; i < bad.length; i++)
        {
            RmsAuthKeyStore store = new RmsAuthKeyStore();
            store.saveString(name, null);
            plant(name, bad[i]);

            AuthKeyLoad loaded = store.load(DC, false);
            Assert.isTrue("stored value #" + i + " is corrupt, not usable: "
                    + loaded.describe(), loaded.isCorrupt());
            Assert.isTrue("the detail for #" + i + " does not quote the value",
                    loaded.detail.indexOf(bad[i]) < 0 || bad[i].length() == 0);
        }

        new RmsAuthKeyStore().saveString(name, null);
    }

    /**
     * A store that will not open is not a store with nothing in it.
     *
     * This is the failure the whole outcome type exists for. Every read used to
     * collapse to null, so an unreadable {@code tgkeys} looked exactly like a
     * first launch - and the connect path answers a first launch by generating
     * a key and writing it over whatever was there.
     */
    private static void anUnreadableStoreIsNotAMissingKey()
    {
        RmsAuthKeyStore store = new RmsAuthKeyStore();
        EmulatorRecords.installUnreadable();
        try
        {
            AuthKeyLoad loaded = store.load(DC, false);
            Assert.isTrue("an unreadable store is an I/O error, not an empty "
                    + "one: " + loaded.describe(), loaded.isIoError());
            Assert.isFalse("and is explicitly not 'no key stored'",
                    loaded.isNotFound());
            Assert.isTrue("it carries no key", loaded.key == null);
        }
        finally
        {
            EmulatorRecords.restore();
        }

        Assert.isTrue("the store works again afterwards",
                store.load(DC, false).isNotFound());
    }

    // --------------------------------------------------------------- helpers

    /** 256 hex characters where 512 are required: a truncated write. */
    private static String halfAKey()
    {
        return hexRun(AuthKey.KEY_SIZE);
    }

    /** The right length once two more characters are appended. */
    private static String nearlyAKey()
    {
        return hexRun(AuthKey.KEY_SIZE * 2 - 2);
    }

    private static String hexRun(int length)
    {
        StringBuffer sb = new StringBuffer(length);
        for (int i = 0; i < length; i++) { sb.append('a'); }
        return sb.toString();
    }

    /** Write a record the store itself would never produce. */
    private static void plant(String name, String value) throws Exception
    {
        RecordStore rs = RecordStore.openRecordStore("tgkeys", true);
        try
        {
            byte[] data = Utf8.encode(name + "=" + value);
            rs.addRecord(data, 0, data.length);
        }
        finally
        {
            rs.closeRecordStore();
        }
    }

    private static AuthKey key(byte seed)
    {
        return key(seed, false);
    }

    private static AuthKey key(byte seed, boolean testEnvironment)
    {
        byte[] raw = new byte[AuthKey.KEY_SIZE];
        for (int i = 0; i < raw.length; i++)
        {
            raw[i] = (byte) (i * seed + seed);
        }
        return new AuthKey(raw, DC, testEnvironment);
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
