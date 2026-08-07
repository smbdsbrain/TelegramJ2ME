package tgtest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import tg.mt.AuthKey;
import tg.mt.AuthKeyLoad;
import tg.mt.AuthKeyRecord;
import tg.mt.AuthKeyStore;

/**
 * Desktop {@link AuthKeyStore}, backed by a properties file under secrets/.
 *
 * Exists so the live tests do not redo the handshake on every run: it is slow,
 * and every run would leave another abandoned key in Telegram's records for the
 * account. Reusing a stored key is also what the handset does, so testing that
 * path here means it has been exercised before it reaches a device.
 *
 * The file lives in secrets/ because it holds an auth_key - possession of which
 * is equivalent to possession of the session. secrets/ is gitignored and
 * bootstrap.ps1 verifies that.
 */
public final class FileAuthKeyStore implements AuthKeyStore
{
    private final File file;
    private final Properties props = new Properties();

    public FileAuthKeyStore()
    {
        this(new File("secrets/live-session.properties"));
    }

    public FileAuthKeyStore(File file)
    {
        this.file = file;
        load();
    }

    public AuthKeyLoad load(int dcId, boolean testEnvironment)
    {
        String value = props.getProperty(AuthKey.entryName(dcId, testEnvironment));
        if (value == null || value.length() == 0)
        {
            return AuthKeyLoad.notFound();
        }
        // The same record format the handset stores, seeding version included,
        // so a live run exercises the encoding that ships rather than a second
        // one that only looks like it.
        AuthKeyLoad loaded = AuthKeyRecord.decode(value, dcId, testEnvironment);
        if (loaded.isCorrupt())
        {
            // Kept, not discarded, for the same reason the handset keeps it:
            // a damaged key is the only description of what damaged it, and
            // deleting it here would hide a bug in the live path.
            System.out.println("stored key unusable (" + loaded.detail + ")");
        }
        return loaded;
    }

    public void save(AuthKey key)
    {
        props.setProperty(AuthKey.entryName(key.dcId(), key.isTestEnvironment()),
                          AuthKeyRecord.encode(key));
        store();
    }

    public void clear(int dcId, boolean testEnvironment)
    {
        props.remove(AuthKey.entryName(dcId, testEnvironment));
        store();
    }

    public String loadString(String name)
    {
        return props.getProperty(name);
    }

    public void saveString(String name, String value)
    {
        if (value == null)
        {
            props.remove(name);
        }
        else
        {
            props.setProperty(name, value);
        }
        store();
    }

    /** The logout sweep, over a properties file instead of RMS. */
    public boolean clearEntries(String[] names, String[] prefixes)
    {
        List<String> doomed = new ArrayList<String>();
        for (Enumeration<?> e = props.propertyNames(); e.hasMoreElements(); )
        {
            String name = String.valueOf(e.nextElement());
            if (isListed(name, names, prefixes)) { doomed.add(name); }
        }
        for (int i = 0; i < doomed.size(); i++)
        {
            props.remove(doomed.get(i));
        }
        store();
        return true;
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

    public File file()
    {
        return file;
    }

    private void load()
    {
        if (!file.exists())
        {
            return;
        }
        FileInputStream in = null;
        try
        {
            in = new FileInputStream(file);
            props.load(in);
        }
        catch (Exception e)
        {
            System.out.println("could not read " + file + ": " + e.getMessage());
        }
        finally
        {
            if (in != null) { try { in.close(); } catch (Exception ignored) { } }
        }
    }

    private void store()
    {
        FileOutputStream out = null;
        try
        {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists())
            {
                parent.mkdirs();
            }
            out = new FileOutputStream(file);
            props.store(out, "Live-test session state. Contains an auth_key - "
                             + "do not commit. secrets/ is gitignored.");
        }
        catch (Exception e)
        {
            System.out.println("could not write " + file + ": " + e.getMessage());
        }
        finally
        {
            if (out != null) { try { out.close(); } catch (Exception ignored) { } }
        }
    }
}
