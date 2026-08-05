package tg.plat;

import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;

import tg.diag.CrashLog;
import tg.diag.Diag;
import tg.io.Hex;
import tg.mt.AuthKey;
import tg.mt.AuthKeyStore;
import tg.tl.Utf8;

/**
 * {@link AuthKeyStore} backed by RMS.
 *
 * One record store, one record per entry, each a line of the form
 * {@code name=value}. Deliberately simple: RMS record size limits on 2011
 * hardware are unmeasured, and an auth_key is only 512 hex characters, so a
 * flat key/value layout avoids needing an index or a compaction scheme.
 *
 * Every operation is defensive. A MIDlet that cannot read its stored key must
 * fall back to generating a new one, not fail to start - RMS on an unknown
 * handset is exactly the sort of thing that behaves differently from the spec.
 *
 * <h3>Writes are verified, and say so honestly</h3>
 * This class used to log {@code "persisted ..."} unconditionally, including
 * when the write had thrown and nothing had been stored. It is the only store
 * that carries the login, and it was the only one that could not report a
 * failure. Now every write is read back and compared before it is called a
 * success, and a failure is recorded where it survives the restart that would
 * otherwise erase the evidence.
 *
 * <h3>Add before delete</h3>
 * RMS has no update-by-key, so a value is replaced by writing a new record and
 * removing the old ones. The order matters: deleting first means a failed
 * {@code addRecord} - a full store, a per-record cap - loses the old value as
 * well as the new one, leaving no key at all. Adding first means the worst case
 * is a duplicate, and {@link #loadString} resolves duplicates by preferring the
 * highest record id, which is the most recently written.
 */
public final class RmsAuthKeyStore implements AuthKeyStore
{
    private static final String STORE = "tgkeys";

    /** Last write failure, for the Diagnostics screen. Null when healthy. */
    private String lastWriteError;
    private int writeFailures;

    public synchronized AuthKey load(int dcId, boolean testEnvironment)
    {
        String hex = loadString(keyName(dcId, testEnvironment));
        if (hex == null)
        {
            return null;
        }
        try
        {
            byte[] raw = Hex.decode(hex);
            AuthKey key = new AuthKey(raw, dcId, testEnvironment);
            Diag.info("loaded stored " + key.describe());
            return key;
        }
        catch (Throwable t)
        {
            // About to delete the only copy of the login, so record its shape
            // first. A stored key is 512 hex characters; anything else names
            // its own cause - a truncation points at a per-record cap or a
            // partial flush, junk at the wrong encoding. Diag is an in-memory
            // ring that is gone by the next launch, so this goes to the crash
            // log, which is not.
            Diag.error("stored auth_key is unusable, discarding: "
                       + shape(hex), t);
            CrashLog.save("rms-key-unusable", t);
            clear(dcId, testEnvironment);
            return null;
        }
    }

    public synchronized void save(AuthKey key)
    {
        if (saveVerified(keyName(key.dcId(), key.isTestEnvironment()),
                         Hex.encode(key.bytes())))
        {
            Diag.info("persisted " + key.describe());
        }
        else
        {
            // Not fatal - the session works, it just will not survive an exit,
            // and the next launch pays two 2048-bit modPows to regenerate. Say
            // so rather than claiming a success.
            Diag.error("could NOT persist " + key.describe()
                       + " - this session will not survive a restart");
        }
    }

    public synchronized void clear(int dcId, boolean testEnvironment)
    {
        saveString(keyName(dcId, testEnvironment), null);
    }

    public synchronized String loadString(String name)
    {
        RecordStore rs = null;
        RecordEnumeration en = null;
        try
        {
            rs = RecordStore.openRecordStore(STORE, true);
            en = rs.enumerateRecords(null, null, false);
            // Highest id wins: writes add before they delete, so if a delete
            // was ever lost the newest record is still the correct answer.
            int bestId = -1;
            String best = null;
            while (en.hasNextElement())
            {
                int id = en.nextRecordId();
                if (id <= bestId) { continue; }
                String value = valueOf(rs, id, name);
                if (value != null) { bestId = id; best = value; }
            }
            return best;
        }
        catch (Throwable t)
        {
            Diag.error("RMS read failed for " + name, t);
            return null;
        }
        finally
        {
            if (en != null) { try { en.destroy(); } catch (Throwable ignored) { } }
            close(rs);
        }
    }

