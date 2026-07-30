package tg.plat;

import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;

import tg.diag.Diag;
import tg.io.Hex;
import tg.mt.AuthKey;
import tg.mt.AuthKeyStore;

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
 */
public final class RmsAuthKeyStore implements AuthKeyStore
{
    private static final String STORE = "tgkeys";

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
            Diag.error("stored auth_key is unusable, discarding", t);
            clear(dcId, testEnvironment);
            return null;
        }
    }

    public synchronized void save(AuthKey key)
    {
        saveString(keyName(key.dcId(), key.isTestEnvironment()),
                   Hex.encode(key.bytes()));
        Diag.info("persisted " + key.describe());
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
            while (en.hasNextElement())
            {
                byte[] raw = en.nextRecord();
                String line = new String(raw);
                int eq = line.indexOf('=');
                if (eq > 0 && line.substring(0, eq).equals(name))
                {
                    return line.substring(eq + 1);
                }
            }
            return null;
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
        RecordStore rs = null;
        RecordEnumeration en = null;
        try
        {
            rs = RecordStore.openRecordStore(STORE, true);

            // Remove any existing entry first: RMS has no update-by-key, and
            // leaving a stale duplicate would make reads non-deterministic.
            en = rs.enumerateRecords(null, null, false);
            while (en.hasNextElement())
            {
                int id = en.nextRecordId();
                try
                {
                    String line = new String(rs.getRecord(id));
                    int eq = line.indexOf('=');
                    if (eq > 0 && line.substring(0, eq).equals(name))
                    {
                        rs.deleteRecord(id);
                    }
                }
                catch (Throwable ignored) { /* record vanished, fine */ }
            }

            if (value != null)
            {
                byte[] data = (name + "=" + value).getBytes();
                rs.addRecord(data, 0, data.length);
            }
        }
        catch (Throwable t)
        {
            Diag.error("RMS write failed for " + name, t);
        }
        finally
        {
            if (en != null) { try { en.destroy(); } catch (Throwable ignored) { } }
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
