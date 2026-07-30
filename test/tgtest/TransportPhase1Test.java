package tgtest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import tg.crypto.HmacSha256;
import tg.crypto.Rng;
import tg.crypto.X25519;
import tg.io.FakeTlsTransport;
import tg.io.HttpExecutor;
import tg.io.HttpResponse;
import tg.io.Transport;
import tg.mt.HttpLink;

/** Packet-carrier tests for HTTP and FakeTLS. */
public final class TransportPhase1Test implements Test
{
    public String name() { return "mt/phase1-transports"; }

    public void run() throws Exception
    {
        hmacVector();
        x25519Points();
        http();
        fakeTls();
        fakeTlsRecordFraming();
    }

    /**
     * Record boundaries are the proxy's choice, not ours: a packet may arrive
     * split across records with a ChangeCipherSpec wedged in between, and our
     * own packets must leave as one record each.
     */
    private void fakeTlsRecordFraming() throws Exception
    {
        byte[] secret = Assert.unhex("00112233445566778899aabbccddeeff");
        FakeServerTransport raw = new FakeServerTransport(secret);
        FakeTlsTransport tls = new FakeTlsTransport(raw,
                Rng.forTesting(Assert.ascii("record-framing")), secret, "example.com");
        tls.connect("proxy", 443, 1000);

        byte[] whole = Assert.ascii("sixteen-byte-pkt");
        ByteArrayOutputStream split = new ByteArrayOutputStream();
        appendRecord(split, 0x17, whole, 0, 6);
        appendRecord(split, 0x14, new byte[] { 1 }, 0, 1);
        appendRecord(split, 0x17, whole, 6, whole.length - 6);
        raw.queueRaw(split.toByteArray());

        byte[] rejoined = new byte[whole.length];
        tls.readFully(rejoined, 0, rejoined.length);
        Assert.bytesEqual("packet rejoined across a record boundary", whole, rejoined);

        // The send side, through the same stack the MTProxy route builds. It
        // needs its own server: ObfuscatedTransport.connect reruns the FakeTLS
        // handshake underneath it.
        FakeServerTransport sendRaw = new FakeServerTransport(secret);
        Transport obfs = new tg.io.ObfuscatedTransport(
                new FakeTlsTransport(sendRaw,
                        Rng.forTesting(Assert.ascii("record-framing-tls")),
                        secret, "example.com"),
                Rng.forTesting(Assert.ascii("record-framing-obfs")),
                tg.io.ObfuscatedTransport.PROTOCOL_PADDED_INTERMEDIATE, 2, null);
        obfs.connect("proxy", 443, 1000);
        sendRaw.afterHello.reset();

        tg.mt.Intermediate frame = new tg.mt.Intermediate(obfs,
                Rng.forTesting(Assert.ascii("record-framing-frame")), true, true);
        byte[] packet = new byte[20];
        frame.send(packet, 0, packet.length);

        int[] lengths = recordLengths(sendRaw.afterHello.toByteArray());
        Assert.equal("a padded packet leaves as one TLS record", 1, lengths.length);
        // Real TLS 1.3 cannot produce an application-data record below 17 bytes
        // (one content-type byte plus a 16-byte AEAD tag), so anything smaller
        // is a giveaway that this is not TLS at all.
        Assert.isTrue("record is not impossibly small for TLS 1.3", lengths[0] >= 17);
        Assert.isTrue("record carries length prefix, payload and padding",
                      lengths[0] >= 24 && lengths[0] <= 24 + 15);
    }

    private static void appendRecord(ByteArrayOutputStream out, int type,
                                     byte[] body, int off, int len)
    {
        out.write(type); out.write(3); out.write(3);
        out.write(len >>> 8); out.write(len & 0xff);
        out.write(body, off, len);
    }

    /** Lengths of the 0x17 records in a raw TLS stream, in order. */
    private static int[] recordLengths(byte[] stream)
    {
        int count = 0;
        for (int pass = 0; pass < 2; pass++)
        {
            int[] out = pass == 0 ? null : new int[count];
            int at = 0;
            count = 0;
            while (at + 5 <= stream.length)
            {
                int type = stream[at] & 0xff;
                int len = ((stream[at + 3] & 0xff) << 8) | (stream[at + 4] & 0xff);
                if (type == 0x17)
                {
                    if (out != null) { out[count] = len; }
                    count++;
                }
                at += 5 + len;
            }
            if (out != null) { return out; }
        }
        return new int[0];
    }

