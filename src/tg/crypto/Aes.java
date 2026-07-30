package tg.crypto;

/**
 * AES block cipher (FIPS-197), 128/192/256-bit keys.
 *
 * MTProto uses AES-256 in IGE mode; see {@link AesIge}. This class is only the
 * block function and the key schedule.
 *
 * Two deliberate choices, both driven by constrained Java ME hardware:
 *
 * 1. **Byte-oriented, not T-table.** The classic four 1 KB T-table variant is
 *    roughly two to four times faster but costs ~8 KB of tables per process. On
 *    a handset whose heap we have not yet measured, and where the dominant cost
 *    of a message is the GPRS round trip rather than the cipher, correctness and
 *    footprint win. The substitution and GF multiplication tables here total
 *    about 2 KB.
 *
 * 2. **Tables are computed at class init, not stored as literals.** Generating
 *    them from the GF(2^8) log/antilog tables is ~40 lines; embedding them would
 *    add ~2 KB of constant pool to an application intended for runtimes with
 *    strict JAR-size limits.
 *
 * If the device benchmark says the cipher is the bottleneck, the T-table
 * variant is the known remedy - measure first.
 *
 * No allocation happens per block: the state buffer belongs to the instance.
 * An instance is therefore NOT safe to share between threads.
 */
public final class Aes
{
    public static final int BLOCK_SIZE = 16;

    private static final byte[] SBOX = new byte[256];
    private static final byte[] INV_SBOX = new byte[256];

    // GF(2^8) multiplication tables for the MixColumns coefficients.
    private static final byte[] MUL2 = new byte[256];
    private static final byte[] MUL3 = new byte[256];
    private static final byte[] MUL9 = new byte[256];
    private static final byte[] MUL11 = new byte[256];
    private static final byte[] MUL13 = new byte[256];
    private static final byte[] MUL14 = new byte[256];

    private static final int[] RCON = {
        0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40,
        0x80, 0x1b, 0x36, 0x6c, 0xd8, 0xab, 0x4d
    };

    static
    {
        // Log/antilog tables over GF(2^8) with the AES polynomial 0x11b and
        // generator 3. Everything else is derived from these.
        int[] alog = new int[256];
        int[] log = new int[256];
        int x = 1;
        for (int i = 0; i < 255; i++)
        {
            alog[i] = x;
            log[x] = i;
            x ^= xtime(x);                 // multiply by 3 == x ^ (x * 2)
        }

        // S-box: multiplicative inverse followed by the AES affine transform.
        // The "% 255" is load-bearing: log[1] is 0, so without it the index is
        // 255, which is one past the end of the antilog table. That would make
        // inv(1) come out as 0, collide SBOX[1] with SBOX[0], and leave the
        // S-box a non-permutation - encryption still passes many test vectors,
        // decryption does not.
        SBOX[0] = 0x63;
        for (int i = 1; i < 256; i++)
        {
            int inv = alog[(255 - log[i]) % 255];
            int s = inv;
            for (int r = 0; r < 4; r++)
            {
                inv = ((inv << 1) | (inv >>> 7)) & 0xff;
                s ^= inv;
            }
            s ^= 0x63;
            SBOX[i] = (byte) s;
        }
        for (int i = 0; i < 256; i++)
        {
            INV_SBOX[SBOX[i] & 0xff] = (byte) i;
        }

        for (int i = 0; i < 256; i++)
        {
            MUL2[i] = (byte) mul(i, 2, log, alog);
            MUL3[i] = (byte) mul(i, 3, log, alog);
            MUL9[i] = (byte) mul(i, 9, log, alog);
            MUL11[i] = (byte) mul(i, 11, log, alog);
            MUL13[i] = (byte) mul(i, 13, log, alog);
            MUL14[i] = (byte) mul(i, 14, log, alog);
        }
    }

    private final byte[] roundKeys;      // (rounds + 1) * 16 bytes
    private final int rounds;
    private final byte[] state = new byte[BLOCK_SIZE];

    /**
     * @param key 16, 24 or 32 bytes. MTProto always uses 32.
     */
    public Aes(byte[] key)
    {
        this(key, 0, key.length);
    }

