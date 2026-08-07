package tgtest;

import tg.api.AccountStore;
import tg.api.AccountWipe;
import tg.api.MediaAuthorization;
import tg.api.MemoryDraftStore;
import tg.api.MemoryOutgoingStore;
import tg.api.MemoryUpdateStateStore;
import tg.api.Peer;
import tg.api.Telegram;
import tg.api.UpdateState;
import tg.api.WipeReport;
import tg.crypto.Rng;
import tg.mt.AuthKey;
import tg.mt.Dc;

/**
 * Logging out has to leave nothing of the account behind.
 *
 * The client stores an auth_key per data centre, and a download from another
 * data centre stores one for that one too, plus a marker recording that this
 * account's authorization was imported into it. Logout cleared exactly one key
 * - the home data centre's - and no marker at all, so the media session of the
 * account that just signed out stayed on the handset, ready and addressed.
 *
 * The marker is the part that turns leftovers into a leak.
 * {@link MediaAuthorization#needsImport} skips the export/import handshake when
 * the stored marker names the key it is about to use, which is correct while
 * one account owns both. After a logout that leaves both in place, the next
 * account inherits the decision: it never imports its own authorization, and
 * its file requests travel on the previous account's imported session.
 *
 * These run against doubles rather than RMS. The wipe deletes whole record
 * stores by name, and a desktop suite shares the developer's MicroEmulator
 * profile, so running the real thing here would empty the drafts and dialog
 * cache of whatever account is signed in on this machine. What genuinely needs
 * RMS semantics - deleting entries by name and prefix out of a store shared
 * with the settings - is tested in {@link RmsAuthKeyStoreTest} against sentinel
 * names it also cleans up.
 */
public final class AccountWipeTest implements Test
{
    /** Not a real data centre; see {@link RmsAuthKeyStoreTest}. */
    private static final int MEDIA_DC = 4;

    private static final boolean TEST_ENV = Dc.isTest();
    private static final String ENV = TEST_ENV ? "test" : "prod";

    public String name() { return "api/account-wipe"; }

    public void run() throws Exception
    {
        everyDataCentreKeyGoes();
        theMediaImportMarkersGo();
        theAccountIdAndHomeDataCentreGo();
        connectionAndInterfaceSettingsStay();
        theOtherEnvironmentIsNotTouched();
        theNextAccountImportsItsOwnAuthorization();
        everyComponentIsAttemptedWhenOneFails();
        aRefusedKeyStoreDoesNotStopTheCaches();
        theReportNamesLabelsAndNeverValues();
        erasingTwiceIsErasingOnce();
        nothingStoredIsNotAFailure();
        accountBSeesNothingOfAccountA();
        aWriterRunningDuringTheErasureIsToldTheAccountIsGone();
    }

    /**
     * A key for a data centre this build has no bootstrap address for is stored
     * exactly like any other, because the authoritative list comes from
     * help.getConfig. So "clear the keys" cannot mean "walk the addresses this
     * build happens to know".
     */
    private static void everyDataCentreKeyGoes() throws Exception
    {
        MemoryAuthKeyStore store = signedIn();
        store.save(key(MEDIA_DC));
        store.save(key(7));

        logOut(store);

        Assert.isTrue("the home data centre key is gone",
                store.load(Dc.BOOTSTRAP_DC_ID, TEST_ENV).isNotFound());
        Assert.isTrue("the media data centre key is gone",
                store.load(MEDIA_DC, TEST_ENV).isNotFound());
        Assert.isTrue("a key for a data centre outside the built-in table is "
                      + "gone too", store.load(7, TEST_ENV).isNotFound());
    }

    private static void theMediaImportMarkersGo() throws Exception
    {
        MemoryAuthKeyStore store = signedIn();
        String marker = MediaAuthorization.markerName(MEDIA_DC, TEST_ENV);
        store.saveString(marker, String.valueOf(key(MEDIA_DC).keyId()));

        logOut(store);

        Assert.isTrue("the media import marker is gone",
                store.loadString(marker) == null);
    }

    /** The stored account id is the signed-in user's Telegram id. */
    private static void theAccountIdAndHomeDataCentreGo() throws Exception
    {
        MemoryAuthKeyStore store = signedIn();

        logOut(store);

        Assert.isTrue("the cached account id is gone",
                store.loadString("cache.account." + ENV) == null);
        Assert.isTrue("the home data centre pointer is gone",
                store.loadString("dc") == null);
        Assert.isTrue("the signed-in flag is gone",
                store.loadString("authorized") == null);
    }

