package tg.api;

import tg.diag.Diag;
import tg.mt.AuthKey;
import tg.mt.AuthKeyStore;

/**
 * Erases every local trace of the signed-in account, and says what it could not.
 *
 * Logging out was spread over two files and neither had the whole list.
 * {@code Telegram.logOut} cleared the auth key of the data centre it was
 * currently talking to; {@code TgMidlet.finishLoggedOut} cleared three caches,
 * each in a {@code try/catch} that logged and carried on. Nothing cleared the
 * keys of the other data centres, the media import markers, the home data
 * centre pointer, or the stored account id - and nothing told the user when a
 * delete failed.
 *
 * <h3>What belongs to an account</h3>
 * This class is the list. Anything holding a credential, an identifier or a
 * conversation is registered here; the proxy, the theme, the log level and the
 * measured heap ceiling are not, and survive a logout on purpose. Someone
 * adding a store later has one place to look, which is the point of putting the
 * list somewhere rather than leaving it implied by two call sites.
 *
 * <h3>Keys are swept by prefix, not walked by data centre</h3>
 * A key is stored per data centre, and the authoritative list of data centres
 * comes from {@code help.getConfig} - a photo can name one this build has no
 * built-in address for, and downloading it stores a key under that number. So
 * "delete the keys" cannot mean "walk the numbers we know". The sweep matches
 * {@link AuthKey#entryPrefix} and {@link MediaAuthorization#markerPrefix}, both
 * of which name the environment, so the other environment's key - a different
 * account, on the same handset - is left alone.
 *
 * <h3>Order</h3>
 * The stored account id goes first. Background work that writes to the caches
 * asks for it before every write and skips the write when it is absent, so
 * removing it disarms writers that are already running before the stores they
 * would write to are emptied.
 *
 * <h3>Everything is attempted</h3>
 * A component that fails does not stop the ones after it. A logout that gave up
 * halfway would leave more behind than one that tried everything and reported
 * two failures, and the report is what makes the second honest.
 */
public final class AccountWipe
{
    /** Drafts, avatars, chat cache, outbox, update state, and room to spare. */
    private static final int MAX_COMPONENTS = 8;

    /** Set while signed in; read to tell "never signed in" from "logged out". */
    public static final String AUTHORIZED = "authorized";

    /** The data centre the account lives on. */
    public static final String HOME_DC = "dc";

    /**
     * Prefix of the entry holding the signed-in user's Telegram id.
     *
     * The offline caches are keyed on it, so it has to outlive a restart - and
     * therefore has to be deleted explicitly, or the next account inherits the
     * previous one's cache key.
     */
    public static final String CACHE_ACCOUNT = "cache.account.";

    private final AuthKeyStore keys;
    private final boolean testEnvironment;

    private final String[] labels = new String[MAX_COMPONENTS];
    private final AccountStore[] stores = new AccountStore[MAX_COMPONENTS];
    private int count;

    public AccountWipe(AuthKeyStore keys, boolean testEnvironment)
    {
        this.keys = keys;
        this.testEnvironment = testEnvironment;
    }

    /** The environment suffix used by the account-bound entry names. */
    public static String environment(boolean testEnvironment)
    {
        return testEnvironment ? "test" : "prod";
    }

    /**
     * Register a store whose whole contents belong to the account.
     *
     * Bounded and fixed: every registration happens once, where the application
     * is assembled, so a full registry means a component was added without
     * raising the bound rather than a queue that grew.
     *
     * @return false when nothing was registered
     */
    public synchronized boolean add(String label, AccountStore store)
    {
        if (store == null || label == null) { return false; }
        if (count == MAX_COMPONENTS)
        {
            Diag.error("logout wipe cannot register " + label
                       + ": only " + MAX_COMPONENTS + " components fit");
            return false;
        }
        labels[count] = label;
        stores[count] = store;
        count++;
        return true;
    }

    /**
     * Erase everything, whatever happens. Never throws.
     *
     * Safe to call twice, and safe on a handset where the user never opened a
     * chat: deleting from a store that was never created is a success.
     */
    public synchronized WipeReport run()
    {
        String env = environment(testEnvironment);
        StringBuffer failed = new StringBuffer();

        // Identity first - see the class comment on ordering.
        if (!keys.clearEntries(
                new String[] { CACHE_ACCOUNT + env, AUTHORIZED, HOME_DC },
                null))
        {
            note(failed, "session identity");
        }
        if (!keys.clearEntries(null,
                new String[] { AuthKey.entryPrefix(testEnvironment) }))
        {
            note(failed, "auth keys");
        }
        if (!keys.clearEntries(null,
                new String[] { MediaAuthorization.markerPrefix(testEnvironment) }))
        {
            note(failed, "media authorizations");
        }

        for (int i = 0; i < count; i++)
        {
            try
            {
                stores[i].clear();
            }
            catch (Throwable t)
            {
                // Named, not swallowed: a cache that would not empty is a
                // privacy fact during a logout, whatever it is the rest of the
                // time.
                Diag.error("logout could not erase " + labels[i], t);
                note(failed, labels[i]);
            }
        }

        WipeReport report = new WipeReport(failed.length() == 0,
                                           failed.toString());
        if (report.complete) { Diag.info(report.describe()); }
        else { Diag.error(report.describe()); }
        return report;
    }

    private static void note(StringBuffer failed, String label)
    {
        if (failed.length() > 0) { failed.append(", "); }
        failed.append(label);
    }
}