    public Aes(byte[] key, int off, int len)
    {
        if (len != 16 && len != 24 && len != 32)
        {
            throw new IllegalArgumentException("AES key must be 16, 24 or 32 bytes, got " + len);
        }
        int nk = len / 4;
        rounds = nk + 6;
        roundKeys = new byte[(rounds + 1) * BLOCK_SIZE];
        expandKey(key, off, nk);
    }

    public int getRounds()
    {
        return rounds;
    }

    /**
     * Replace the key, reusing the existing schedule array.
     *
     * MTProto derives a fresh AES key per message from the msg_key, so this is
     * called once per packet in each direction. Allocating a new Aes each time
     * would churn a 240-byte schedule plus an object header on every message -
     * avoidable garbage on a handset heap.
     *
     * The key length must match the one this instance was built with, since the
     * round count and schedule size depend on it.
     */
    public void rekey(byte[] key, int off, int len)
    {
        // rounds == keyWords + 6, so the required length is fixed per instance.
        int requiredLen = (rounds - 6) * 4;
        if (len != requiredLen)
        {
            throw new IllegalArgumentException(
                    "rekey needs a " + requiredLen + "-byte key, got " + len);
        }
        expandKey(key, off, len / 4);
    }

    public void rekey(byte[] key)
    {
        rekey(key, 0, key.length);
    }

    /** Encrypt one 16-byte block. {@code in} and {@code out} may be the same array. */
    public void encryptBlock(byte[] in, int inOff, byte[] out, int outOff)
    {
        byte[] s = state;
        System.arraycopy(in, inOff, s, 0, BLOCK_SIZE);

        addRoundKey(s, 0);
        for (int r = 1; r < rounds; r++)
        {
            subBytes(s, SBOX);
            shiftRows(s);
            mixColumns(s);
            addRoundKey(s, r);
        }
        subBytes(s, SBOX);
        shiftRows(s);
        addRoundKey(s, rounds);

        System.arraycopy(s, 0, out, outOff, BLOCK_SIZE);
    }

    /** Decrypt one 16-byte block. {@code in} and {@code out} may be the same array. */
    public void decryptBlock(byte[] in, int inOff, byte[] out, int outOff)
    {
        byte[] s = state;
        System.arraycopy(in, inOff, s, 0, BLOCK_SIZE);

        addRoundKey(s, rounds);
        for (int r = rounds - 1; r > 0; r--)
        {
            invShiftRows(s);
            subBytes(s, INV_SBOX);
            addRoundKey(s, r);
            invMixColumns(s);
        }
        invShiftRows(s);
        subBytes(s, INV_SBOX);
        addRoundKey(s, 0);

        System.arraycopy(s, 0, out, outOff, BLOCK_SIZE);
    }

    /**
     * Best-effort wipe of the key schedule. CLDC gives no guarantee the VM did
     * not copy this elsewhere, but leaving an auth_key-derived schedule sitting
     * in a live object for the rest of the session is worse.
     */
    public void wipe()
    {
        for (int i = 0; i < roundKeys.length; i++) { roundKeys[i] = 0; }
        for (int i = 0; i < state.length; i++) { state[i] = 0; }
    }

    // ------------------------------------------------------------ internal

    private void expandKey(byte[] key, int off, int nk)
    {
        int totalWords = (rounds + 1) * 4;
        System.arraycopy(key, off, roundKeys, 0, nk * 4);

        byte[] temp = new byte[4];
        for (int i = nk; i < totalWords; i++)
        {
            int prev = (i - 1) * 4;
            temp[0] = roundKeys[prev];
            temp[1] = roundKeys[prev + 1];
            temp[2] = roundKeys[prev + 2];
            temp[3] = roundKeys[prev + 3];

            if (i % nk == 0)
            {
                // RotWord, SubWord, then XOR the round constant.
                byte t = temp[0];
                temp[0] = SBOX[temp[1] & 0xff];
                temp[1] = SBOX[temp[2] & 0xff];
                temp[2] = SBOX[temp[3] & 0xff];
                temp[3] = SBOX[t & 0xff];
                temp[0] ^= (byte) RCON[i / nk];
            }
            else if (nk > 6 && i % nk == 4)
            {
                // AES-256 only: an extra SubWord every eighth word.
                temp[0] = SBOX[temp[0] & 0xff];
                temp[1] = SBOX[temp[1] & 0xff];
                temp[2] = SBOX[temp[2] & 0xff];
                temp[3] = SBOX[temp[3] & 0xff];
            }

            int back = (i - nk) * 4;
            int cur = i * 4;
            roundKeys[cur]     = (byte) (roundKeys[back] ^ temp[0]);
            roundKeys[cur + 1] = (byte) (roundKeys[back + 1] ^ temp[1]);
            roundKeys[cur + 2] = (byte) (roundKeys[back + 2] ^ temp[2]);
            roundKeys[cur + 3] = (byte) (roundKeys[back + 3] ^ temp[3]);
        }
    }