    /** A null value deletes the entry. */
    public synchronized void saveString(String name, String value)
    {
        saveVerified(name, value);
    }

    /**
     * @return true when the value was written and read back identical, or
     *         deleted as asked; false when nothing could be stored
     */
    public synchronized boolean saveVerified(String name, String value)
    {
        RecordStore rs = null;
        try
        {
            rs = RecordStore.openRecordStore(STORE, true);

            int written = -1;
            if (value != null)
            {
                byte[] data = Utf8.encode(name + "=" + value);
                written = rs.addRecord(data, 0, data.length);
                String readBack = valueOf(rs, written, name);
                if (readBack == null || !readBack.equals(value))
                {
                    // The store accepted the record and gave back something
                    // else. Leave the previous value in place: it is the only
                    // remaining copy.
                    try { rs.deleteRecord(written); }
                    catch (Throwable ignored) { }
                    return fail(name, "read back "
                            + (readBack == null ? "nothing" : "a different value"),
                            null);
                }
            }

            // Only now that the new value is safely stored may the old ones go.
            removeMatching(rs, name, written);
            lastWriteError = null;
            return true;
        }
        catch (Throwable t)
        {
            return fail(name, Diag.className(t), t);
        }
        finally
        {
            close(rs);
        }
    }

    /** Wipe everything - the "log out" path. */
    public synchronized void clearAll()
    {
        try
        {
            RecordStore.deleteRecordStore(STORE);
            Diag.info("all stored keys and session state deleted");
        }
        catch (Throwable ignored) { /* nothing stored yet */ }
    }

    /** One line for the Diagnostics screen; null while nothing has failed. */
    public synchronized String writeFailureSummary()
    {
        if (lastWriteError == null && writeFailures == 0) { return null; }
        return writeFailures + " write failure(s), last: "
                + String.valueOf(lastWriteError);
    }

    // ------------------------------------------------------------- internals

    private boolean fail(String name, String why, Throwable t)
    {
        writeFailures++;
        lastWriteError = name + ": " + why;
        Diag.error("RMS write failed for " + name + " (" + why + ")", t);
        // Survives the restart. A store that cannot hold the auth_key presents
        // as "it forgot my login", and without this the only trace was a line
        // in a ring buffer that the restart itself discarded.
        CrashLog.save("rms-write", t != null ? t
                : new RuntimeException("RMS write failed for " + name
                                       + ": " + why));
        return false;
    }

    /** The value of record {@code id} if it holds {@code name}, else null. */
    private static String valueOf(RecordStore rs, int id, String name)
    {
        try
        {
            byte[] raw = rs.getRecord(id);
            if (raw == null) { return null; }
            String line = Utf8.decode(raw);
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).equals(name))
            {
                return line.substring(eq + 1);
            }
            return null;
        }
        catch (Throwable ignored)
        {
            return null; // deleted or unreadable id
        }
    }

    private static void removeMatching(RecordStore rs, String name, int keepId)
    {
        RecordEnumeration en = null;
        try
        {
            en = rs.enumerateRecords(null, null, false);
            while (en.hasNextElement())
            {
                int id = en.nextRecordId();
                if (id == keepId) { continue; }
                if (valueOf(rs, id, name) != null)
                {
                    try { rs.deleteRecord(id); }
                    catch (Throwable ignored) { /* record vanished, fine */ }
                }
            }
        }
        catch (Throwable ignored) { /* leaves a duplicate; highest id wins */ }
        finally
        {
            if (en != null) { try { en.destroy(); } catch (Throwable ignored) { } }
        }
    }

    /** Describe a bad stored value without disclosing it. */
    private static String shape(String hex)
    {
        if (hex == null) { return "absent"; }
        return "length " + hex.length() + " (expected "
                + (AuthKey.KEY_SIZE * 2) + ")";
    }

    private static String keyName(int dcId, boolean test)
    {
        return "authkey." + (test ? "test" : "prod") + "." + dcId;
    }

    private static void close(RecordStore rs)
    {
        if (rs != null)
        {
            try { rs.closeRecordStore(); } catch (Throwable ignored) { }
        }
    }
}
