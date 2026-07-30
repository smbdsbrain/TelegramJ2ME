package tg.io;

import java.io.IOException;

import tg.crypto.AesCtr;
import tg.crypto.Rng;
import tg.crypto.Sha256;
import tg.mt.ProxySecret;

/**
 * obfuscated2 stream decorator used for direct connections and MTProxy.
 */
public final class ObfuscatedTransport implements Transport
{
    public static final int PROTOCOL_ABRIDGED = 0xefefefef;
    public static final int PROTOCOL_INTERMEDIATE = 0xeeeeeeee;
    public static final int PROTOCOL_PADDED_INTERMEDIATE = 0xdddddddd;

    private final Transport delegate;
    private final Rng rng;
    private final int protocol;
    private final int proxyDc;
    private final ProxySecret secret;

    private AesCtr encrypt;
    private AesCtr decrypt;

    public ObfuscatedTransport(Transport delegate, Rng rng, int protocol,
                               int proxyDc, ProxySecret secret)
    {
        this.delegate = delegate;
        this.rng = rng;
        this.protocol = protocol;
        this.proxyDc = proxyDc;
        this.secret = secret;
    }

    public void connect(String host, int port, int timeoutMs) throws IOException
    {
        close();
        delegate.connect(host, port, timeoutMs);
        byte[] header = createHeader();
        delegate.write(header, 0, header.length);
        delegate.flush();
    }

    public int read(byte[] buf, int off, int len) throws IOException
    {
        int n = delegate.read(buf, off, len);
        if (n > 0) { decrypt.crypt(buf, off, n); }
        return n;
    }

    public void readFully(byte[] buf, int off, int len) throws IOException
    {
        int got = 0;
        while (got < len)
        {
            int n = read(buf, off + got, len - got);
            if (n < 0) { throw new IOException("eof after " + got + "/" + len); }
            got += n;
        }
    }

    public void write(byte[] buf, int off, int len) throws IOException
    {
        byte[] encrypted = new byte[len];
        encrypt.crypt(buf, off, encrypted, 0, len);
        delegate.write(encrypted, 0, encrypted.length);
    }

    public void flush() throws IOException { delegate.flush(); }
    public boolean isConnected() { return delegate.isConnected(); }
    public long bytesRead() { return delegate.bytesRead(); }
    public long bytesWritten() { return delegate.bytesWritten(); }

    public void close()
    {
        delegate.close();
        if (encrypt != null) { encrypt.wipe(); encrypt = null; }
        if (decrypt != null) { decrypt.wipe(); decrypt = null; }
    }

    private byte[] createHeader()
    {
        byte[] plain = new byte[64];
        do
        {
            rng.nextBytes(plain);
        }
        while (forbidden(plain));

        writeIntLe(plain, 56, protocol);
        if (proxyDc != 0)
        {
            plain[60] = (byte) proxyDc;
            plain[61] = (byte) (proxyDc >>> 8);
        }

        byte[] reversed = new byte[64];
        for (int i = 0; i < 64; i++) { reversed[i] = plain[63 - i]; }

        byte[] encKey = slice(plain, 8, 32);
        byte[] encIv = slice(plain, 40, 16);
        byte[] decKey = slice(reversed, 8, 32);
        byte[] decIv = slice(reversed, 40, 16);
        if (secret != null)
        {
            encKey = Sha256.hash(encKey, secret.key());
            decKey = Sha256.hash(decKey, secret.key());
        }
        encrypt = new AesCtr(encKey, encIv);
        decrypt = new AesCtr(decKey, decIv);

        byte[] encryptedHeader = new byte[64];
        encrypt.crypt(plain, 0, encryptedHeader, 0, 64);
        System.arraycopy(encryptedHeader, 56, plain, 56, 8);
        return plain;
    }

    static boolean forbidden(byte[] h)
    {
        int first = readIntLe(h, 0);
        int second = readIntLe(h, 4);
        return (h[0] & 0xff) == 0xef || second == 0
                || first == 0x44414548 || first == 0x54534f50
                || first == 0x20544547 || first == 0x4954504f
                || first == 0x02010316 || first == 0xdddddddd
                || first == 0xeeeeeeee;
    }

    private static byte[] slice(byte[] in, int off, int len)
    {
        byte[] out = new byte[len];
        System.arraycopy(in, off, out, 0, len);
        return out;
    }

    private static int readIntLe(byte[] b, int off)
    {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static void writeIntLe(byte[] b, int off, int v)
    {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }
}
