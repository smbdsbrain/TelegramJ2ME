package tg.mt;

import tg.crypto.AesIge;
import tg.crypto.Rng;
import tg.crypto.Sha1;
import tg.crypto.Sha256;
import tg.crypto.bigint.BigInteger;
import tg.io.Hex;
import tg.tl.TlWriter;

/**
 * A Telegram server RSA public key, and the RSA_PAD encryption the auth_key
 * handshake requires.
 *
 * <h3>Fingerprints are computed, not stored</h3>
 * resPQ names the keys the server will accept by fingerprint - the low 64 bits
 * of SHA1 over the bare type {@code rsa_public_key n:string e:string}. Deriving
 * it here from the modulus means a transcription error in
 * {@link ServerKeys} shows up as "no matching key" rather than as a request the
 * server silently rejects.
 *
 * <h3>RSA_PAD, not textbook RSA</h3>
 * Telegram replaced the old SHA1-prefix padding with a variant of OAEP+. The
 * scheme, from core.telegram.org/mtproto/auth_key section 4.1:
 *
 * <pre>
 *   data_with_padding := data + random, padded to exactly 192 bytes
 *   data_pad_reversed := reverse(data_with_padding)
 *   temp_key          := 32 random bytes
 *   data_with_hash    := data_pad_reversed + SHA256(temp_key + data_with_padding)
 *   aes_encrypted     := AES256-IGE(data_with_hash, temp_key, zero IV)
 *   temp_key_xor      := temp_key XOR SHA256(aes_encrypted)
 *   key_aes_encrypted := temp_key_xor + aes_encrypted        (256 bytes)
 *   if key_aes_encrypted >= modulus: retry with a new temp_key
 *   encrypted_data    := key_aes_encrypted ^ e mod n
 * </pre>
 *
 * The retry when the padded block exceeds the modulus is not optional - without
 * it the value would silently reduce mod n and the server could not decrypt.
 */
public final class RsaKey
{
    /** RSA_PAD works on exactly this much data before encryption. */
    private static final int PAD_SIZE = 192;

    /** So data + at least some random padding always fits in PAD_SIZE. */
    private static final int MAX_DATA = 144;

    private static final int KEY_SIZE = 256;

    private final BigInteger modulus;
    private final BigInteger exponent;
    private final long fingerprint;

    public RsaKey(String modulusHex, String exponentHex)
    {
        byte[] n = Hex.decode(modulusHex);
        byte[] e = Hex.decode(exponentHex);
        modulus = new BigInteger(1, n);
        exponent = new BigInteger(1, e);
        fingerprint = computeFingerprint(n, e);
    }

    public long fingerprint()
    {
        return fingerprint;
    }

    public BigInteger modulus()
    {
        return modulus;
    }

    /**
     * The fingerprint resPQ uses: low 64 bits of SHA1 over the TL-serialised
     * bare {@code rsa_public_key n:string e:string}, read as a little-endian
     * signed int64.
     */
    private static long computeFingerprint(byte[] n, byte[] e)
    {
        TlWriter w = new TlWriter(n.length + e.length + 8);
        w.writeBytes(n);
        w.writeBytes(e);
        byte[] sha = Sha1.hash(w.toByteArray(), 0, w.size());

        long v = 0;
        // Little-endian over the last 8 bytes.
        for (int i = 7; i >= 0; i--)
        {
            v = (v << 8) | (sha[sha.length - 8 + i] & 0xffL);
        }
        return v;
    }

