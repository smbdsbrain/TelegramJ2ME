package tg.plat;

import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;

import tg.diag.CrashLog;
import tg.diag.Diag;
import tg.mt.AuthKey;
import tg.mt.AuthKeyLoad;
import tg.mt.AuthKeyRecord;
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
 * still start - RMS on an unknown handset is exactly the sort of thing that
 * behaves differently from the spec.
 *
 * <h3>A read reports what it found, and never destroys it</h3>
 * Loading used to answer null for three different states - nothing stored, a
 * damaged record, a store that would not open - and to {@code clear()} the
 * record it had just failed to decode. The caller reads "nothing stored" as a
 * first launch and generates a fresh key over the top, so an unreadable store
 * cost the session and the evidence in one step. {@link #load} now returns an
 * {@link AuthKeyLoad} naming which of the four it was, and deletes nothing:
 * whether to regenerate belongs to the caller, and a corrupt record is the only
 * description anyone will ever get of what the handset did.
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
 *
 * <h3>What a key record contains</h3>
 * The bytes and the seeding version that produced them, in one value - see
 * {@link AuthKeyRecord}. This class owns which record store an entry lives in
 * and whether the write took; it owns neither what a key value looks like nor
 * what it is called, which is what lets the desktop store speak the same format
 * and lets the logout sweep in {@link tg.api.AccountWipe} match on
 * {@link AuthKey#entryPrefix} without restating it.
 */
public final class RmsAuthKeyStore implements AuthKeyStore
{
    private static final String STORE = "tgkeys";

    /** {@link #read} found the entry. */
    private static final int READ_OK = 0;
    /** {@link #read} opened the store and the entry was not in it. */
    private static final int READ_ABSENT = 1;
    /** {@link #read} could not open or enumerate the store. */
    private static final int READ_FAILED = 2;

    /** Last write failure, for the Diagnostics screen. Null when healthy. */
    private String lastWriteError;
    private int writeFailures;

    /**
     * Whether a damaged key has already reached the crash log this run.
     *
     * The record is no longer deleted when it fails to decode, so every connect
     * attempt would otherwise append another entry - and the log keeps three,
     * so a reconnect loop would push out the failure that started it.
     */
    private boolean corruptionRecorded;

    public synchronized AuthKeyLoad load(int dcId, boolean testEnvironment)
    {
        String name = AuthKey.entryName(dcId, testEnvironment);
        // slot[0] is the value, slot[1] the class of whatever stopped the read;
        // CLDC has no tuple and this is the same out-parameter shape used by
        // Session.encrypt.
        String[] slot = new String[2];
        int status = read(name, slot);
        if (status == READ_FAILED)
        {
            return AuthKeyLoad.ioError(String.valueOf(slot[1]));
        }
        if (status == READ_ABSENT)
        {
            return AuthKeyLoad.notFound();
        }

        // Binds to this data centre and environment: a key is only valid for
        // the pair it was negotiated with, and the entry name is what carries
        // that pair.
        AuthKeyLoad loaded = AuthKeyRecord.decode(slot[0], dcId, testEnvironment);
        if (loaded.isCorrupt())
        {
            return corrupt(loaded.detail, null);
        }
        if (loaded.isFound())
        {
            Diag.info("loaded stored " + loaded.key.describe());
        }
        return loaded;
    }

    /**
     * How the stored key for this data centre was seeded, without decoding it.
     *
     * The start screen asks on every launch, before anything connects, and has
     * no use for the bytes. Reading only the prefix keeps 256 bytes of session
     * out of the heap for a question about one small integer - and an
     * unreadable store answers {@link AuthKey#SEEDING_NONE}, which claims
     * nothing about a key it could not see.
     */
    public synchronized int storedSeeding(int dcId, boolean testEnvironment)
    {
        String[] slot = new String[2];
        if (read(AuthKey.entryName(dcId, testEnvironment), slot) != READ_OK)
        {
            return AuthKey.SEEDING_NONE;
        }
        return AuthKeyRecord.seedingOf(slot[0]);
    }

    public synchronized void save(AuthKey key)
    {
        if (saveVerified(AuthKey.entryName(key.dcId(), key.isTestEnvironment()),
                         AuthKeyRecord.encode(key)))
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
        saveString(AuthKey.entryName(dcId, testEnvironment), null);
    }

    /**
     * Settings keep the nullable contract: a missing proxy host and an
     * unreadable one both mean "use the default", and there is nothing better
     * for a caller to do about either.
     */
    public synchronized String loadString(String name)
    {
        String[] slot = new String[2];
        read(name, slot);
        return slot[0];
    }

    /**
     * @param slot receives the value in {@code [0]} and, on failure, the class
     *             of what went wrong in {@code [1]}
     * @return {@link #READ_OK}, {@link #READ_ABSENT} or {@link #READ_FAILED}
     */
    private int read(String name, String[] slot)
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
            slot[0] = best;
            return best == null ? READ_ABSENT : READ_OK;
        }
        catch (Throwable t)
        {
            Diag.error("RMS read failed for " + name, t);
            slot[1] = Diag.className(t);
            return READ_FAILED;
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

    /**
     * The logout sweep: delete listed entries, then look again before saying so.
     *
     * This replaces a {@code clearAll} that deleted the whole record store. It
     * was never called, and it could not have been: the proxy, the theme, the
     * log level and the measured heap ceiling live in here beside the keys, and
     * losing those to a logout is not a trade any user agreed to. It also
     * swallowed every failure, which is the one thing a privacy operation may
     * not do.
     *
     * The second pass is not defensive habit. RMS on an unknown handset is
     * exactly where a delete returns without deleting, and the caller is about
     * to tell someone their account is off this phone.
     */
    public synchronized boolean clearEntries(String[] names, String[] prefixes)
    {
        RecordStore rs = null;
        try
        {
            rs = RecordStore.openRecordStore(STORE, true);
            sweep(rs, names, prefixes, true);
            int left = sweep(rs, names, prefixes, false);
            if (left != 0)
            {
                return fail("account entries",
                        left + " of them are still stored", null);
            }
            return true;
        }
        catch (Throwable t)
        {
            return fail("account entries", Diag.className(t), t);
        }
        finally
        {
            close(rs);
        }
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

    /**
     * Visit every record whose entry name is listed, deleting or counting.
     *
     * The enumeration is not kept updated, so deleting inside the loop walks a
     * stable snapshot - the same assumption {@link #removeMatching} makes.
     *
     * @return how many listed entries were seen
     */
    private static int sweep(RecordStore rs, String[] names, String[] prefixes,
                             boolean delete) throws Exception
    {
        RecordEnumeration en = null;
        int seen = 0;
        try
        {
            en = rs.enumerateRecords(null, null, false);
            while (en.hasNextElement())
            {
                int id = en.nextRecordId();
                if (!isListed(nameOf(rs, id), names, prefixes)) { continue; }
                if (!delete) { seen++; continue; }
                try { rs.deleteRecord(id); }
                catch (Throwable ignored)
                {
                    // Not reported here: the counting pass is what decides,
                    // and a record that vanished under us is a success.
                }
            }
        }
        finally
        {
            if (en != null) { try { en.destroy(); } catch (Throwable ignored) { } }
        }
        return seen;
    }

    /** The entry name held by record {@code id}, or null when unreadable. */
    private static String nameOf(RecordStore rs, int id)
    {
        try
        {
            byte[] raw = rs.getRecord(id);
            if (raw == null) { return null; }
            String line = Utf8.decode(raw);
            int eq = line.indexOf('=');
            return eq > 0 ? line.substring(0, eq) : null;
        }
        catch (Throwable ignored)
        {
            return null; // deleted or unreadable id
        }
    }

    private static boolean isListed(String name, String[] names,
                                    String[] prefixes)
    {
        if (name == null) { return false; }
        for (int i = 0; names != null && i < names.length; i++)
        {
            if (name.equals(names[i])) { return true; }
        }
        for (int i = 0; prefixes != null && i < prefixes.length; i++)
        {
            if (prefixes[i] != null && name.startsWith(prefixes[i]))
            {
                return true;
            }
        }
        return false;
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

    /**
     * Record a damaged key and answer with it.
     *
     * Diag is an in-memory ring that the next launch discards, and "the app
     * forgot my login" is reported after that launch, so the first occurrence
     * also goes to the crash log, which survives.
     */
    private AuthKeyLoad corrupt(String detail, Throwable t)
    {
        Diag.error("stored auth_key is unusable: " + detail, t);
        if (!corruptionRecorded)
        {
            corruptionRecorded = true;
            CrashLog.save("rms-key-unusable", t != null ? t
                    : new RuntimeException("stored auth_key is unusable: "
                                           + detail));
        }
        return AuthKeyLoad.corrupt(detail);
    }

    private static void close(RecordStore rs)
    {
        if (rs != null)
        {
            try { rs.closeRecordStore(); } catch (Throwable ignored) { }
        }
    }
}