    private void addRoundKey(byte[] s, int round)
    {
        int k = round * BLOCK_SIZE;
        for (int i = 0; i < BLOCK_SIZE; i++)
        {
            s[i] ^= roundKeys[k + i];
        }
    }

    private static void subBytes(byte[] s, byte[] box)
    {
        for (int i = 0; i < BLOCK_SIZE; i++)
        {
            s[i] = box[s[i] & 0xff];
        }
    }

    /**
     * The state is column-major: byte i is row (i &amp; 3), column (i &gt;&gt; 2).
     * Row r rotates left by r.
     */
    private static void shiftRows(byte[] s)
    {
        byte t;

        // row 1: left by 1
        t = s[1];
        s[1] = s[5]; s[5] = s[9]; s[9] = s[13]; s[13] = t;

        // row 2: left by 2
        t = s[2];  s[2] = s[10];  s[10] = t;
        t = s[6];  s[6] = s[14];  s[14] = t;

        // row 3: left by 3 == right by 1
        t = s[15];
        s[15] = s[11]; s[11] = s[7]; s[7] = s[3]; s[3] = t;
    }

    private static void invShiftRows(byte[] s)
    {
        byte t;

        // row 1: right by 1
        t = s[13];
        s[13] = s[9]; s[9] = s[5]; s[5] = s[1]; s[1] = t;

        // row 2: right by 2 == left by 2
        t = s[2];  s[2] = s[10];  s[10] = t;
        t = s[6];  s[6] = s[14];  s[14] = t;

        // row 3: right by 3 == left by 1
        t = s[3];
        s[3] = s[7]; s[7] = s[11]; s[11] = s[15]; s[15] = t;
    }

    private static void mixColumns(byte[] s)
    {
        for (int c = 0; c < 4; c++)
        {
            int i = c << 2;
            int a0 = s[i] & 0xff, a1 = s[i + 1] & 0xff;
            int a2 = s[i + 2] & 0xff, a3 = s[i + 3] & 0xff;

            s[i]     = (byte) (MUL2[a0] ^ MUL3[a1] ^ a2 ^ a3);
            s[i + 1] = (byte) (a0 ^ MUL2[a1] ^ MUL3[a2] ^ a3);
            s[i + 2] = (byte) (a0 ^ a1 ^ MUL2[a2] ^ MUL3[a3]);
            s[i + 3] = (byte) (MUL3[a0] ^ a1 ^ a2 ^ MUL2[a3]);
        }
    }

    private static void invMixColumns(byte[] s)
    {
        for (int c = 0; c < 4; c++)
        {
            int i = c << 2;
            int a0 = s[i] & 0xff, a1 = s[i + 1] & 0xff;
            int a2 = s[i + 2] & 0xff, a3 = s[i + 3] & 0xff;

            s[i]     = (byte) (MUL14[a0] ^ MUL11[a1] ^ MUL13[a2] ^ MUL9[a3]);
            s[i + 1] = (byte) (MUL9[a0] ^ MUL14[a1] ^ MUL11[a2] ^ MUL13[a3]);
            s[i + 2] = (byte) (MUL13[a0] ^ MUL9[a1] ^ MUL14[a2] ^ MUL11[a3]);
            s[i + 3] = (byte) (MUL11[a0] ^ MUL13[a1] ^ MUL9[a2] ^ MUL14[a3]);
        }
    }

    /** Multiply by 2 in GF(2^8) modulo the AES polynomial 0x11b. */
    private static int xtime(int a)
    {
        a <<= 1;
        if ((a & 0x100) != 0) { a = (a ^ 0x11b) & 0xff; }
        return a & 0xff;
    }

    private static int mul(int a, int b, int[] log, int[] alog)
    {
        if (a == 0 || b == 0) { return 0; }
        return alog[(log[a] + log[b]) % 255];
    }
}