    /**
     * The keys share one record store with every setting, so the wipe may not
     * be a delete of that store. A logout that costs the user their proxy is a
     * logout they will avoid.
     */
    private static void connectionAndInterfaceSettingsStay() throws Exception
    {
        MemoryAuthKeyStore store = signedIn();
        store.saveString("net.proxy.host", "proxy.example.net");
        store.saveString("net.proxy.port", "443");
        store.saveString("ui.theme", "1");
        store.saveString("log.level", "2");
        store.saveString("heap.ceiling", "2097152");

        logOut(store);

        Assert.equal("the proxy host survives", "proxy.example.net",
                store.loadString("net.proxy.host"));
        Assert.equal("the proxy port survives", "443",
                store.loadString("net.proxy.port"));
        Assert.equal("the theme survives", "1", store.loadString("ui.theme"));
        Assert.equal("the log level survives", "2",
                store.loadString("log.level"));
        Assert.equal("the measured heap ceiling survives", "2097152",
                store.loadString("heap.ceiling"));
    }

    /**
     * A key is bound to one data centre and one environment, and the two live
     * side by side under different names. Only a deliberate factory reset may
     * take the environment this build is not talking to.
     */
    private static void theOtherEnvironmentIsNotTouched() throws Exception
    {
        MemoryAuthKeyStore store = signedIn();
        AuthKey other = new AuthKey(rawKey((byte) 23), MEDIA_DC, !TEST_ENV);
        store.save(other);
        String otherMarker = MediaAuthorization.markerName(MEDIA_DC, !TEST_ENV);
        store.saveString(otherMarker, String.valueOf(other.keyId()));

        logOut(store);

        Assert.isTrue("the other environment keeps its key",
                store.load(MEDIA_DC, !TEST_ENV).isFound());
        Assert.isTrue("and its import marker",
                store.loadString(otherMarker) != null);
    }

    /**
     * The leak stated as the next account would experience it: with the key and
     * the marker both left behind, the client decides it has already imported
     * an authorization into that data centre - the previous account's.
     */
    private static void theNextAccountImportsItsOwnAuthorization()
            throws Exception
    {
        MemoryAuthKeyStore store = signedIn();
        AuthKey mediaKey = key(MEDIA_DC);
        store.save(mediaKey);
        store.saveString(MediaAuthorization.markerName(MEDIA_DC, TEST_ENV),
                         String.valueOf(mediaKey.keyId()));

        Assert.isFalse("account A had already imported into the media dc",
                MediaAuthorization.needsImport(Dc.BOOTSTRAP_DC_ID, MEDIA_DC,
                        mediaKey, store.loadString(MediaAuthorization
                                .markerName(MEDIA_DC, TEST_ENV))));

        logOut(store);

        Assert.isTrue("account B must import its own authorization",
                MediaAuthorization.needsImport(Dc.BOOTSTRAP_DC_ID, MEDIA_DC,
                        mediaKey, store.loadString(MediaAuthorization
                                .markerName(MEDIA_DC, TEST_ENV))));
    }

    /**
     * One component refusing must not become the reason the rest survive.
     *
     * The old cleanup was three independent try/catch blocks that logged and
     * carried on, which had this property by accident. Having it on purpose
     * means also being able to say which one refused.
     */
    private static void everyComponentIsAttemptedWhenOneFails()
    {
        MemoryAuthKeyStore store = signedIn();
        AccountWipe wipe = new AccountWipe(store, TEST_ENV);
        CountingStore drafts = new CountingStore(false);
        CountingStore avatars = new CountingStore(true);
        CountingStore chats = new CountingStore(false);
        wipe.add("drafts", drafts);
        wipe.add("avatars", avatars);
        wipe.add("chat cache", chats);

        WipeReport report = wipe.run();

        Assert.isFalse("a refused component makes the wipe incomplete",
                report.complete);
        Assert.equal("and it is the one named", "avatars", report.failed);
        Assert.equal("the component before it was cleared", 1, drafts.cleared);
        Assert.equal("the failing one was attempted", 1, avatars.cleared);
        Assert.equal("and the one after it was not skipped", 1, chats.cleared);
        Assert.isTrue("the credentials still went",
                store.load(Dc.BOOTSTRAP_DC_ID, TEST_ENV).isNotFound());
    }

