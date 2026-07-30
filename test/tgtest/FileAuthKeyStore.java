package tgtest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

import tg.io.Hex;
import tg.mt.AuthKey;
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

    public AuthKey load(int dcId, boolean testEnvironment)
    {
        String hex = props.getProperty(keyName(dcId, testEnvironment));
        if (hex == null || hex.length() == 0)
        {
            return null;
        }
        try
        {
            return new AuthKey(Hex.decode(hex), dcId, testEnvironment);
        }
        catch (RuntimeException e)
        {
            System.out.println("stored key unusable (" + e.getMessage() + "), discarding");
            clear(dcId, testEnvironment);
            return null;
        }
    }

    public void save(AuthKey key)
    {
        props.setProperty(keyName(key.dcId(), key.isTestEnvironment()),
                          Hex.encode(key.bytes()));
        store();
    }

    public void clear(int dcId, boolean testEnvironment)
    {
        props.remove(keyName(dcId, testEnvironment));
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

    public void clearAll()
    {
        props.clear();
        store();
    }

    public File file()
    {
        return file;
    }

    private static String keyName(int dcId, boolean test)
    {
        return "authkey." + (test ? "test" : "prod") + "." + dcId;
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
