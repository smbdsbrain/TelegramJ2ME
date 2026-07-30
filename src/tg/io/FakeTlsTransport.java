package tg.io;

import java.io.IOException;

import tg.crypto.HmacSha256;
import tg.crypto.Rng;
import tg.crypto.X25519;

/**
 * FakeTLS carrier for 0xee MTProxy secrets.
 *
 * It authenticates a TLS-1.3-looking ClientHello, validates the proxy response,
 * then carries obfuscated2 bytes inside TLS application-data records.
 */
public final class FakeTlsTransport implements Transport
{
    private static final int MAX_RECORD = 16384;

    /** Above this a record buffer is used once and dropped rather than retained. */
    private static final int MAX_REUSED_RECORD = 4096;

    private final Transport delegate;
    private final Rng rng;
    private final byte[] secret;
    private final String domain;

    private byte[] readBuffer = new byte[0];
    private byte[] writeRecord = new byte[0];
    private int readAt;
    private boolean firstWrite;

    public FakeTlsTransport(Transport delegate, Rng rng, byte[] secret, String domain)
    {
        if (secret == null || secret.length != 16 || domain == null || domain.length() == 0)
        {
            throw new IllegalArgumentException("FakeTLS needs a 16-byte secret and domain");
        }
        this.delegate = delegate;
        this.rng = rng;
        this.secret = new byte[16];
        System.arraycopy(secret, 0, this.secret, 0, 16);
        this.domain = domain;
    }

    public void connect(String host, int port, int timeoutMs) throws IOException
    {
        close();
        delegate.connect(host, port, timeoutMs);
        byte[] hello = buildHello();
        byte[] clientRandom = new byte[32];
        System.arraycopy(hello, 11, clientRandom, 0, 32);
        delegate.write(hello, 0, hello.length);
        delegate.flush();
        validateHelloResponse(clientRandom);
        firstWrite = true;
    }

    public int read(byte[] buf, int off, int len) throws IOException
    {
        if (readAt >= readBuffer.length) { readApplicationRecord(); }
        int n = Math.min(len, readBuffer.length - readAt);
        System.arraycopy(readBuffer, readAt, buf, off, n);
        readAt += n;
        return n;
    }

    public void readFully(byte[] buf, int off, int len) throws IOException
    {
        int got = 0;
        while (got < len)
        {
            int n = read(buf, off + got, len - got);
            if (n < 0) { throw new IOException("FakeTLS eof"); }
            got += n;
        }
    }

    public void write(byte[] buf, int off, int len) throws IOException
    {
        while (len > 0)
        {
            int n = Math.min(len, MAX_RECORD);
            if (firstWrite)
            {
                byte[] ccs = { 0x14, 0x03, 0x03, 0x00, 0x01, 0x01 };
                delegate.write(ccs, 0, ccs.length);
                firstWrite = false;
            }
            // Header and body go out together. The socket underneath is
            // unbuffered on the device, so writing them separately put a
            // 5-byte TCP segment ahead of every record.
            byte[] record = writeRecord;
            if (record.length < 5 + n)
            {
                record = new byte[5 + n];
                if (5 + n <= MAX_REUSED_RECORD) { writeRecord = record; }
            }
            record[0] = 0x17;
            record[1] = 0x03;
            record[2] = 0x03;
            record[3] = (byte) (n >>> 8);
            record[4] = (byte) n;
            System.arraycopy(buf, off, record, 5, n);
            delegate.write(record, 0, 5 + n);
            off += n;
            len -= n;
        }
    }

    public void flush() throws IOException { delegate.flush(); }
    public boolean isConnected() { return delegate.isConnected(); }
    public void close() { delegate.close(); readBuffer = new byte[0]; readAt = 0; firstWrite = false; }
    public long bytesRead() { return delegate.bytesRead(); }
    public long bytesWritten() { return delegate.bytesWritten(); }

