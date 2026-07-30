package tg.crypto;

/**
 * AES in Infinite Garble Extension mode - the mode MTProto uses for every
 * encrypted message and for server_DH_inner_data during the auth_key handshake.
 *
 * IGE is not one of the NIST modes and no mainstream crypto library exposes it,
 * which is exactly why it has to be implemented here rather than borrowed.
 * Definition, with the 32-byte IV split into two halves:
 *
 * <pre>
 *   c[-1] = iv[0..16]        previous ciphertext block
 *   m[-1] = iv[16..32]       previous plaintext block
 *
 *   encrypt:  c[i] = E(m[i] XOR c[i-1]) XOR m[i-1]
 *   decrypt:  m[i] = D(c[i] XOR m[i-1]) XOR c[i-1]
 * </pre>
 *
 * This matches OpenSSL's AES_ige_encrypt byte for byte, which is what Telegram's
 * own implementations are checked against.
 *
 * Length must be a multiple of 16. MTProto guarantees that by padding, so a
 * violation is a framing bug we want reported immediately rather than silently
 * truncated.
 *
 * All scratch space is per-instance and allocated once, because MTProto
 * encrypts and decrypts in place - the block being consumed and the block being
 * produced can share memory. Not thread safe.
 */
public final class AesIge
{
    public static final int BLOCK_SIZE = Aes.BLOCK_SIZE;
    public static final int IV_SIZE = 32;

    private final Aes aes;

    /** c[i-1] during encryption and decryption alike. */
    private final byte[] prevCipher = new byte[BLOCK_SIZE];

    /** m[i-1] during encryption and decryption alike. */
    private final byte[] prevPlain = new byte[BLOCK_SIZE];

    /** The current input block, copied out before `out` may overwrite `in`. */
    private final byte[] carry = new byte[BLOCK_SIZE];

    private final byte[] tmp = new byte[BLOCK_SIZE];

    /**
     * @param key 32 bytes for MTProto
     */
    public AesIge(byte[] key)
    {
        aes = new Aes(key);
    }

    public AesIge(byte[] key, int keyOff, int keyLen)
    {
        aes = new Aes(key, keyOff, keyLen);
    }

    /**
     * Swap in a new key without reallocating the schedule.
     *
     * MTProto derives a fresh AES key from the msg_key for every single message,
     * so this runs once per packet in each direction. Constructing a new AesIge
     * each time would allocate a key schedule plus four scratch buffers per
     * message - garbage a handset heap does not need.
     */
    public void rekey(byte[] key)
    {
        aes.rekey(key);
    }

    /**
     * @param iv 32 bytes, not modified
     */
    public void encrypt(byte[] iv, int ivOff,
                        byte[] in, int inOff,
                        byte[] out, int outOff, int len)
    {
        checkArgs(iv, ivOff, len);
        loadIv(iv, ivOff);

        for (int pos = 0; pos < len; pos += BLOCK_SIZE)
        {
            System.arraycopy(in, inOff + pos, carry, 0, BLOCK_SIZE);   // m[i]

            for (int i = 0; i < BLOCK_SIZE; i++)
            {
                tmp[i] = (byte) (carry[i] ^ prevCipher[i]);            // m[i] ^ c[i-1]
            }
            aes.encryptBlock(tmp, 0, tmp, 0);
            for (int i = 0; i < BLOCK_SIZE; i++)
            {
                tmp[i] ^= prevPlain[i];                                // ^ m[i-1] = c[i]
            }

            System.arraycopy(tmp, 0, out, outOff + pos, BLOCK_SIZE);
            System.arraycopy(tmp, 0, prevCipher, 0, BLOCK_SIZE);       // c[i-1] := c[i]
            System.arraycopy(carry, 0, prevPlain, 0, BLOCK_SIZE);      // m[i-1] := m[i]
        }
    }

    /**
     * @param iv 32 bytes, not modified
     */
    public void decrypt(byte[] iv, int ivOff,
                        byte[] in, int inOff,
                        byte[] out, int outOff, int len)
    {
        checkArgs(iv, ivOff, len);
        loadIv(iv, ivOff);

        for (int pos = 0; pos < len; pos += BLOCK_SIZE)
        {
            System.arraycopy(in, inOff + pos, carry, 0, BLOCK_SIZE);   // c[i]

            for (int i = 0; i < BLOCK_SIZE; i++)
            {
                tmp[i] = (byte) (carry[i] ^ prevPlain[i]);             // c[i] ^ m[i-1]
            }
            aes.decryptBlock(tmp, 0, tmp, 0);
            for (int i = 0; i < BLOCK_SIZE; i++)
            {
                tmp[i] ^= prevCipher[i];                               // ^ c[i-1] = m[i]
            }

            System.arraycopy(tmp, 0, out, outOff + pos, BLOCK_SIZE);
            System.arraycopy(tmp, 0, prevPlain, 0, BLOCK_SIZE);        // m[i-1] := m[i]
            System.arraycopy(carry, 0, prevCipher, 0, BLOCK_SIZE);     // c[i-1] := c[i]
        }
    }

    // Whole-array convenience wrappers.

    public byte[] encrypt(byte[] iv, byte[] data)
    {
        byte[] out = new byte[data.length];
        encrypt(iv, 0, data, 0, out, 0, data.length);
        return out;
    }

    public byte[] decrypt(byte[] iv, byte[] data)
    {
        byte[] out = new byte[data.length];
        decrypt(iv, 0, data, 0, out, 0, data.length);
        return out;
    }

    /**
     * Best-effort wipe of the key schedule and chaining state. CLDC offers no
     * guarantee the VM has not copied any of it, but leaving auth_key-derived
     * material live for the rest of the session is strictly worse.
     */
    public void wipe()
    {
        aes.wipe();
        for (int i = 0; i < BLOCK_SIZE; i++)
        {
            prevCipher[i] = 0;
            prevPlain[i] = 0;
            carry[i] = 0;
            tmp[i] = 0;
        }
    }

    // ------------------------------------------------------------ internal

    private void loadIv(byte[] iv, int ivOff)
    {
        System.arraycopy(iv, ivOff, prevCipher, 0, BLOCK_SIZE);
        System.arraycopy(iv, ivOff + BLOCK_SIZE, prevPlain, 0, BLOCK_SIZE);
    }

    private static void checkArgs(byte[] iv, int ivOff, int len)
    {
        if (iv == null || iv.length - ivOff < IV_SIZE)
        {
            throw new IllegalArgumentException("AES-IGE needs a 32-byte IV");
        }
        if (len < 0 || (len % BLOCK_SIZE) != 0)
        {
            throw new IllegalArgumentException(
                    "AES-IGE length must be a multiple of 16, got " + len);
        }
    }
}
