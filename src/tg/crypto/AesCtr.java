package tg.crypto;

/**
 * Streaming AES-CTR using the OpenSSL/MTProxy counter convention.
 *
 * The 128-bit counter is incremented as a big-endian integer after each block.
 * State is deliberately retained between calls: obfuscated2 uses one CTR stream
 * for the whole lifetime of a TCP connection, including the 64-byte greeting.
 */
public final class AesCtr
{
    private final Aes aes;
    private final byte[] counter = new byte[Aes.BLOCK_SIZE];
    private final byte[] stream = new byte[Aes.BLOCK_SIZE];
    private int available;

    public AesCtr(byte[] key, byte[] iv)
    {
        if (key == null || key.length != 32)
        {
            throw new IllegalArgumentException("AES-CTR needs a 32-byte key");
        }
        if (iv == null || iv.length != Aes.BLOCK_SIZE)
        {
            throw new IllegalArgumentException("AES-CTR needs a 16-byte IV");
        }
        aes = new Aes(key);
        System.arraycopy(iv, 0, counter, 0, counter.length);
    }

    public void crypt(byte[] in, int inOff, byte[] out, int outOff, int len)
    {
        if (in == null || out == null || inOff < 0 || outOff < 0 || len < 0
                || inOff + len > in.length || outOff + len > out.length)
        {
            throw new IllegalArgumentException("AES-CTR slice outside buffer");
        }

        while (len > 0)
        {
            if (available == 0)
            {
                aes.encryptBlock(counter, 0, stream, 0);
                incrementCounter();
                available = Aes.BLOCK_SIZE;
            }
            int used = Aes.BLOCK_SIZE - available;
            int n = available < len ? available : len;
            for (int i = 0; i < n; i++)
            {
                out[outOff + i] = (byte) (in[inOff + i] ^ stream[used + i]);
                stream[used + i] = 0;
            }
            available -= n;
            inOff += n;
            outOff += n;
            len -= n;
        }
    }

    public void crypt(byte[] data, int off, int len)
    {
        crypt(data, off, data, off, len);
    }

    public void wipe()
    {
        aes.wipe();
        for (int i = 0; i < counter.length; i++)
        {
            counter[i] = 0;
            stream[i] = 0;
        }
        available = 0;
    }

    private void incrementCounter()
    {
        for (int i = counter.length - 1; i >= 0; i--)
        {
            counter[i]++;
            if (counter[i] != 0) { break; }
        }
    }
}
