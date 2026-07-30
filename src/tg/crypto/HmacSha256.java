package tg.crypto;

/** Allocation-bounded HMAC-SHA256 for MTProxy FakeTLS authentication. */
public final class HmacSha256
{
    private HmacSha256() { }

    public static byte[] compute(byte[] key, byte[] data)
    {
        return compute(key, data, null);
    }

    public static byte[] compute(byte[] key, byte[] first, byte[] second)
    {
        byte[] normalized = key;
        if (normalized.length > Sha256.BLOCK_SIZE) { normalized = Sha256.hash(normalized); }
        byte[] ipad = new byte[Sha256.BLOCK_SIZE];
        byte[] opad = new byte[Sha256.BLOCK_SIZE];
        for (int i = 0; i < ipad.length; i++)
        {
            byte k = i < normalized.length ? normalized[i] : 0;
            ipad[i] = (byte) (k ^ 0x36);
            opad[i] = (byte) (k ^ 0x5c);
        }
        Sha256 sha = new Sha256();
        sha.update(ipad);
        sha.update(first);
        if (second != null) { sha.update(second); }
        byte[] inner = sha.digest();
        sha.reset();
        sha.update(opad);
        sha.update(inner);
        byte[] out = sha.digest();
        wipe(ipad); wipe(opad); wipe(inner);
        return out;
    }

    private static void wipe(byte[] b)
    {
        for (int i = 0; i < b.length; i++) { b[i] = 0; }
    }
}
