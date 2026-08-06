package tgtest;

import tg.io.Hex;
import tg.mt.AuthKey;
import tg.mt.AuthKeyLoad;
import tg.mt.AuthKeyRecord;

/**
 * What a stored key says about how it was seeded.
 *
 * Strengthening key generation does nothing for the keys already in RMS, and
 * the stored record used to be 512 hex characters and nothing else - so a key
 * negotiated after one {@code Entropy.gather()} and a key that crossed the
 * measured barrier were the same bytes in the same shape, and nothing could
 * tell a user which one they were carrying.
 *
 * The cases here are about the durable value rather than about RMS, so they run
 * without an emulator; {@code RmsAuthKeyStoreTest} covers the same encoding as
 * it survives a real record store.
 *
 * Two properties are load-bearing and easy to break later: a legacy key must
 * never come back marked current, and a version from a build newer than this
 * one must come back exactly as it went in.
 */
public final class AuthKeyProvenanceTest implements Test
{
    private static final int DC = 2;

    public String name() { return "mt/auth-key-provenance"; }

    public void run() throws Exception
    {
        aHandshakeKeyIsTheOnlyCurrentOne();
        anUnmarkedKeyIsNeverCurrent();
        aCurrentKeyRoundTripsThroughTheRecord();
        aLegacyValueLoadsAndIsRewrittenUnchanged();
        anUnknownFutureVersionIsKeptAsItIs();
        damagedValuesAreCorruptAndQuoteNothing();
        theVersionReadsWithoutDecodingTheKey();
        provenanceIsBoundToTheDcAndEnvironment();
        theWordingRecommendsRatherThanAlarms();
        aNegativeVersionIsRefused();
    }

    /**
     * The barrier is what earns the mark, and {@code Handshake} is the only
     * production path that crosses it - so it is the only one that may claim
     * the current version. {@code SourceGuardTest} pins that at source level;
     * this pins what the factory actually stamps.
     */
    private static void aHandshakeKeyIsTheOnlyCurrentOne()
    {
        AuthKey fresh = AuthKey.fromHandshake(raw((byte) 3), DC, false);

        Assert.equal("a handshake key carries the current version",
                AuthKey.SEEDING_CURRENT, fresh.provenance());
        Assert.isTrue("and reads as current",
                AuthKey.isSeedingCurrent(fresh.provenance()));
        Assert.isFalse("so nothing is recommended",
                AuthKey.seedingNeedsReauth(fresh.provenance()));
        Assert.isTrue("and there is no advice to show",
                AuthKey.seedingAdvice(fresh.provenance()) == null);
    }

    /**
     * The plain constructor means "provenance unknown", not "assume the best".
     *
     * A key that arrives without a version is either a record written before
     * the field existed or a mistake in some later path; presenting either as
     * current is the one answer that cannot be corrected afterwards.
     */
    private static void anUnmarkedKeyIsNeverCurrent()
    {
        AuthKey unmarked = new AuthKey(raw((byte) 5), DC, false);

        Assert.equal("an unmarked key is unknown-legacy",
                AuthKey.SEEDING_UNKNOWN_LEGACY, unmarked.provenance());
        Assert.isFalse("and is not current",
                AuthKey.isSeedingCurrent(unmarked.provenance()));
        Assert.isTrue("so it carries a recommendation",
                AuthKey.seedingNeedsReauth(unmarked.provenance()));
        Assert.isTrue("and there is something to show",
                AuthKey.seedingAdvice(unmarked.provenance()) != null);
    }

    private static void aCurrentKeyRoundTripsThroughTheRecord()
    {
        AuthKey fresh = AuthKey.fromHandshake(raw((byte) 7), DC, false);
        String value = AuthKeyRecord.encode(fresh);

        Assert.equal("the value carries the version and the key",
                "p" + AuthKey.SEEDING_CURRENT + ":" + Hex.encode(fresh.bytes()),
                value);

        AuthKeyLoad back = AuthKeyRecord.decode(value, DC, false);
        Assert.isTrue("it decodes: " + back.describe(), back.isFound());
        Assert.bytesEqual("the key bytes round trip",
                fresh.bytes(), back.key.bytes());
        Assert.equal("the version round trips",
                AuthKey.SEEDING_CURRENT, back.key.provenance());
        Assert.equal("the key id is unaffected",
                fresh.keyId(), back.key.keyId());

        // Re-saving a key that was never regenerated must not change what is
        // claimed about it - in either direction.
        Assert.equal("re-encoding is byte-identical",
                value, AuthKeyRecord.encode(back.key));
    }