    /** The keys are first, and failing there may not abandon the caches. */
    private static void aRefusedKeyStoreDoesNotStopTheCaches()
    {
        MemoryAuthKeyStore store = signedIn();
        store.refuseEntryClears();
        AccountWipe wipe = new AccountWipe(store, TEST_ENV);
        CountingStore drafts = new CountingStore(false);
        wipe.add("drafts", drafts);

        WipeReport report = wipe.run();

        Assert.isFalse("an unusable key store is a failure", report.complete);
        Assert.isTrue("naming session identity: " + report.failed,
                report.failed.indexOf("session identity") >= 0);
        Assert.isTrue("naming the keys", report.failed.indexOf("auth keys") >= 0);
        Assert.isTrue("naming the media authorizations",
                report.failed.indexOf("media authorizations") >= 0);
        Assert.equal("and the drafts were still cleared", 1, drafts.cleared);
    }

    /**
     * The report is shown on a screen and read out to whoever is helping.
     *
     * It names components. A key, a phone number or a message body reaching it
     * would be a leak created by the code that exists to prevent one.
     */
    private static void theReportNamesLabelsAndNeverValues()
    {
        String keyHex = "0123456789abcdef0123456789abcdef";
        String phone = "+995322123456";
        String chat = "Anna Novikova";

        MemoryAuthKeyStore store = signedIn();
        store.refuseEntryClears();
        store.saveString("cache.account." + ENV, "776655443322");
        AccountWipe wipe = new AccountWipe(store, TEST_ENV);
        wipe.add("drafts", new CountingStore(true));

        String described = wipe.run().describe();

        Assert.isTrue("no key material in " + described,
                described.indexOf(keyHex) < 0);
        Assert.isTrue("no phone number", described.indexOf(phone) < 0);
        Assert.isTrue("no peer title", described.indexOf(chat) < 0);
        Assert.isTrue("no account id",
                described.indexOf("776655443322") < 0);
        Assert.isTrue("but it does name the component: " + described,
                described.indexOf("drafts") >= 0);
    }

    /**
     * Retrying an erasure is the only recovery offered for a partial one, so it
     * has to be safe to run against a handset that is already clean.
     */
    private static void erasingTwiceIsErasingOnce()
    {
        MemoryAuthKeyStore store = signedIn();
        store.save(key(MEDIA_DC));
        AccountWipe wipe = new AccountWipe(store, TEST_ENV);
        CountingStore drafts = new CountingStore(false);
        wipe.add("drafts", drafts);

        Assert.isTrue("the first run is clean", wipe.run().complete);
        Assert.isTrue("and so is the second", wipe.run().complete);
        Assert.equal("the component was asked twice", 2, drafts.cleared);
        Assert.equal("and nothing of the account is left", 0, store.size());
    }

    /** A handset where the user never signed in has nothing to fail at. */
    private static void nothingStoredIsNotAFailure()
    {
        MemoryAuthKeyStore store = new MemoryAuthKeyStore();
        AccountWipe wipe = new AccountWipe(store, TEST_ENV);
        wipe.add("drafts", new CountingStore(false));

        Assert.isTrue("an empty store erases cleanly", wipe.run().complete);
    }

    /**
     * The question the whole change exists to answer.
     *
     * Account A signs in, types a draft, queues a message and records an update
     * cursor. After a logout, account B must find an empty handset - not
     * A's chats behind B's name.
     */
    private static void accountBSeesNothingOfAccountA() throws Exception
    {
        MemoryAuthKeyStore store = signedIn();
        store.save(key(MEDIA_DC));
        store.saveString(MediaAuthorization.markerName(MEDIA_DC, TEST_ENV),
                         String.valueOf(key(MEDIA_DC).keyId()));

        MemoryDraftStore drafts = new MemoryDraftStore();
        MemoryOutgoingStore outbox = new MemoryOutgoingStore();
        MemoryUpdateStateStore updates = new MemoryUpdateStateStore();
        Peer peer = new Peer(Peer.USER, 4242);
        peer.accessHash = 99;
        peer.title = "Anna Novikova";
        drafts.save(peer, "see you at eight");
        outbox.add(peer, "on my way", 7788, 1000);
        UpdateState cursor = new UpdateState();
        cursor.accountId = 776655443322L;
        cursor.testEnvironment = TEST_ENV;
        cursor.pts = 4711;
        updates.save(cursor);

        AccountWipe wipe = new AccountWipe(store, TEST_ENV);
        wipe.add("drafts", drafts);
        wipe.add("outbox", outbox);
        wipe.add("update state", updates);

        Assert.isTrue("the erasure completes", wipe.run().complete);

        Assert.equal("account B finds no draft", "", drafts.load(peer));
        Assert.equal("no queued message", 0, outbox.list().length);
        Assert.isTrue("no update cursor",
                updates.load(776655443322L, TEST_ENV) == null);
        Assert.equal("and nothing at all in the key store", 0, store.size());
    }

