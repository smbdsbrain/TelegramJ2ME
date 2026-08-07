package tg.mt;

import tg.crypto.Sha1;
import tg.io.Hex;

/**
 * A negotiated MTProto authorization key and the identifiers derived from it.
 *
 * <pre>
 *   auth_key           2048 bits, the DH shared secret
 *   auth_key_id        low  64 bits of SHA1(auth_key) - sent with every message
 *   auth_key_aux_hash  high 64 bits of SHA1(auth_key) - only used to verify
 *                      dh_gen_ok during the handshake
 * </pre>
 *
 * The two hashes are easy to mix up and the specification says so explicitly;
 * confusing them makes {@code dh_gen_ok} verification fail with no clue why,
 * so they are separate accessors here rather than an index into one array.
 *
 * <h3>Persistence</h3>
 * Generating this key costs a 2048-bit modular exponentiation, which is the
 * single most expensive operation on the handset. It must be stored and reused
 * across restarts - regenerating on every launch would make the client unusable
 * and pointlessly churn Telegram's key table. See {@link AuthKeyStore}.
 *
 * A key is bound to one data centre AND one environment. A production key
 * presented to a test DC is answered with a bare -404 and no explanation, so
 * the DC id and environment are carried alongside it.
 *
 * <h3>How it was seeded travels with it</h3>
 * Strengthening key generation does nothing for a key that is already stored,
 * and until this field existed there was no way to tell the two apart: both are
 * 256 bytes, and neither says whether a {@link tg.crypto.AuthKeySeeding} barrier
 * was ever crossed. {@link #provenance()} is that answer, persisted in the same
 * record as the bytes. It names the client seeding <em>path</em> - not a
 * measured entropy figure, not a certification - so a key whose barrier ran
 * short of its target is still {@link #SEEDING_CURRENT}: it took the path.
 */
public final class AuthKey
{
    public static final int KEY_SIZE = 256;

    /**
     * No seeding version is recorded for this key.
     *
     * Every key stored before this field existed, which on the released builds
     * means one that may have been drawn from a pool seeded by a single
     * {@code Entropy.gather()}. Usable, and the client says so; the honest
     * statement is that its seeding is unknown, not that it is broken.
     */
    public static final int SEEDING_UNKNOWN_LEGACY = 0;

    /** Generated after crossing the measured {@code AuthKeySeeding} barrier. */
    public static final int SEEDING_BARRIER = 1;

    /**
     * What a key minted by this build carries.
     *
     * Monotonic. Raising it makes every smaller version - including
     * {@link #SEEDING_BARRIER} - fall under {@link #seedingNeedsReauth}, which
     * is the whole mechanism: a future improvement to seeding is deployed by
     * bumping this constant and writing the new path, and existing sessions
     * start recommending re-authentication on their own. A version larger than
     * this one comes from a build that knows more than this one does and is
     * never rewritten or downgraded.
     */
    public static final int SEEDING_CURRENT = SEEDING_BARRIER;

    /** No key at all: nothing stored, or nothing readable. Not a version. */
    public static final int SEEDING_NONE = -1;

    private final byte[] key;
    private final long keyId;
    private final long auxHash;
    private final int dcId;
    private final boolean testEnvironment;
    private final int provenance;

    /**
     * A key whose seeding path is not known.
     *
     * The conservative default, and deliberately the plain constructor: an
     * unmarked key must never be presented as current. The one production path
     * entitled to claim {@link #SEEDING_CURRENT} is {@link #fromHandshake},
     * and {@code tgtest.SourceGuardTest} keeps it that way.
     */
    public AuthKey(byte[] key, int dcId, boolean testEnvironment)
    {
        this(key, dcId, testEnvironment, SEEDING_UNKNOWN_LEGACY);
    }

    /**
     * A key negotiated by {@link Handshake}, which has just crossed the barrier.
     *
     * @param key 256 bytes of freshly agreed DH secret
     */
    public static AuthKey fromHandshake(byte[] key, int dcId,
                                        boolean testEnvironment)
    {
        return new AuthKey(key, dcId, testEnvironment, SEEDING_CURRENT);
    }

    /** @param provenance a {@code SEEDING_*} version, as read from storage */
    public AuthKey(byte[] key, int dcId, boolean testEnvironment, int provenance)
    {
        if (key == null || key.length != KEY_SIZE)
        {
            throw new IllegalArgumentException(
                    "auth_key must be " + KEY_SIZE + " bytes, got "
                    + (key == null ? -1 : key.length));
        }
        if (provenance < SEEDING_UNKNOWN_LEGACY)
        {
            throw new IllegalArgumentException(
                    "seeding version must not be negative, got " + provenance);
        }
        this.key = key;
        this.dcId = dcId;
        this.testEnvironment = testEnvironment;
        this.provenance = provenance;

        byte[] sha = Sha1.hash(key);
        this.auxHash = leLong(sha, 0);              // high-order 64 bits
        this.keyId = leLong(sha, sha.length - 8);   // low-order 64 bits
    }

