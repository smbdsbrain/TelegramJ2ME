package tgtest;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

import tg.mt.AuthKey;
import tg.mt.AuthKeyLoad;
import tg.mt.AuthKeyRecord;
import tg.mt.AuthKeyStore;

/**
 * An {@link AuthKeyStore} in a Hashtable, shaped like the one on the handset.
 *
 * Four suites had grown a private copy of this, three of them stubs whose
 * {@code clear} did nothing. That was harmless while the interface only had to
 * answer {@code loadString}; it stopped being harmless once the logout wipe
 * started asking a store to delete entries and report whether they went, since
 * a stub that silently succeeds is exactly the answer a wipe test must not
 * accept.
 *
 * Entries live under the same names {@code tg.plat.RmsAuthKeyStore} uses -
 * {@code authkey.<env>.<dc>} carrying an {@link AuthKeyRecord} value, settings
 * under their own names, all in one flat namespace. A double that keys its keys
 * differently from the real store would pass a prefix sweep the handset fails.
 */
public class MemoryAuthKeyStore implements AuthKeyStore
{
    private final Hashtable values = new Hashtable();
    private boolean refuseEntryClears;

    public AuthKeyLoad load(int dcId, boolean testEnvironment)
    {
        String stored = (String) values.get(AuthKey.entryName(dcId, testEnvironment));
        if (stored == null) { return AuthKeyLoad.notFound(); }
        return AuthKeyRecord.decode(stored, dcId, testEnvironment);
    }

    public void save(AuthKey key)
    {
        values.put(AuthKey.entryName(key.dcId(), key.isTestEnvironment()),
                   AuthKeyRecord.encode(key));
    }

    public void clear(int dcId, boolean testEnvironment)
    {
        values.remove(AuthKey.entryName(dcId, testEnvironment));
    }

    public String loadString(String name)
    {
        return (String) values.get(name);
    }

    public void saveString(String name, String value)
    {
        if (value == null) { values.remove(name); }
        else { values.put(name, value); }
    }

    public boolean clearEntries(String[] names, String[] prefixes)
    {
        if (refuseEntryClears) { return false; }
        Vector doomed = new Vector();
        for (Enumeration e = values.keys(); e.hasMoreElements(); )
        {
            String name = (String) e.nextElement();
            if (isListed(name, names, prefixes)) { doomed.addElement(name); }
        }
        for (int i = 0; i < doomed.size(); i++)
        {
            values.remove(doomed.elementAt(i));
        }
        return true;
    }

    /**
     * Make the sweep refuse, the way a store that will not open does.
     *
     * A wipe has to keep going and report the refusal rather than stop at it,
     * and that cannot be shown with a double whose every operation succeeds.
     */
    public void refuseEntryClears()
    {
        refuseEntryClears = true;
    }

    private static boolean isListed(String name, String[] names,
                                    String[] prefixes)
    {
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

    /** How many entries are held, for tests that assert nothing was left. */
    public int size()
    {
        return values.size();
    }

    /** Every entry name currently held, for a test that has to name a leak. */
    public String[] names()
    {
        Vector found = new Vector();
        for (Enumeration e = values.keys(); e.hasMoreElements(); )
        {
            found.addElement(e.nextElement());
        }
        String[] out = new String[found.size()];
        found.copyInto(out);
        return out;
    }
}
