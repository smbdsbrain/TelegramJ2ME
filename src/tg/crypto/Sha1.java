package tg.crypto;

/**
 * SHA-1 (FIPS 180-4).
 *
 * Still required by MTProto even though the message layer moved to SHA-256:
 * the auth_key handshake uses SHA-1 for the RSA-encrypted p_q_inner_data, for
 * server_DH_inner_data verification, and for the new_nonce hashes in the
 * dh_gen_ok exchange. It is not used to protect message contents.
 *
 * Same shape and same no-allocation discipline as {@link Sha256}.
 */
public final class Sha1
{
    public static final int DIGEST_SIZE = 20;
    public static final int BLOCK_SIZE = 64;

    private final int[] h = new int[5];
    private final int[] w = new int[80];
    private final byte[] block = new byte[BLOCK_SIZE];

    private int blockLen;
    private long totalLen;

    public Sha1()
    {
        reset();
    }

    public void reset()
    {
        h[0] = 0x67452301;
        h[1] = 0xefcdab89;
        h[2] = 0x98badcfe;
        h[3] = 0x10325476;
        h[4] = 0xc3d2e1f0;
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

    public byte[] digest()
    {
        byte[] out = new byte[DIGEST_SIZE];
        digest(out, 0);
        return out;
    }

    public void digest(byte[] out, int outOff)
    {
        long bitLen = totalLen << 3;

        update((byte) 0x80);
        while (blockLen != 56)
        {
            update((byte) 0);
        }

        block[56] = (byte) (bitLen >>> 56);
        block[57] = (byte) (bitLen >>> 48);
        block[58] = (byte) (bitLen >>> 40);
        block[59] = (byte) (bitLen >>> 32);
        block[60] = (byte) (bitLen >>> 24);
        block[61] = (byte) (bitLen >>> 16);
        block[62] = (byte) (bitLen >>> 8);
        block[63] = (byte) bitLen;
        process(block, 0);

        for (int i = 0; i < 5; i++)
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
        Sha1 d = new Sha1();
        d.update(data, off, len);
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
        for (int i = 16; i < 80; i++)
        {
            int x = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16];
            w[i] = (x << 1) | (x >>> 31);
        }

        int a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];

        for (int i = 0; i < 80; i++)
        {
            int f;
            int k;
            if (i < 20)
            {
                f = (b & c) | ((~b) & d);
                k = 0x5a827999;
            }
            else if (i < 40)
            {
                f = b ^ c ^ d;
                k = 0x6ed9eba1;
            }
            else if (i < 60)
            {
                f = (b & c) | (b & d) | (c & d);
                k = 0x8f1bbcdc;
            }
            else
            {
                f = b ^ c ^ d;
                k = 0xca62c1d6;
            }

            int temp = ((a << 5) | (a >>> 27)) + f + e + k + w[i];
            e = d;
            d = c;
            c = (b << 30) | (b >>> 2);
            b = a;
            a = temp;
        }

        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e;
    }
}
