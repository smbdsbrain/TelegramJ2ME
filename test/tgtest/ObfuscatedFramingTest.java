package tgtest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import tg.crypto.AesCtr;
import tg.crypto.Rng;
import tg.crypto.Sha256;
import tg.io.ObfuscatedTransport;
import tg.io.Transport;
import tg.mt.Abridged;
import tg.mt.Intermediate;
import tg.mt.MsgIdGen;
import tg.mt.MtPlain;
import tg.mt.ProxySecret;

/** Wire-level obfuscated2 and intermediate framing tests. */
public final class ObfuscatedFramingTest implements Test
{
    public String name() { return "mt/obfuscated-framing"; }

    public void run() throws Exception
    {
        obfuscatedHeaderAndStream();
        intermediateSend();
        paddedReceiveAndErrors();
        overPaddedFrames();
    }

    private void obfuscatedHeaderAndStream() throws Exception
    {
        byte[] seed = Assert.ascii("obfuscated2-deterministic-test");
        ProxySecret secret =
                ProxySecret.parse("00112233445566778899aabbccddeeff");
        MemoryTransport raw = new MemoryTransport(new byte[0]);
        ObfuscatedTransport obfs = new ObfuscatedTransport(raw,
                Rng.forTesting(seed),
                ObfuscatedTransport.PROTOCOL_INTERMEDIATE, -2, secret);
        obfs.connect("proxy", 443, 1000);

        byte[] plain = new byte[64];
        Rng oracleRng = Rng.forTesting(seed);
        do { oracleRng.nextBytes(plain); } while (forbidden(plain));
        putLe(plain, 56, ObfuscatedTransport.PROTOCOL_INTERMEDIATE);
        plain[60] = (byte) 0xfe;
        plain[61] = (byte) 0xff;

        byte[] reverse = new byte[64];
        for (int i = 0; i < 64; i++) { reverse[i] = plain[63 - i]; }
        byte[] encKey = Sha256.hash(slice(plain, 8, 32), secret.key());
        AesCtr enc = new AesCtr(encKey, slice(plain, 40, 16));
        byte[] encryptedHeader = new byte[64];
        enc.crypt(plain, 0, encryptedHeader, 0, 64);
        byte[] expectedHeader = (byte[]) plain.clone();
        System.arraycopy(encryptedHeader, 56, expectedHeader, 56, 8);
        Assert.bytesEqual("obfuscated2 64-byte greeting", expectedHeader, raw.output());

        byte[] payload = Assert.ascii("stream boundaries are preserved");
        obfs.write(payload, 0, 3);
        obfs.write(payload, 3, payload.length - 3);
        byte[] encryptedPayload = new byte[payload.length];
        enc.crypt(payload, 0, encryptedPayload, 0, payload.length);
        byte[] wire = raw.output();
        Assert.bytesEqual("obfuscated2 persistent TX CTR",
                encryptedPayload, slice(wire, 64, encryptedPayload.length));
    }

    private void intermediateSend() throws Exception
    {
        byte[] packet = new byte[20];
        putLe(packet, 16, 0);

        MemoryTransport normalRaw = new MemoryTransport(new byte[0]);
        Intermediate normal = new Intermediate(normalRaw,
                Rng.forTesting(Assert.ascii("intermediate")), false, false);
        normal.send(packet, 0, packet.length);
        byte[] normalWire = normalRaw.output();
        Assert.equal("intermediate wire length", 28, normalWire.length);
        Assert.equal("intermediate tag", 0xeeeeeeeeL, readLe(normalWire, 0) & 0xffffffffL);
        Assert.equal("intermediate payload length", packet.length, readLe(normalWire, 4));

        MemoryTransport embeddedRaw = new MemoryTransport(new byte[0]);
        Intermediate embedded = new Intermediate(embeddedRaw,
                Rng.forTesting(Assert.ascii("embedded")), false, true);
        embedded.send(packet, 0, packet.length);
        Assert.equal("embedded tag is not emitted", packet.length,
                     readLe(embeddedRaw.output(), 0));

        MemoryTransport paddedRaw = new MemoryTransport(new byte[0]);
        Intermediate padded = new Intermediate(paddedRaw,
                Rng.forTesting(Assert.ascii("padded")), true, true);
        padded.send(packet, 0, packet.length);
        byte[] paddedWire = paddedRaw.output();
        int declared = readLe(paddedWire, 0);
        Assert.isTrue("padding 0..15", declared >= packet.length
                && declared <= packet.length + 15);
        Assert.equal("padded frame exact wire size", declared + 4, paddedWire.length);
    }