    /**
     * The upgrade path, and the reason the legacy form is written back as it
     * was: an unmarked record is already exactly as informative as {@code p0:}
     * would be, and leaving it alone means a handset downgraded to an older
     * build still finds its session.
     */
    private static void aLegacyValueLoadsAndIsRewrittenUnchanged()
    {
        String legacy = Hex.encode(raw((byte) 11));

        AuthKeyLoad loaded = AuthKeyRecord.decode(legacy, DC, false);
        Assert.isTrue("a bare hex value still loads: " + loaded.describe(),
                loaded.isFound());
        Assert.bytesEqual("with the key intact", raw((byte) 11),
                loaded.key.bytes());
        Assert.equal("and no version recorded",
                AuthKey.SEEDING_UNKNOWN_LEGACY, loaded.key.provenance());
        Assert.isTrue("so it recommends signing in again",
                AuthKey.seedingNeedsReauth(loaded.key.provenance()));

        Assert.equal("re-encoding keeps the legacy shape",
                legacy, AuthKeyRecord.encode(loaded.key));
        Assert.equal("which is still 512 characters", AuthKey.KEY_SIZE * 2,
                AuthKeyRecord.encode(loaded.key).length());
    }

    /**
     * A version this build has never heard of comes from one that knows more.
     *
     * It is used as it is, reported as neither current nor legacy, and above
     * all written back unchanged - clamping it to the current version would
     * make a downgrade permanently lie about the key.
     */
    private static void anUnknownFutureVersionIsKeptAsItIs()
    {
        int future = AuthKey.SEEDING_CURRENT + 8;
        String value = "p" + future + ":" + Hex.encode(raw((byte) 13));

        AuthKeyLoad loaded = AuthKeyRecord.decode(value, DC, false);
        Assert.isTrue("a future version still loads: " + loaded.describe(),
                loaded.isFound());
        Assert.equal("and keeps its number", future, loaded.key.provenance());
        Assert.isFalse("it is not this build's current version",
                AuthKey.isSeedingCurrent(loaded.key.provenance()));
        Assert.isFalse("and it is not something to warn about",
                AuthKey.seedingNeedsReauth(loaded.key.provenance()));
        Assert.isTrue("so there is no advice",
                AuthKey.seedingAdvice(loaded.key.provenance()) == null);
        Assert.equal("re-encoding does not downgrade it",
                value, AuthKeyRecord.encode(loaded.key));
    }

    /** Damage is described by shape. The value is the session. */
    private static void damagedValuesAreCorruptAndQuoteNothing()
    {
        String hex = Hex.encode(raw((byte) 17));
        String[] bad = {
            "",                                     // an empty record
            "p",                                    // a truncated prefix
            "p:" + hex,                             // a prefix with no digits
            "pX:" + hex,                            // a version that is not one
            "p1" + hex,                             // no separator at all
            "p1:" + hex.substring(0, 400),          // a truncated key
            "p1:" + hex + "ff",                     // an over-long key
            "p1:" + hex.substring(2) + "zz",        // not hex where it must be
            hex.substring(0, 256)                   // legacy form, truncated
        };

        for (int i = 0; i < bad.length; i++)
        {
            AuthKeyLoad loaded = AuthKeyRecord.decode(bad[i], DC, false);
            Assert.isTrue("value #" + i + " is corrupt, not usable: "
                    + loaded.describe(), loaded.isCorrupt());
            Assert.isTrue("value #" + i + " carries no key", loaded.key == null);
            // Only for values long enough to be key material: "p" appears
            // inside "malformed seeding version prefix" by coincidence, and a
            // one-character record is not a session.
            Assert.isTrue("the detail for #" + i + " does not quote the value",
                    bad[i].length() < 16 || loaded.detail.indexOf(bad[i]) < 0);
            Assert.isTrue("the detail for #" + i + " does not quote the key",
                    loaded.detail.indexOf(hex.substring(0, 32)) < 0);
        }

        Assert.isTrue("a missing value is not damage",
                AuthKeyRecord.decode(null, DC, false).isNotFound());
    }

