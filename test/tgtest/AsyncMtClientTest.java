package tgtest;

import java.io.IOException;
import java.util.Vector;

import tg.crypto.AesIge;
import tg.crypto.Rng;
import tg.crypto.Sha256;
import tg.io.Hex;
import tg.mt.AuthKey;
import tg.mt.Dc;
import tg.mt.MtClient;
import tg.mt.MtLink;
import tg.tl.Tl;
import tg.tl.TlReader;
import tg.tl.TlWriter;

/** Scripted encrypted peer for request routing, updates, acks and salt retry. */
public final class AsyncMtClientTest implements Test
{
    public String name() { return "mt/async-client"; }

    public void run() throws Exception
    {
        concurrentReverseResponses();
        saltRetry();
    }

    private void concurrentReverseResponses() throws Exception
    {
        final FakeLink link = new FakeLink(false);
        MtClient client = client(link);
        final int[] results = new int[2];
        final Throwable[] failures = new Throwable[2];
        final int queryA = 0x10203040;
        final int queryB = 0x50607080;
        Thread first = invokeThread(client, queryA, results, failures, 0);
        Thread second = invokeThread(client, queryB, results, failures, 1);
        first.start();
        second.start();
        first.join(5000);
        second.join(5000);
        Assert.isFalse("first RPC finished", first.isAlive());
        Assert.isFalse("second RPC finished", second.isAlive());
        if (failures[0] != null) { throw new AssertionError(failures[0]); }
        if (failures[1] != null) { throw new AssertionError(failures[1]); }
        Assert.equal("first routed by msg_id", queryA + 1000, results[0]);
        Assert.equal("second routed by msg_id", queryB + 1000, results[1]);
        Thread.sleep(400);
        Assert.equal("duplicate update dispatched once", 1, link.updateCount);
        Assert.isTrue("content messages acknowledged", link.ackCount > 0);
        client.close();
    }

    private void saltRetry() throws Exception
    {
        FakeLink link = new FakeLink(true);
        MtClient client = client(link);
        int query = 0x12344321;
        byte[] result = client.invokeWithSaltRetry(intBody(query));
        Assert.equal("salt retry response", query + 1000,
                new TlReader(result).readInt());
        Assert.equal("salt retry sends twice", 2, link.rpcCount);
        client.close();
    }

    private static MtClient client(FakeLink link) throws Exception
    {
        MtClient client = new MtClient(link,
                Rng.forTesting(Assert.ascii("async-client-seed")));
        client.setListener(new MtClient.Listener()
        {
            public void onUpdate(byte[] body) { }
            public void onConnectionLost(IOException error) { }
        });
        client.connect(Dc.BOOTSTRAP_DC_ID, "fake", 443, 1);
        client.resume(link.key, 0);
        link.client = client;
        client.setListener(link);
        return client;
    }