    /**
     * Work already in flight must not refill what is being emptied.
     *
     * Three writers - the dialog cache, the history cache and the avatar worker
     * - ask for the account id before every write, and the avatar worker runs
     * on a second {@code Worker} that is genuinely concurrent with the one
     * running auth.logOut. What stops them is that by the time any store is
     * emptied there is no account to answer with: the session is not authorized,
     * the peer cache has no self, and the stored account id is already gone.
     *
     * Asserted from inside the erasure, by a component registered last, so this
     * is the state a writer would actually observe rather than the state left
     * behind afterwards.
     */
    private static void aWriterRunningDuringTheErasureIsToldTheAccountIsGone()
    {
        final MemoryAuthKeyStore store = signedIn();
        final Telegram telegram =
                new Telegram(new SeTransport(), new Rng(), store);
        final boolean[] observed = new boolean[3];

        telegram.accountWipe().add("witness", new AccountStore()
        {
            public void clear()
            {
                observed[0] = telegram.isAuthorized();
                observed[1] = telegram.peers().self() != null;
                observed[2] = store.loadString("cache.account." + ENV) != null;
            }
        });

        try { telegram.logOut(); }
        catch (Exception expected) { /* never connected */ }

        Assert.isFalse("the session no longer claims an account", observed[0]);
        Assert.isFalse("the peer cache has no self", observed[1]);
        Assert.isFalse("and the stored account id is already gone",
                observed[2]);
    }

    // --------------------------------------------------------------- helpers

    /** An {@link AccountStore} that counts, and optionally refuses. */
    private static final class CountingStore implements AccountStore
    {
        private final boolean broken;
        int cleared;

        CountingStore(boolean broken) { this.broken = broken; }

        public void clear() throws java.io.IOException
        {
            cleared++;
            if (broken) { throw new java.io.IOException("RMS said no"); }
        }
    }

    /** A store holding what a signed-in session leaves on the handset. */
    private static MemoryAuthKeyStore signedIn()
    {
        MemoryAuthKeyStore store = new MemoryAuthKeyStore();
        store.save(key(Dc.BOOTSTRAP_DC_ID));
        store.saveString("dc", String.valueOf(Dc.BOOTSTRAP_DC_ID));
        store.saveString("authorized", "1");
        store.saveString("cache.account." + ENV, "776655443322");
        return store;
    }

    /**
     * Log out with nothing to log out to.
     *
     * The client was never connected, so auth.logOut fails before a socket is
     * involved - which is the case that matters most here: local erasure has to
     * happen whether or not Telegram answered.
     */
    private static void logOut(MemoryAuthKeyStore store)
    {
        Telegram telegram = new Telegram(new SeTransport(), new Rng(), store);
        try
        {
            telegram.logOut();
            Assert.fail("an unconnected client cannot reach auth.logOut");
        }
        catch (Exception expected)
        {
            // "not connected" - the lost-reply path, deliberately.
        }
        Assert.isFalse("the client no longer claims an account",
                telegram.isAuthorized());
    }

    private static AuthKey key(int dcId)
    {
        return new AuthKey(rawKey((byte) (dcId + 1)), dcId, TEST_ENV);
    }

    private static byte[] rawKey(byte seed)
    {
        byte[] raw = new byte[AuthKey.KEY_SIZE];
        for (int i = 0; i < raw.length; i++)
        {
            raw[i] = (byte) (i * seed + seed);
        }
        return raw;
    }
}