    private byte[] buildHello()
    {
        Builder b = new Builder(2300);
        b.byte1(0x16); b.byte1(0x03); b.byte1(0x01);
        int recordLen = b.reserve16();
        b.byte1(0x01);
        int handshakeLen = b.reserve24();
        b.byte1(0x03); b.byte1(0x03);
        int randomAt = b.size();
        b.zeros(32);
        b.byte1(32); b.random(rng, 32);
        int cipherLen = b.reserve16();
        int cipherStart = b.size();
        b.grease(rng); b.bytes(new int[] {
            0x13,0x01,0x13,0x02,0x13,0x03,0xc0,0x2b,0xc0,0x2f,0xc0,0x2c,
            0xc0,0x30,0xcc,0xa9,0xcc,0xa8,0xc0,0x13,0xc0,0x14,0x00,0x9c,
            0x00,0x9d,0x00,0x2f,0x00,0x35
        });
        b.patch16(cipherLen, b.size() - cipherStart);
        b.byte1(1); b.byte1(0);
        int extensionsLen = b.reserve16();
        int extensionsStart = b.size();

        // Chrome/TDLib randomises the extension order. Keeping this as a
        // switch avoids allocating fifteen temporary extension buffers on a
        // handset whose heap size is still being measured.
        int[] order = new int[16];
        for (int i = 0; i < order.length; i++) { order[i] = i; }
        for (int i = order.length - 1; i > 0; i--)
        {
            int j = rng.nextInt(i + 1);
            int t = order[i]; order[i] = order[j]; order[j] = t;
        }
        for (int i = 0; i < order.length; i++)
        {
            switch (order[i])
            {
                case 0: b.grease(rng); b.uint16(0); break;
                case 1: extensionSni(b); break;
                case 2: extensionSimple(b, 0x0005, new int[] { 1,0,0,0,0 }); break;
                case 3: extensionSimple(b, 0x000a, new int[] {
                        0,12, 0x1a,0x1a, 0x11,0xec, 0,0x1d, 0,0x17, 0,0x18, 0,0x19
                }); break;
                case 4: extensionSimple(b, 0x000b, new int[] { 1,0 }); break;
                case 5: extensionSimple(b, 0x000d, new int[] {
                        0,16, 4,3, 8,4, 4,1, 5,3, 8,5, 5,1, 8,6, 6,1
                }); break;
                case 6: extensionSimple(b, 0x0010, new int[] {
                        0,12, 2,'h','2', 8,'h','t','t','p','/','1','.','1'
                }); break;
                case 7: extensionSimple(b, 0x0012, new int[0]); break;
                case 8: extensionSimple(b, 0x0017, new int[0]); break;
                case 9: extensionSimple(b, 0x001b, new int[] { 2,0,2 }); break;
                case 10: extensionSimple(b, 0x0023, new int[0]); break;
                case 11: extensionSimple(b, 0x002b,
                        new int[] { 6,0x1a,0x1a,3,4,3,3 }); break;
                case 12: extensionSimple(b, 0x002d, new int[] { 1,1 }); break;
                case 13: extensionKeyShare(b); break;
                case 14: extensionEch(b); break;
                case 15: extensionSimple(b, 0xff01, new int[] { 0 }); break;
                default: break;
            }
        }

        b.patch16(extensionsLen, b.size() - extensionsStart);
        b.patch24(handshakeLen, b.size() - handshakeLen - 3);
        b.patch16(recordLen, b.size() - 5);

        byte[] hello = b.toByteArray();
        byte[] hash = HmacSha256.compute(secret, hello);
        System.arraycopy(hash, 0, hello, randomAt, 32);
        int unix = (int) (System.currentTimeMillis() / 1000L);
        hello[randomAt + 28] ^= (byte) unix;
        hello[randomAt + 29] ^= (byte) (unix >>> 8);
        hello[randomAt + 30] ^= (byte) (unix >>> 16);
        hello[randomAt + 31] ^= (byte) (unix >>> 24);
        return hello;
    }

    private void extensionSni(Builder b)
    {
        b.uint16(0);
        int ext = b.reserve16();
        int start = b.size();
        int list = b.reserve16();
        int listStart = b.size();
        b.byte1(0);
        byte[] name = ascii(domain);
        b.uint16(name.length);
        b.bytes(name);
        b.patch16(list, b.size() - listStart);
        b.patch16(ext, b.size() - start);
    }

    private void extensionKeyShare(Builder b)
    {
        b.uint16(0x0033);
        int ext = b.reserve16(); int start = b.size();
        int list = b.reserve16(); int listStart = b.size();
        b.grease(rng); b.uint16(1); b.random(rng, 1);
        b.uint16(0x11ec); b.uint16(1216);
        // ML-KEM768 public-key shape: 384 pairs of coefficients below q=3329,
        // followed by its 32-byte seed. FakeTLS authenticates the hello; no real
        // TLS key agreement is performed.
        for (int i = 0; i < 384; i++)
        {
            int a = rng.nextInt(3329), c = rng.nextInt(3329);
            b.byte1(a); b.byte1((a >>> 8) | ((c & 15) << 4)); b.byte1(c >>> 4);
        }
        b.random(rng, 32);
        b.bytes(X25519.generateU(rng));
        b.uint16(0x001d); b.uint16(32); b.bytes(X25519.generateU(rng));
        b.patch16(list, b.size() - listStart);
        b.patch16(ext, b.size() - start);
    }

