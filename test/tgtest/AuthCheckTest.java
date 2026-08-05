package tgtest;

import java.io.IOException;
import java.util.Hashtable;

import tg.api.AuthCheck;
import tg.api.Telegram;
import tg.crypto.Rng;
import tg.mt.AuthKey;
import tg.mt.AuthKeyStore;

/**
 * A network failure must not be reported as a logged-out account.
 *
 * This is the defect a Nokia C3-00 surfaced. The stored key loaded, the route
 * worked, {@code help.getConfig} came back - and then {@code users.getSelf} got
 * no reply inside the 60-second timeout. The old code caught every
 * {@code IOException}, returned null, and the UI read null as "not signed in"
 * and asked for a phone number, with a perfectly good auth_key still in RMS.
 *
 * The user-visible symptom was "the login is not saved", which sent the
 * investigation at the persistence layer, where nothing was wrong.
 *
 * The transient case is driven here through the real code path, using the one
 * failure that needs no server: an unconnected client, whose {@code invoke}
 * throws {@code IOException("not connected")}. That is the same catch clause a
 * timeout lands in.
 */
public final class AuthCheckTest implements Test
{
    public String name() { return "api/auth-check"; }

    public void run() throws Exception
    {
        aTransientFailureIsNotARefusal();
        theStoredFlagSurvivesAFailedCheck();
        theOldSignatureStillBehaves();
        verdictValuesAreDistinct();
        unknownAlwaysCarriesACause();
    }

    private static void aTransientFailureIsNotARefusal()
    {
        Telegram tg = offline(new MemoryStore());

        AuthCheck check = tg.verifyAuthorization();

        Assert.isTrue("a network failure is inconclusive", check.isUnknown());
        Assert.isFalse("it is not a refusal", check.isNo());
        Assert.isFalse("it is not an authorization", check.isYes());
        Assert.isTrue("no peer comes back", check.peer == null);
        Assert.isTrue("the cause is carried for the UI to name",
                check.error != null);
        Assert.isTrue("the detail is not empty",
                check.detail != null && check.detail.length() > 0);
    }

    /**
     * The flag written at sign-in was never read anywhere - three writes, zero
     * reads. It is read now, and the failed check must leave it alone: it is
     * the only remaining evidence that there was an account to come back to.
     */
    private static void theStoredFlagSurvivesAFailedCheck()
    {
        MemoryStore store = new MemoryStore();
        store.saveString("authorized", "1");
        store.save(key());

        Telegram tg = offline(store);
        AuthCheck check = tg.verifyAuthorization();

        Assert.isTrue("still inconclusive", check.isUnknown());
        Assert.equal("the signed-in flag is not cleared", "1",
                store.loadString("authorized"));
        Assert.isTrue("the stored key is not discarded",
                store.load(2, false) != null);
        Assert.isFalse("the client does not claim to be authorized",
                tg.isAuthorized());
    }

    /**
     * Seven live tests call {@code checkAuthorization()} and read null as "not
     * signed in". The wrapper has to keep behaving that way, or this change
     * quietly breaks them somewhere with a real account attached.
     */
    private static void theOldSignatureStillBehaves()
    {
        Telegram tg = offline(new MemoryStore());
        Assert.isTrue("the old entry point still returns null on failure",
                tg.checkAuthorization() == null);
    }

    private static void verdictValuesAreDistinct()
    {
        Assert.isTrue("YES, NO and UNKNOWN are three values",
                AuthCheck.YES != AuthCheck.NO
                        && AuthCheck.NO != AuthCheck.UNKNOWN
                        && AuthCheck.YES != AuthCheck.UNKNOWN);

        AuthCheck no = AuthCheck.no("PHONE_NUMBER_UNOCCUPIED");
        Assert.isTrue("a refusal is a refusal", no.isNo());
        Assert.isTrue("a refusal carries no peer", no.peer == null);
        Assert.isTrue("a refusal is not an error", no.error == null);
        Assert.equal("a refusal keeps its reason", "PHONE_NUMBER_UNOCCUPIED",
                no.detail);
    }

    /**
     * The UI hands {@code error} straight to a screen that names its class, so a
     * null there would fail while reporting a failure.
     */
    private static void unknownAlwaysCarriesACause()
    {
        AuthCheck fromNull = AuthCheck.unknown(null);
        Assert.isTrue("unknown is unknown", fromNull.isUnknown());
        Assert.isTrue("a cause is substituted rather than left null",
                fromNull.error != null);

        IOException real = new IOException("timed out waiting for a reply");
        AuthCheck carried = AuthCheck.unknown(real);
        Assert.isTrue("the original exception is kept", carried.error == real);
        Assert.equal("and its message becomes the detail",
                "timed out waiting for a reply", carried.detail);
    }

    // --------------------------------------------------------------- helpers

    /**
     * A client that was never connected. {@code invoke} throws before any
     * socket is involved, which is the transient branch under test.
     */
    private static Telegram offline(AuthKeyStore store)
    {
        return new Telegram(new SeTransport(), new Rng(), store);
    }

    private static AuthKey key()
    {
        byte[] raw = new byte[AuthKey.KEY_SIZE];
        for (int i = 0; i < raw.length; i++) { raw[i] = (byte) (i + 1); }
        return new AuthKey(raw, 2, false);
    }

    /** In-memory AuthKeyStore, so nothing here touches a real profile. */
    private static final class MemoryStore implements AuthKeyStore
    {
        private final Hashtable values = new Hashtable();
        private final Hashtable keys = new Hashtable();

        public AuthKey load(int dcId, boolean test)
        {
            return (AuthKey) keys.get(name(dcId, test));
        }

        public void save(AuthKey key)
        {
            keys.put(name(key.dcId(), key.isTestEnvironment()), key);
        }

        public void clear(int dcId, boolean test)
        {
            keys.remove(name(dcId, test));
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

        private static String name(int dcId, boolean test)
        {
            return (test ? "test." : "prod.") + dcId;
        }
    }
}