    private void paddedReceiveAndErrors() throws Exception
    {
        byte[] plain = new byte[28]; // auth_key_id + msg_id + body_length + body
        putLe(plain, 16, 8);
        byte[] plainWire = frame(plain, 5);
        Intermediate plainFrame = new Intermediate(new MemoryTransport(plainWire),
                Rng.forTesting(Assert.ascii("rx-plain")), true, true);
        Assert.equal("recover plaintext length", plain.length, plainFrame.receive());

        byte[] encrypted = new byte[40]; // 8 + 16 + one AES block
        encrypted[0] = 1;
        byte[] encryptedWire = frame(encrypted, 9);
        Intermediate encryptedFrame = new Intermediate(new MemoryTransport(encryptedWire),
                Rng.forTesting(Assert.ascii("rx-encrypted")), true, true);
        Assert.equal("recover encrypted length", encrypted.length, encryptedFrame.receive());

        try
        {
            new Intermediate(new MemoryTransport(new byte[0]),
                    Rng.forTesting(Assert.ascii("large")), false, true)
                    .send(new byte[Intermediate.MAX_PACKET + 4], 0,
                          Intermediate.MAX_PACKET + 4);
            Assert.fail("oversized intermediate packet accepted");
        }
        catch (IOException expected) { }

        try
        {
            byte[] truncated = new byte[] { 20, 0, 0, 0, 1, 2 };
            new Intermediate(new MemoryTransport(truncated),
                    Rng.forTesting(Assert.ascii("short")), false, true).receive();
            Assert.fail("truncated intermediate packet accepted");
        }
        catch (IOException expected) { }
    }

    /**
     * A live MTProxy answered res_pq with 22 bytes of transport padding, well
     * past the documented 0-15, and the frame layer rejected the whole packet.
     * The padded-intermediate length is recoverable from the MTProto envelope,
     * so the tail size is not ours to police - only the envelope has to fit.
     */
    private void overPaddedFrames() throws Exception
    {
        byte[] plain = new byte[100];        // 20-byte header + an 80-byte resPQ
        putLe(plain, 16, 80);
        for (int i = 0; i < 80; i++) { plain[20 + i] = (byte) (i + 1); }

        Intermediate frame = new Intermediate(new MemoryTransport(frame(plain, 22)),
                Rng.forTesting(Assert.ascii("over-padded")), true, true);
        Assert.equal("res_pq recovered from an over-padded frame", 100, frame.receive());

        // What actually failed on the handset was the whole plaintext path, so
        // assert the body arrives intact rather than just the framing length.
        MtPlain mtPlain = new MtPlain(
                new Intermediate(new MemoryTransport(frame(plain, 22)),
                        Rng.forTesting(Assert.ascii("over-padded-plain")), true, true),
                new MsgIdGen());
        Assert.bytesEqual("over-padded body reaches MtPlain intact",
                slice(plain, 20, 80), mtPlain.receive());

        // A bare transport error is four bytes, but a padding-happy carrier
        // pads that too, and -404 is worth far more than "packet is too short".
        int[] errorPadding = { 0, 3, 15, 31 };
        for (int i = 0; i < errorPadding.length; i++)
        {
            byte[] code = { 0x6c, (byte) 0xfe, (byte) 0xff, (byte) 0xff };   // -404
            Intermediate errorFrame = new Intermediate(
                    new MemoryTransport(frame(code, errorPadding[i])),
                    Rng.forTesting(Assert.ascii("err" + errorPadding[i])), true, true);
            int len = errorFrame.receive();
            Assert.equal("padded transport error stays 4 bytes", 4, len);
            Assert.equal("padded transport error decodes", -404,
                         Abridged.asTransportError(errorFrame.buffer(), len));
        }

        // Encrypted frames cannot be relaxed the same way: the length is only
        // recoverable modulo the AES block, so a full hidden block is invisible
        // here and only the msg_key check downstream can catch it.
        byte[] encrypted = new byte[40];
        encrypted[0] = 1;
        for (int pad = 0; pad <= 15; pad++)
        {
            Intermediate strict = new Intermediate(new MemoryTransport(frame(encrypted, pad)),
                    Rng.forTesting(Assert.ascii("enc" + pad)), true, true);
            Assert.equal("encrypted length recovered with " + pad + " padding",
                         40, strict.receive());
        }
        Intermediate hidden = new Intermediate(new MemoryTransport(frame(encrypted, 16)),
                Rng.forTesting(Assert.ascii("enc16")), true, true);
        Assert.equal("a whole hidden block is invisible to the frame layer",
                     56, hidden.receive());

        // Relaxing the tail must not weaken desync detection: the envelope
        // still has to fit inside the frame that was actually read.
        byte[] lying = new byte[40];
        putLe(lying, 16, 1000);
        try
        {
            new Intermediate(new MemoryTransport(frame(lying, 0)),
                    Rng.forTesting(Assert.ascii("lying")), true, true).receive();
            Assert.fail("plaintext body length outside the frame accepted");
        }
        catch (IOException expected) { }

        byte[] small = new byte[20];
        try
        {
            new Intermediate(new MemoryTransport(frame(small, Intermediate.MAX_PADDING + 1)),
                    Rng.forTesting(Assert.ascii("flood")), true, true).receive();
            Assert.fail("unbounded padding accepted");
        }
        catch (IOException expected) { }
    }