    /**
     * The start screen asks on every launch and has no use for 256 bytes of
     * session, so the version is readable from the prefix alone - and has to
     * agree with what a full decode would say, including when it refuses.
     */
    private static void theVersionReadsWithoutDecodingTheKey()
    {
        String hex = Hex.encode(raw((byte) 19));
        String[] good = { hex, "p1:" + hex, "p9:" + hex, "p1234:" + hex };

        for (int i = 0; i < good.length; i++)
        {
            AuthKeyLoad loaded = AuthKeyRecord.decode(good[i], DC, false);
            Assert.isTrue("value #" + i + " decodes", loaded.isFound());
            Assert.equal("the prefix read agrees with the full decode for #" + i,
                    loaded.key.provenance(), AuthKeyRecord.seedingOf(good[i]));
        }

        String[] none = { null, "", "p", "p:" + hex, "pX:" + hex,
                          "p1:" + hex.substring(0, 400), hex.substring(0, 256) };
        for (int i = 0; i < none.length; i++)
        {
            Assert.equal("nothing readable in #" + i + " is no version",
                    AuthKey.SEEDING_NONE, AuthKeyRecord.seedingOf(none[i]));
        }
    }

    /**
     * A key belongs to one data centre in one environment, and the entry name
     * is what carries that pair - the version must not disturb it.
     */
    private static void provenanceIsBoundToTheDcAndEnvironment()
    {
        String value = AuthKeyRecord.encode(
                AuthKey.fromHandshake(raw((byte) 23), 4, true));

        AuthKeyLoad test = AuthKeyRecord.decode(value, 4, true);
        Assert.isTrue("it loads for the pair it was filed under",
                test.isFound());
        Assert.isTrue("and matches that pair", test.key.matches(4, true));
        Assert.isFalse("but not production on the same dc",
                test.key.matches(4, false));
        Assert.equal("the version is unaffected by the environment",
                AuthKey.SEEDING_CURRENT, test.key.provenance());
    }

    /**
     * The wording is the product of this PR, so it is asserted like one.
     *
     * Nothing here is evidence that any key was compromised, and the client
     * states no entropy figure anywhere a user can read it as a strength
     * claim. It must also never be the thing that puts a key id on screen.
     */
    private static void theWordingRecommendsRatherThanAlarms()
    {
        String advice = AuthKey.seedingAdvice(AuthKey.SEEDING_UNKNOWN_LEGACY);
        Assert.isTrue("legacy keys have something to say", advice != null);
        String lower = advice.toLowerCase();
        String[] forbidden = { "compromis", "insecure", "unsafe", "weak",
                               "bits", "entropy", "danger", "attack" };
        for (int i = 0; i < forbidden.length; i++)
        {
            Assert.isTrue("the advice must not say '" + forbidden[i] + "': "
                    + advice, lower.indexOf(forbidden[i]) < 0);
        }
        Assert.isTrue("it says the session still works",
                lower.indexOf("still works") >= 0);
        Assert.isTrue("and names the action", lower.indexOf("sign in") >= 0);

        int[] states = { AuthKey.SEEDING_NONE, AuthKey.SEEDING_UNKNOWN_LEGACY,
                         AuthKey.SEEDING_CURRENT, AuthKey.SEEDING_CURRENT + 8 };
        for (int i = 0; i < states.length; i++)
        {
            String line = AuthKey.describeSeeding(states[i]);
            Assert.isTrue("every state describes itself: " + states[i],
                    line != null && line.length() > 0);
            Assert.isTrue("and the description fits one diagnostics line: "
                    + line, line.indexOf('\n') < 0);
        }
        Assert.isFalse("a current key is not described as legacy",
                AuthKey.describeSeeding(AuthKey.SEEDING_CURRENT)
                        .indexOf("not recorded") >= 0);
    }

    /** There is no version below "none recorded"; a negative one is a bug. */
    private static void aNegativeVersionIsRefused()
    {
        try
        {
            new AuthKey(raw((byte) 29), DC, false, -1);
            Assert.fail("a negative seeding version was accepted");
        }
        catch (IllegalArgumentException expected) { }

        Assert.isFalse("and 'no key' is not a version to warn about",
                AuthKey.seedingNeedsReauth(AuthKey.SEEDING_NONE));
        Assert.isTrue("nor one to describe as a key",
                AuthKey.seedingAdvice(AuthKey.SEEDING_NONE) == null);
    }

    // --------------------------------------------------------------- helpers

    private static byte[] raw(byte seed)
    {
        byte[] out = new byte[AuthKey.KEY_SIZE];
        for (int i = 0; i < out.length; i++)
        {
            out[i] = (byte) (i * seed + seed);
        }
        return out;
    }
}
