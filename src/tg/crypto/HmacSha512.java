package tg.crypto;

/** Reusable HMAC-SHA512 with no per-iteration allocation. */
public final class HmacSha512
{
    private final byte[] ipad = new byte[Sha512.BLOCK_SIZE];
    private final byte[] opad = new byte[Sha512.BLOCK_SIZE];
    private final byte[] inner = new byte[Sha512.DIGEST_SIZE];
    private final Sha512 sha = new Sha512();

    public HmacSha512(byte[] key)
    {
        byte[] normalized = key.length > Sha512.BLOCK_SIZE ? Sha512.hash(key) : key;
        for (int i = 0; i < ipad.length; i++)
        {
            byte k = i < normalized.length ? normalized[i] : 0;
            ipad[i] = (byte)(k ^ 0x36); opad[i] = (byte)(k ^ 0x5c);
        }
    }

    public void compute(byte[] data, int off, int len, byte[] out, int outOff)
    {
        sha.reset(); sha.update(ipad); sha.update(data, off, len); sha.digest(inner, 0);
        sha.reset(); sha.update(opad); sha.update(inner); sha.digest(out, outOff);
    }

    public static byte[] compute(byte[] key, byte[] data)
    {
        byte[] out = new byte[Sha512.DIGEST_SIZE];
        new HmacSha512(key).compute(data, 0, data.length, out, 0);
        return out;
    }
}