    private static byte[] frame(byte[] payload, int padding)
    {
        byte[] wire = new byte[4 + payload.length + padding];
        putLe(wire, 0, payload.length + padding);
        System.arraycopy(payload, 0, wire, 4, payload.length);
        return wire;
    }

    private static boolean forbidden(byte[] h)
    {
        int first = readLe(h, 0), second = readLe(h, 4);
        return (h[0] & 0xff) == 0xef || second == 0
                || first == 0x44414548 || first == 0x54534f50
                || first == 0x20544547 || first == 0x4954504f
                || first == 0x02010316 || first == 0xdddddddd
                || first == 0xeeeeeeee;
    }

    private static byte[] slice(byte[] data, int off, int len)
    {
        byte[] out = new byte[len];
        System.arraycopy(data, off, out, 0, len);
        return out;
    }

    private static int readLe(byte[] b, int p)
    {
        return (b[p] & 255) | ((b[p + 1] & 255) << 8)
                | ((b[p + 2] & 255) << 16) | (b[p + 3] << 24);
    }

    private static void putLe(byte[] b, int p, int v)
    {
        b[p] = (byte) v; b[p + 1] = (byte) (v >>> 8);
        b[p + 2] = (byte) (v >>> 16); b[p + 3] = (byte) (v >>> 24);
    }

    private static final class MemoryTransport implements Transport
    {
        private final ByteArrayInputStream in;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private boolean connected;
        private long read;

        MemoryTransport(byte[] input) { in = new ByteArrayInputStream(input); }
        public void connect(String host, int port, int timeout) { connected = true; }
        public int read(byte[] b, int o, int n)
        {
            int got = in.read(b, o, n);
            if (got > 0) { read += got; }
            return got;
        }
        public void readFully(byte[] b, int o, int n) throws IOException
        {
            int done = 0;
            while (done < n)
            {
                int got = read(b, o + done, n - done);
                if (got < 0) { throw new IOException("truncated"); }
                done += got;
            }
        }
        public void write(byte[] b, int o, int n) { out.write(b, o, n); }
        public void flush() { }
        public boolean isConnected() { return connected; }
        public void close() { connected = false; }
        public long bytesRead() { return read; }
        public long bytesWritten() { return out.size(); }
        byte[] output() { return out.toByteArray(); }
    }
}