    public byte[] bytes()
    {
        return key;
    }

    /** Sent in the header of every encrypted message. */
    public long keyId()
    {
        return keyId;
    }

    /** Used only to verify dh_gen_ok. Not the same as {@link #keyId()}. */
    public long auxHash()
    {
        return auxHash;
    }

    public int dcId()
    {
        return dcId;
    }

    public boolean isTestEnvironment()
    {
        return testEnvironment;
    }

    /**
     * Which client seeding path produced this key.
     *
     * @return a {@code SEEDING_*} version; {@link #SEEDING_UNKNOWN_LEGACY} when
     *         the stored record carried none
     */
    public int provenance()
    {
        return provenance;
    }

    /**
     * The name a store files this key under.
     *
     * Three implementations of {@link AuthKeyStore} had written the same
     * expression out separately, which was harmless while a name was only ever
     * built to read one key back. Logging out has to delete keys nobody
     * enumerated - the data centre list arrives from the server, so a key can
     * exist for a number this build has no address for - and a sweep by
     * {@link #entryPrefix} is only as complete as its agreement with whatever
     * wrote the entry. One expression, so the two cannot drift apart.
     */
    public static String entryName(int dcId, boolean testEnvironment)
    {
        return entryPrefix(testEnvironment) + dcId;
    }

    /** What every key entry for one environment starts with. */
    public static String entryPrefix(boolean testEnvironment)
    {
        return "authkey." + (testEnvironment ? "test" : "prod") + ".";
    }

    /** True when this key can be used against the given data centre. */
    public boolean matches(int otherDcId, boolean otherTest)
    {
        return dcId == otherDcId && testEnvironment == otherTest;
    }

    public String describe()
    {
        return "auth_key dc" + dcId + (testEnvironment ? " test" : " prod")
               + " id=" + Long.toString(keyId)
               + " sha1=" + Hex.encode(Sha1.hash(key), 0, 4)
               + " seed=" + provenance;
    }

    /** True when {@code provenance} is exactly what this build mints. */
    public static boolean isSeedingCurrent(int provenance)
    {
        return provenance == SEEDING_CURRENT;
    }

    /**
     * Whether a key with this provenance is worth replacing.
     *
     * True only for a real version older than this build's. A key from a
     * <em>newer</em> build is not "not current" in any sense the user should
     * act on, and {@link #SEEDING_NONE} is not a key at all.
     */
    public static boolean seedingNeedsReauth(int provenance)
    {
        return provenance >= SEEDING_UNKNOWN_LEGACY
                && provenance < SEEDING_CURRENT;
    }

    /** One line for diagnostics. Carries no key material. */
    public static String describeSeeding(int provenance)
    {
        if (provenance == SEEDING_NONE)
        {
            return "no key stored for this account";
        }
        if (provenance == SEEDING_UNKNOWN_LEGACY)
        {
            return "not recorded - created before this build kept track";
        }
        if (provenance == SEEDING_CURRENT)
        {
            return "v" + provenance + " - created with the measured seeding"
                   + " barrier";
        }
        if (provenance > SEEDING_CURRENT)
        {
            return "v" + provenance + " - created by a newer version of this"
                   + " app; kept as it is";
        }
        return "v" + provenance + " - superseded by v" + SEEDING_CURRENT;
    }

    /**
     * What to tell the user, or null when there is nothing worth saying.
     *
     * Deliberately a recommendation and not an alarm: nothing here is evidence
     * that a key was ever compromised, only that a later build generates keys
     * from a pool it can measure. Carries no figure anyone could read as a
     * strength claim, and never names the key.
     */
    public static String seedingAdvice(int provenance)
    {
        if (!seedingNeedsReauth(provenance)) { return null; }
        return "This sign-in was created by an earlier version, before the app"
               + " measured how keys are seeded. It still works. When it suits"
               + " you, log out and sign in again to replace it.";
    }

    /** Best effort - see the note in Aes.wipe about what CLDC can promise. */
    public void wipe()
    {
        for (int i = 0; i < key.length; i++)
        {
            key[i] = 0;
        }
    }

    private static long leLong(byte[] b, int off)
    {
        long v = 0;
        for (int i = 7; i >= 0; i--)
        {
            v = (v << 8) | (b[off + i] & 0xffL);
        }
        return v;
    }
}