    private void x25519Points()
    {
        Rng rng = Rng.forTesting(Assert.ascii("x25519-validity"));
        for (int i = 0; i < 4; i++)
        {
            byte[] u = X25519.generateU(rng);
            Assert.isTrue("generated FakeTLS X25519 point", X25519.isValidU(u));
        }
    }

    private void hmacVector()
    {
        Assert.bytesEqual("RFC 4231 HMAC-SHA256 case 1",
                "b0344c61d8db38535ca8afceaf0bf12b"
              + "881dc200c9833da726e9376c2e32cff7",
                HmacSha256.compute(Assert.repeat((byte) 0x0b, 20),
                        Assert.ascii("Hi There")));
    }

    private void http() throws Exception
    {
        final byte[] answer = Assert.ascii("response");
        HttpExecutor ok = new HttpExecutor()
        {
            public HttpResponse post(String url, byte[] body, int max)
            {
                Assert.equal("HTTP URL", "http://venus.web.telegram.org:80/api", url);
                Assert.bytesEqual("HTTP request", Assert.ascii("request"), body);
                return new HttpResponse(200, answer);
            }
        };
        HttpLink link = new HttpLink(ok, "http://venus.web.telegram.org:80/api");
        link.connect("ignored", 80, 1);
        byte[] request = Assert.ascii("request");
        link.send(request, 0, request.length);
        Assert.equal("HTTP receive length", answer.length, link.receive());
        Assert.bytesEqual("HTTP response", answer, link.buffer());
        Assert.equal("HTTP tx", request.length, link.bytesWritten());
        Assert.equal("HTTP rx", answer.length, link.bytesRead());

        final HttpLink blocking = new HttpLink(ok, "http://x/api");
        final boolean[] unblocked = new boolean[1];
        blocking.connect("x", 80, 1);
        Thread receiver = new Thread(new Runnable()
        {
            public void run()
            {
                try { blocking.receive(); }
                catch (IOException expected) { unblocked[0] = true; }
            }
        });
        receiver.start();
        Thread.sleep(20);
        Assert.isTrue("HTTP receive blocks while empty", receiver.isAlive());
        blocking.close();
        receiver.join(1000);
        Assert.isTrue("HTTP close unblocks receive", unblocked[0]);

        HttpLink bad = new HttpLink(new HttpExecutor()
        {
            public HttpResponse post(String url, byte[] body, int max)
            {
                return new HttpResponse(403, new byte[0]);
            }
        }, "http://x/api");
        bad.connect("x", 80, 1);
        try
        {
            bad.send(request, 0, request.length);
            Assert.fail("HTTP 403 accepted");
        }
        catch (IOException expected) { }

        HttpLink huge = new HttpLink(new HttpExecutor()
        {
            public HttpResponse post(String url, byte[] body, int max)
            {
                return new HttpResponse(200, new byte[HttpLink.MAX_PACKET + 1]);
            }
        }, "http://x/api");
        huge.connect("x", 80, 1);
        try
        {
            huge.send(request, 0, request.length);
            Assert.fail("oversized HTTP response accepted");
        }
        catch (IOException expected) { }
    }

    private void fakeTls() throws Exception
    {
        byte[] secret = Assert.unhex("00112233445566778899aabbccddeeff");
        FakeServerTransport raw = new FakeServerTransport(secret);
        FakeTlsTransport tls = new FakeTlsTransport(raw,
                Rng.forTesting(Assert.ascii("fake-tls-test-seed")),
                secret, "example.com");
        tls.connect("proxy", 443, 1000);

        byte[] hello = raw.hello;
        Assert.isTrue("modern hello includes hybrid key share", hello.length > 1500);
        Assert.isTrue("hello carries SNI", contains(hello, Assert.ascii("example.com")));
        Assert.equal("TLS handshake record", 0x16, hello[0] & 0xff);
        byte[] unsignedHello = (byte[]) hello.clone();
        for (int i = 11; i < 43; i++) { unsignedHello[i] = 0; }
        byte[] helloHash = HmacSha256.compute(secret, unsignedHello);
        long stamped = ((hello[39] ^ helloHash[28]) & 0xffL)
                | (((hello[40] ^ helloHash[29]) & 0xffL) << 8)
                | (((hello[41] ^ helloHash[30]) & 0xffL) << 16)
                | (((hello[42] ^ helloHash[31]) & 0xffL) << 24);
        long now = System.currentTimeMillis() / 1000L;
        Assert.isTrue("FakeTLS timestamp is current", Math.abs(now - stamped) <= 5);

        byte[] payload = Assert.ascii("obfuscated stream");
        tls.write(payload, 0, payload.length);
        tls.flush();
        byte[] written = raw.afterHello.toByteArray();
        Assert.equal("first post-hello record is CCS", 0x14, written[0] & 0xff);
        Assert.equal("then application data", 0x17, written[6] & 0xff);
        byte[] carried = new byte[payload.length];
        System.arraycopy(written, 11, carried, 0, carried.length);
        Assert.bytesEqual("TLS record payload", payload, carried);

        raw.queueApplication(Assert.ascii("incoming"));
        byte[] incoming = new byte[8];
        tls.readFully(incoming, 0, incoming.length);
        Assert.bytesEqual("unwrapped TLS application data", Assert.ascii("incoming"), incoming);
    }