    /**
     * RSA_PAD(data, this key).
     *
     * @param data the serialised p_q_inner_data_dc, at most 144 bytes
     * @param rng  source of the padding and the temporary key; its quality
     *             matters here as much as anywhere in the protocol
     * @return exactly 256 bytes
     */
    public byte[] encrypt(byte[] data, Rng rng)
    {
        if (data.length > MAX_DATA)
        {
            throw new IllegalArgumentException(
                    "RSA_PAD accepts at most " + MAX_DATA + " bytes, got " + data.length);
        }

        byte[] dataWithPadding = new byte[PAD_SIZE];
        System.arraycopy(data, 0, dataWithPadding, 0, data.length);
        rng.nextBytes(dataWithPadding, data.length, PAD_SIZE - data.length);

        byte[] reversed = new byte[PAD_SIZE];
        for (int i = 0; i < PAD_SIZE; i++)
        {
            reversed[i] = dataWithPadding[PAD_SIZE - 1 - i];
        }

        byte[] tempKey = new byte[32];
        byte[] dataWithHash = new byte[PAD_SIZE + 32];
        byte[] keyAesEncrypted = new byte[KEY_SIZE];
        byte[] zeroIv = new byte[32];

        // Bounded so a broken RNG cannot spin here forever. Each attempt has a
        // well under 50% chance of exceeding the modulus, so 64 tries failing
        // means something is badly wrong rather than unlucky.
        for (int attempt = 0; attempt < 64; attempt++)
        {
            rng.nextBytes(tempKey, 0, 32);

            System.arraycopy(reversed, 0, dataWithHash, 0, PAD_SIZE);
            Sha256 sha = new Sha256();
            sha.update(tempKey, 0, 32);
            sha.update(dataWithPadding, 0, PAD_SIZE);
            sha.digest(dataWithHash, PAD_SIZE);

            AesIge ige = new AesIge(tempKey);
            byte[] aesEncrypted = new byte[dataWithHash.length];
            ige.encrypt(zeroIv, 0, dataWithHash, 0, aesEncrypted, 0, dataWithHash.length);
            ige.wipe();

            byte[] hashOfCipher = Sha256.hash(aesEncrypted);
            for (int i = 0; i < 32; i++)
            {
                keyAesEncrypted[i] = (byte) (tempKey[i] ^ hashOfCipher[i]);
            }
            System.arraycopy(aesEncrypted, 0, keyAesEncrypted, 32, aesEncrypted.length);

            BigInteger value = new BigInteger(1, keyAesEncrypted);
            if (value.compareTo(modulus) < 0)
            {
                byte[] out = toFixed(value.modPow(exponent, modulus), KEY_SIZE);
                wipe(tempKey);
                wipe(dataWithPadding);
                wipe(dataWithHash);
                return out;
            }
            // Otherwise the padded block is not reducible mod n; try again with
            // a fresh temp_key, exactly as the specification requires.
        }
        throw new IllegalStateException("RSA_PAD failed to produce a value below the modulus");
    }

    /** Big-endian, left-padded with zeros - MTProto wants a fixed width. */
    static byte[] toFixed(BigInteger v, int size)
    {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[size];

        int from = 0;
        int len = raw.length;
        // toByteArray() prefixes a zero byte when the top bit is set.
        while (len > 0 && raw[from] == 0)
        {
            from++;
            len--;
        }
        if (len > size)
        {
            throw new IllegalArgumentException("value needs " + len
                                               + " bytes, only " + size + " available");
        }
        System.arraycopy(raw, from, out, size - len, len);
        return out;
    }

    private static void wipe(byte[] b)
    {
        for (int i = 0; i < b.length; i++) { b[i] = 0; }
    }

    /** Keys for the environment this build targets. */
    public static RsaKey[] forThisBuild()
    {
        String[] moduli = ServerKeys.moduli();
        RsaKey[] keys = new RsaKey[moduli.length];
        for (int i = 0; i < moduli.length; i++)
        {
            keys[i] = new RsaKey(moduli[i], ServerKeys.EXPONENT);
        }
        return keys;
    }

    /**
     * Pick the key the server named. Returns null when none match, which means
     * either the key list is stale or the build is pointed at the wrong
     * environment - production and test data centres use different keys.
     */
    public static RsaKey select(RsaKey[] keys, long[] fingerprints)
    {
        for (int i = 0; i < fingerprints.length; i++)
        {
            for (int k = 0; k < keys.length; k++)
            {
                if (keys[k].fingerprint() == fingerprints[i])
                {
                    return keys[k];
                }
            }
        }
        return null;
    }
}