    private static Thread invokeThread(final MtClient client, final int query,
            final int[] results, final Throwable[] failures, final int slot)
    {
        return new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    results[slot] = new TlReader(client.invoke(intBody(query))).readInt();
                }
                catch (Throwable t) { failures[slot] = t; }
            }
        });
    }

    private static byte[] intBody(int value)
    {
        TlWriter writer = new TlWriter(4);
        writer.writeInt(value);
        return writer.toByteArray();
    }

    private static final class Pending
    {
        long msgId;
        int query;
    }

    private static final class FakeLink implements MtLink, MtClient.Listener
    {
        final AuthKey key;
        final boolean saltFirst;
        final Vector incoming = new Vector();
        final Vector pending = new Vector();
        boolean connected;
        byte[] current = new byte[0];
        long rx;
        long tx;
        int rpcCount;
        int ackCount;
        int updateCount;
        int serverCounter;
        MtClient client;

        FakeLink(boolean saltFirst)
        {
            this.saltFirst = saltFirst;
            byte[] raw = new byte[256];
            for (int i = 0; i < raw.length; i++) { raw[i] = (byte) (i + 1); }
            key = new AuthKey(raw, Dc.BOOTSTRAP_DC_ID, Dc.isTest());
        }

        public synchronized void connect(String host, int port, int timeout)
        {
            connected = true;
        }

        public void send(byte[] payload, int off, int len) throws IOException
        {
            byte[] packet = new byte[len];
            System.arraycopy(payload, off, packet, 0, len);
            byte[] body = decryptClient(packet);
            int constructor = new TlReader(body).readInt();
            synchronized (this) { tx += len; }
            if (constructor == Tl.MSGS_ACK)
            {
                synchronized (this) { ackCount++; }
                return;
            }
            if (constructor == Tl.PING || constructor == Tl.PING_DELAY_DISCONNECT
                    || constructor == 0x9299359f)
            {
                return;
            }

            long requestMsgId = clientMessageId(packet);
            int query = lastInt(body);
            synchronized (this)
            {
                rpcCount++;
                if (saltFirst && rpcCount == 1)
                {
                    TlWriter bad = new TlWriter(28);
                    bad.writeInt(Tl.BAD_SERVER_SALT);
                    bad.writeLong(requestMsgId);
                    bad.writeInt(1);
                    bad.writeInt(48);
                    bad.writeLong(123456789L);
                    queue(serverPacket(bad.toByteArray(), 1));
                    return;
                }
                Pending value = new Pending();
                value.msgId = requestMsgId;
                value.query = query;
                pending.addElement(value);
                if (saltFirst)
                {
                    queue(result(value));
                }
                else if (pending.size() == 2)
                {
                    byte[] update = serverPacket(intBody(0x11223344), 1);
                    queue(update);
                    queue(update); // identical msg_id: listener must see it once
                    queue(result((Pending) pending.elementAt(1)));
                    queue(result((Pending) pending.elementAt(0)));
                }
            }
        }

        private byte[] result(Pending pending) throws IOException
        {
            TlWriter body = new TlWriter(16);
            body.writeInt(Tl.RPC_RESULT);
            body.writeLong(pending.msgId);
            body.writeInt(pending.query + 1000);
            return serverPacket(body.toByteArray(), 1);
        }

        private synchronized void queue(byte[] packet)
        {
            incoming.addElement(packet);
            notifyAll();
        }

        public synchronized int receive() throws IOException
        {
            while (incoming.size() == 0 && connected)
            {
                try { wait(); }
                catch (InterruptedException e) { throw new IOException("interrupted"); }
            }
            if (!connected) { throw new IOException("fake link closed"); }
            current = (byte[]) incoming.elementAt(0);
            incoming.removeElementAt(0);
            rx += current.length;
            return current.length;
        }

        public synchronized byte[] buffer() { return current; }
        public synchronized boolean isConnected() { return connected; }
        public synchronized void close() { connected = false; notifyAll(); }
        public synchronized long bytesRead() { return rx; }
        public synchronized long bytesWritten() { return tx; }
        public String description() { return "scripted"; }
        public boolean isRequestResponse() { return false; }
        public void onUpdate(byte[] body) { updateCount++; }
        public void onConnectionLost(IOException error) { }

        private long clientMessageId(byte[] packet) throws IOException
        {
            byte[] plain = decrypt(packet, 0);
            return readLong(plain, 16);
        }

        private byte[] decryptClient(byte[] packet) throws IOException
        {
            byte[] plain = decrypt(packet, 0);
            int length = readInt(plain, 28);
            byte[] body = new byte[length];
            System.arraycopy(plain, 32, body, 0, length);
            return body;
        }

        private byte[] decrypt(byte[] packet, int x) throws IOException
        {
            byte[][] material = derive(packet, 8, x);
            byte[] plain = new byte[packet.length - 24];
            new AesIge(material[0]).decrypt(material[1], 0,
                    packet, 24, plain, 0, plain.length);
            return plain;
        }

        private byte[] serverPacket(byte[] body, int seqNo) throws IOException
        {
            int unpadded = 32 + body.length;
            int padding = 12 + ((16 - ((unpadded + 12) & 15)) & 15);
            byte[] plain = new byte[unpadded + padding];
            writeLong(plain, 0, 0);
            writeLong(plain, 8, client.session().sessionId());
            long msgId = ((System.currentTimeMillis() / 1000L) << 32)
                    | ((serverCounter++ * 4L + 1L) & 0xffffffffL);
            writeLong(plain, 16, msgId);
            writeInt(plain, 24, seqNo);
            writeInt(plain, 28, body.length);
            System.arraycopy(body, 0, plain, 32, body.length);

            Sha256 sha = new Sha256();
            sha.update(key.bytes(), 96, 32);
            sha.update(plain);
            byte[] large = sha.digest();
            byte[] msgKey = new byte[16];
            System.arraycopy(large, 8, msgKey, 0, 16);
            byte[][] material = derive(msgKey, 0, 8);
            byte[] packet = new byte[24 + plain.length];
            writeLong(packet, 0, key.keyId());
            System.arraycopy(msgKey, 0, packet, 8, 16);
            new AesIge(material[0]).encrypt(material[1], 0,
                    plain, 0, packet, 24, plain.length);
            return packet;
        }

        private byte[][] derive(byte[] msgKey, int off, int x)
        {
            Sha256 sha = new Sha256();
            sha.update(msgKey, off, 16);
            sha.update(key.bytes(), x, 36);
            byte[] a = sha.digest();
            sha.reset();
            sha.update(key.bytes(), 40 + x, 36);
            sha.update(msgKey, off, 16);
            byte[] b = sha.digest();
            byte[] aesKey = new byte[32];
            byte[] aesIv = new byte[32];
            System.arraycopy(a, 0, aesKey, 0, 8);
            System.arraycopy(b, 8, aesKey, 8, 16);
            System.arraycopy(a, 24, aesKey, 24, 8);
            System.arraycopy(b, 0, aesIv, 0, 8);
            System.arraycopy(a, 8, aesIv, 8, 16);
            System.arraycopy(b, 24, aesIv, 24, 8);
            return new byte[][] { aesKey, aesIv };
        }

        private static int lastInt(byte[] body)
        {
            return readInt(body, body.length - 4);
        }

        private static void writeInt(byte[] out, int off, int value)
        {
            out[off] = (byte) value;
            out[off + 1] = (byte) (value >>> 8);
            out[off + 2] = (byte) (value >>> 16);
            out[off + 3] = (byte) (value >>> 24);
        }

        private static int readInt(byte[] in, int off)
        {
            return (in[off] & 0xff) | ((in[off + 1] & 0xff) << 8)
                    | ((in[off + 2] & 0xff) << 16)
                    | ((in[off + 3] & 0xff) << 24);
        }

        private static void writeLong(byte[] out, int off, long value)
        {
            for (int i = 0; i < 8; i++) { out[off + i] = (byte) (value >>> (i * 8)); }
        }

        private static long readLong(byte[] in, int off)
        {
            long value = 0;
            for (int i = 7; i >= 0; i--)
            {
                value = (value << 8) | (in[off + i] & 0xffL);
            }
            return value;
        }
    }
}