    private static boolean contains(byte[] haystack, byte[] needle)
    {
        for (int i = 0; i + needle.length <= haystack.length; i++)
        {
            int j = 0;
            while (j < needle.length && haystack[i + j] == needle[j]) { j++; }
            if (j == needle.length) { return true; }
        }
        return false;
    }

    private static final class FakeServerTransport implements Transport
    {
        private final byte[] secret;
        private final ByteArrayOutputStream initial = new ByteArrayOutputStream();
        final ByteArrayOutputStream afterHello = new ByteArrayOutputStream();
        byte[] hello;
        private byte[] input = new byte[0];
        private int inputAt;
        private boolean connected;

        FakeServerTransport(byte[] secret) { this.secret = secret; }

        public void connect(String host, int port, int timeout) { connected = true; }
        public boolean isConnected() { return connected; }

        public void write(byte[] b, int off, int len)
        {
            if (hello == null) { initial.write(b, off, len); }
            else { afterHello.write(b, off, len); }
        }

        public void flush()
        {
            if (hello == null)
            {
                hello = initial.toByteArray();
                byte[] clientRandom = new byte[32];
                System.arraycopy(hello, 11, clientRandom, 0, 32);
                input = helloResponse(clientRandom);
                inputAt = 0;
            }
        }

        public int read(byte[] b, int off, int len)
        {
            if (inputAt >= input.length) { return -1; }
            int n = Math.min(len, input.length - inputAt);
            System.arraycopy(input, inputAt, b, off, n);
            inputAt += n;
            return n;
        }

        public void readFully(byte[] b, int off, int len) throws IOException
        {
            int got = 0;
            while (got < len)
            {
                int n = read(b, off + got, len - got);
                if (n < 0) { throw new IOException("fake server eof"); }
                got += n;
            }
        }

        /** Feed an already-framed TLS record stream, boundaries and all. */
        void queueRaw(byte[] records)
        {
            input = records;
            inputAt = 0;
        }

        void queueApplication(byte[] payload)
        {
            input = new byte[5 + payload.length];
            input[0] = 0x17; input[1] = 3; input[2] = 3;
            input[3] = (byte) (payload.length >>> 8); input[4] = (byte) payload.length;
            System.arraycopy(payload, 0, input, 5, payload.length);
            inputAt = 0;
        }

        private byte[] helloResponse(byte[] clientRandom)
        {
            byte[] response = new byte[5 + 43 + 6 + 6];
            int p = 0;
            response[p++] = 0x16; response[p++] = 3; response[p++] = 3;
            response[p++] = 0; response[p++] = 43;
            response[p++] = 2; response[p++] = 0; response[p++] = 0; response[p++] = 39;
            response[p++] = 3; response[p++] = 3;
            p += 32;
            while (p < 48) { response[p++] = 0; }
            response[p++] = 0x14; response[p++] = 3; response[p++] = 3;
            response[p++] = 0; response[p++] = 1; response[p++] = 1;
            response[p++] = 0x17; response[p++] = 3; response[p++] = 3;
            response[p++] = 0; response[p++] = 1; response[p++] = 0;

            byte[] hash = HmacSha256.compute(secret, clientRandom, response);
            System.arraycopy(hash, 0, response, 11, 32);
            return response;
        }

        public void close() { connected = false; }
        public long bytesRead() { return inputAt; }
        public long bytesWritten() { return initial.size() + afterHello.size(); }
    }
}