    private void extensionEch(Builder b)
    {
        b.uint16(0xfe0d);
        int ext = b.reserve16(); int start = b.size();
        b.bytes(new int[] { 0,0,1,0,1 });
        b.random(rng, 1);
        b.uint16(32); b.random(rng, 32);
        int payload = b.reserve16(); int p = b.size();
        b.random(rng, 144 + rng.nextInt(4) * 32);
        b.patch16(payload, b.size() - p);
        b.patch16(ext, b.size() - start);
    }

    private static void extensionSimple(Builder b, int type, int[] data)
    {
        b.uint16(type); b.uint16(data.length); b.bytes(data);
    }

    private void validateHelloResponse(byte[] clientRandom) throws IOException
    {
        Builder all = new Builder(2048);
        readRecordInto(all, 0x16);
        readRecordInto(all, 0x14);
        readRecordInto(all, 0x17);
        byte[] response = all.toByteArray();
        if (response.length < 43) { throw new IOException("FakeTLS response is too short"); }
        byte[] got = new byte[32];
        System.arraycopy(response, 11, got, 0, 32);
        for (int i = 11; i < 43; i++) { response[i] = 0; }
        byte[] expected = HmacSha256.compute(secret, clientRandom, response);
        if (!Hex.equals(got, expected)) { throw new IOException("FakeTLS response hash mismatch"); }
    }

    private void readRecordInto(Builder all, int expectedType) throws IOException
    {
        byte[] h = new byte[5];
        delegate.readFully(h, 0, 5);
        int len = ((h[3] & 0xff) << 8) | (h[4] & 0xff);
        if ((h[0] & 0xff) != expectedType || h[1] != 3 || h[2] != 3 || len > MAX_RECORD)
        {
            throw new IOException("invalid FakeTLS handshake record");
        }
        byte[] body = new byte[len];
        delegate.readFully(body, 0, len);
        all.bytes(h); all.bytes(body);
    }

    private void readApplicationRecord() throws IOException
    {
        while (true)
        {
            byte[] h = new byte[5];
            delegate.readFully(h, 0, 5);
            int type = h[0] & 0xff;
            int len = ((h[3] & 0xff) << 8) | (h[4] & 0xff);
            if (h[1] != 3 || h[2] != 3 || len <= 0 || len > MAX_RECORD)
            {
                throw new IOException("invalid FakeTLS application record");
            }
            byte[] body = new byte[len];
            delegate.readFully(body, 0, len);
            if (type == 0x14) { continue; }
            if (type != 0x17) { throw new IOException("unexpected TLS record " + type); }
            readBuffer = body;
            readAt = 0;
            return;
        }
    }

    private static byte[] ascii(String s)
    {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) { out[i] = (byte) s.charAt(i); }
        return out;
    }

    /** Tiny big-endian byte builder; CLDC has no ByteBuffer. */
    private static final class Builder
    {
        private byte[] data;
        private int size;

        Builder(int capacity) { data = new byte[capacity]; }
        int size() { return size; }
        int reserve16() { int p = size; byte1(0); byte1(0); return p; }
        int reserve24() { int p = size; byte1(0); byte1(0); byte1(0); return p; }
        void patch16(int p, int v) { data[p] = (byte) (v >>> 8); data[p + 1] = (byte) v; }
        void patch24(int p, int v) { data[p] = (byte) (v >>> 16); data[p + 1] = (byte) (v >>> 8); data[p + 2] = (byte) v; }
        void uint16(int v) { byte1(v >>> 8); byte1(v); }
        void byte1(int v) { ensure(1); data[size++] = (byte) v; }
        void zeros(int n) { ensure(n); for (int i = 0; i < n; i++) { data[size++] = 0; } }
        void random(Rng r, int n) { byte[] x = r.nextBytes(n); bytes(x); }
        void grease(Rng r) { int g = ((r.nextInt() & 0xf0) | 0x0a); byte1(g); byte1(g); }
        void bytes(int[] x) { ensure(x.length); for (int i = 0; i < x.length; i++) { data[size++] = (byte) x[i]; } }
        void bytes(byte[] x) { ensure(x.length); System.arraycopy(x, 0, data, size, x.length); size += x.length; }
        byte[] toByteArray() { byte[] out = new byte[size]; System.arraycopy(data, 0, out, 0, size); return out; }
        private void ensure(int n)
        {
            if (size + n <= data.length) { return; }
            byte[] bigger = new byte[Math.max(data.length * 2, size + n)];
            System.arraycopy(data, 0, bigger, 0, size); data = bigger;
        }
    }
}
