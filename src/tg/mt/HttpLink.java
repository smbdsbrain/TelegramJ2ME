package tg.mt;

import java.io.IOException;

import tg.io.HttpExecutor;
import tg.io.HttpResponse;
import tg.mem.MemoryBudget;

/**
 * MTProto-over-HTTP packet link. HTTP itself provides the framing.
 *
 * <h3>Why the queue is bounded in bytes and not in responses</h3>
 * HTTP is request/response, so responses arrive on the POSTing thread and have
 * to be handed to the reader through a queue. That queue used to hold up to
 * eight bodies and nothing bounded their size, which meant a worst case of eight
 * megabytes retained - more than the entire heap of either handset that has ever
 * been measured. The count is still a guard on the ring, but the number that
 * decides retention is now {@link tg.mem.MemoryBudget#httpQueueBytes}.
 *
 * The sender blocks rather than dropping. A dropped response is a lost RPC
 * result, which the layer above cannot distinguish from a timeout; making the
 * writer wait for the reader is the correct back-pressure and is what the
 * count cap already did.
 */
public final class HttpLink implements MtLink
{
    /** Bounds the ring itself; {@code httpQueueBytes()} bounds the memory. */
    private static final int MAX_RESPONSES = 8;

    private final HttpExecutor executor;
    private final String url;
    private final byte[][] responses = new byte[MAX_RESPONSES][];
    private int responseHead;
    private int responseCount;
    private int queuedBytes;
    private byte[] current = new byte[0];
    private boolean connected;
    private long rx;
    private long tx;

    public HttpLink(HttpExecutor executor, String url)
    {
        this.executor = executor;
        this.url = url;
    }

    public synchronized void connect(String host, int port, int timeoutMs)
    {
        connected = true;       // the actual HTTP connection opens per POST
        responseHead = 0;
        responseCount = 0;
        queuedBytes = 0;
        current = new byte[0];
        rx = 0;
        tx = 0;
        notifyAll();
    }

    public void send(byte[] payload, int off, int len) throws IOException
    {
        synchronized (this)
        {
            if (!connected) { throw new IOException("HTTP link is not connected"); }
        }
        int maxPacket = MemoryBudget.packetBytes();
        if (len <= 0 || len > maxPacket) { throw new IOException("invalid HTTP payload " + len); }
        byte[] body = new byte[len];
        System.arraycopy(payload, off, body, 0, len);
        HttpResponse r = executor.post(url, body, maxPacket);
        if (r.status != 200)
        {
            throw new IOException("MTProto HTTP status " + r.status);
        }
        if (r.body == null || r.body.length == 0)
        {
            throw new IOException("empty MTProto HTTP response");
        }
        if (r.body.length > maxPacket)
        {
            throw new IOException("MTProto HTTP response exceeds " + maxPacket);
        }
        synchronized (this)
        {
            if (!connected) { throw new IOException("HTTP link closed during POST"); }
            // An empty queue always accepts, even if this one body is larger
            // than the whole budget: there is nothing left to wait for, and the
            // body is already allocated. Waiting there would be a deadlock.
            while (connected && responseCount > 0
                   && (responseCount == MAX_RESPONSES
                       || queuedBytes + r.body.length > MemoryBudget.httpQueueBytes()))
            {
                try { wait(); }
                catch (InterruptedException e) { throw new IOException("HTTP response queue interrupted"); }
            }
            if (!connected) { throw new IOException("HTTP link closed during POST"); }
            int tail = (responseHead + responseCount) % MAX_RESPONSES;
            responses[tail] = r.body;
            responseCount++;
            queuedBytes += r.body.length;
            tx += len;
            rx += r.body.length;
            notifyAll();
        }
    }

    public synchronized int receive() throws IOException
    {
        while (responseCount == 0 && connected)
        {
            try { wait(); }
            catch (InterruptedException e) { throw new IOException("HTTP receive interrupted"); }
        }
        if (!connected) { throw new IOException("HTTP link is closed"); }
        current = responses[responseHead];
        responses[responseHead] = null;
        responseHead = (responseHead + 1) % MAX_RESPONSES;
        responseCount--;
        queuedBytes -= current.length;
        if (queuedBytes < 0) { queuedBytes = 0; }
        notifyAll();
        return current.length;
    }

    public synchronized byte[] buffer() { return current; }
    public synchronized boolean isConnected() { return connected; }
    public synchronized void close()
    {
        connected = false;
        responseHead = 0;
        responseCount = 0;
        queuedBytes = 0;
        for (int i = 0; i < MAX_RESPONSES; i++) { responses[i] = null; }
        current = new byte[0];
        notifyAll();
    }
    public synchronized long bytesRead() { return rx; }
    public synchronized long bytesWritten() { return tx; }
    public String description() { return "http " + url; }
    public boolean isRequestResponse() { return true; }
    public int hiddenPaddingBlocks() { return 0; }
}
