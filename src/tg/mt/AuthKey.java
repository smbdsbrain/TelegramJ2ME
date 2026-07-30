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
 */
public final class AuthKey
{
    public static final int KEY_SIZE = 256;

    private final byte[] key;
    private final long keyId;
    private final long auxHash;
    private final int dcId;
    private final boolean testEnvironment;

    public AuthKey(byte[] key, int dcId, boolean testEnvironment)
    {
        if (key == null || key.length != KEY_SIZE)
        {
            throw new IllegalArgumentException(
                    "auth_key must be " + KEY_SIZE + " bytes, got "
                    + (key == null ? -1 : key.length));
        }
        this.key = key;
        this.dcId = dcId;
        this.testEnvironment = testEnvironment;

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

    /** True when this key can be used against the given data centre. */
    public boolean matches(int otherDcId, boolean otherTest)
    {
        return dcId == otherDcId && testEnvironment == otherTest;
    }

    public String describe()
    {
        return "auth_key dc" + dcId + (testEnvironment ? " test" : " prod")
               + " id=" + Long.toString(keyId)
               + " sha1=" + Hex.encode(Sha1.hash(key), 0, 4);
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
