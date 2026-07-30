package tg.crypto;

/**
 * SHA-256 (FIPS 180-4).
 *
 * MTProto 2.0 uses SHA-256 everywhere that matters: the msg_key derivation, the
 * AES key/IV schedule for every encrypted message, and the auth_key
 * fingerprint. It runs on every packet in both directions, so this is a hot
 * path on a 208 MHz CPU.
 *
 * Written for that constraint:
 *   - no allocation in update() or digest(); the block and schedule buffers are
 *     allocated once per instance and reused, so a long session does not churn
 *     the collector;
 *   - the message schedule is a single int[64] rather than a fresh array per
 *     block;
 *   - callers that hash repeatedly should keep one instance and reset() it.
 *
 * CLDC 1.1 has no java.security.MessageDigest, and Integer.rotateRight is a
 * Java 5 API that does not exist there either - hence the explicit shifts.
 */
public final class Sha256
{
    public static final int DIGEST_SIZE = 32;
    public static final int BLOCK_SIZE = 64;

    /** First 32 bits of the fractional parts of the cube roots of the first 64 primes. */
    private static final int[] K = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
        0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
        0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

    private final int[] h = new int[8];
    private final int[] w = new int[64];
    private final byte[] block = new byte[BLOCK_SIZE];

    private int blockLen;        // bytes currently buffered in `block`
    private long totalLen;       // total message length in bytes

    public Sha256()
    {
        reset();
    }

    public void reset()
    {
        // First 32 bits of the fractional parts of the square roots of the
        // first eight primes.
        h[0] = 0x6a09e667; h[1] = 0xbb67ae85;
        h[2] = 0x3c6ef372; h[3] = 0xa54ff53a;
        h[4] = 0x510e527f; h[5] = 0x9b05688c;
        h[6] = 0x1f83d9ab; h[7] = 0x5be0cd19;
        blockLen = 0;
        totalLen = 0;
    }

    public void update(byte b)
    {
        block[blockLen++] = b;
        totalLen++;
        if (blockLen == BLOCK_SIZE)
        {
            process(block, 0);
            blockLen = 0;
        }
    }

    public void update(byte[] data, int off, int len)
    {
        if (len <= 0) { return; }
        totalLen += len;

        // Top up a partially filled block first.
        if (blockLen > 0)
        {
            int need = BLOCK_SIZE - blockLen;
            if (len < need)
            {
                System.arraycopy(data, off, block, blockLen, len);
                blockLen += len;
                return;
            }
            System.arraycopy(data, off, block, blockLen, need);
            process(block, 0);
            blockLen = 0;
            off += need;
            len -= need;
        }

        // Then take whole blocks straight from the caller's array - no copy.
        while (len >= BLOCK_SIZE)
        {
            process(data, off);
            off += BLOCK_SIZE;
            len -= BLOCK_SIZE;
        }

        if (len > 0)
        {
            System.arraycopy(data, off, block, 0, len);
            blockLen = len;
        }
    }

    public void update(byte[] data)
    {
        update(data, 0, data.length);
    }

    /** Finalise into a fresh array and reset for reuse. */
    public byte[] digest()
    {
        byte[] out = new byte[DIGEST_SIZE];
        digest(out, 0);
        return out;
    }

    /** Finalise into a caller-supplied array and reset for reuse. */
    public void digest(byte[] out, int outOff)
    {
        long bitLen = totalLen << 3;

        // Padding: 0x80, then zeros, then the 64-bit big-endian bit length.
        update((byte) 0x80);
        while (blockLen != 56)
        {
            update((byte) 0);
        }

        // Written directly: update() would recurse into the padding loop.
        block[56] = (byte) (bitLen >>> 56);
        block[57] = (byte) (bitLen >>> 48);
        block[58] = (byte) (bitLen >>> 40);
        block[59] = (byte) (bitLen >>> 32);
        block[60] = (byte) (bitLen >>> 24);
        block[61] = (byte) (bitLen >>> 16);
        block[62] = (byte) (bitLen >>> 8);
        block[63] = (byte) bitLen;
        process(block, 0);

        for (int i = 0; i < 8; i++)
        {
            int v = h[i];
            out[outOff + i * 4]     = (byte) (v >>> 24);
            out[outOff + i * 4 + 1] = (byte) (v >>> 16);
            out[outOff + i * 4 + 2] = (byte) (v >>> 8);
            out[outOff + i * 4 + 3] = (byte) v;
        }
        reset();
    }

    // -------------------------------------------------------- convenience

    public static byte[] hash(byte[] data)
    {
        return hash(data, 0, data.length);
    }

    public static byte[] hash(byte[] data, int off, int len)
    {
        Sha256 d = new Sha256();
        d.update(data, off, len);
        return d.digest();
    }

    /**
     * Hash the concatenation without materialising it. MTProto derives keys
     * from things like SHA256(msg_key + auth_key_part), and building the joined
     * array first would allocate on every single message.
     */
    public static byte[] hash(byte[] a, byte[] b)
    {
        Sha256 d = new Sha256();
        d.update(a, 0, a.length);
        d.update(b, 0, b.length);
        return d.digest();
    }

    // ------------------------------------------------------------ internal

    private void process(byte[] data, int off)
    {
        int[] w = this.w;

        for (int i = 0; i < 16; i++)
        {
            int j = off + (i << 2);
            w[i] = ((data[j] & 0xff) << 24)
                 | ((data[j + 1] & 0xff) << 16)
                 | ((data[j + 2] & 0xff) << 8)
                 |  (data[j + 3] & 0xff);
        }

        for (int i = 16; i < 64; i++)
        {
            int x = w[i - 15];
            int s0 = ((x >>> 7) | (x << 25)) ^ ((x >>> 18) | (x << 14)) ^ (x >>> 3);
            int y = w[i - 2];
            int s1 = ((y >>> 17) | (y << 15)) ^ ((y >>> 19) | (y << 13)) ^ (y >>> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }

        int a = h[0], b = h[1], c = h[2], d = h[3];
        int e = h[4], f = h[5], g = h[6], hh = h[7];

        for (int i = 0; i < 64; i++)
        {
            int S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
            int ch = (e & f) ^ ((~e) & g);
            int t1 = hh + S1 + ch + K[i] + w[i];
            int S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
            int maj = (a & b) ^ (a & c) ^ (b & c);
            int t2 = S0 + maj;

            hh = g; g = f; f = e;
            e = d + t1;
            d = c; c = b; b = a;
            a = t1 + t2;
        }

        h[0] += a; h[1] += b; h[2] += c; h[3] += d;
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    }
}
