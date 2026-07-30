package tg.mt;

import java.io.IOException;

import tg.io.HttpExecutor;
import tg.io.HttpResponse;

/** MTProto-over-HTTP packet link. HTTP itself provides the framing. */
public final class HttpLink implements MtLink
{
    public static final int MAX_PACKET = 1024 * 1024;
    private static final int MAX_RESPONSES = 8;

    private final HttpExecutor executor;
    private final String url;
    private final byte[][] responses = new byte[MAX_RESPONSES][];
    private int responseHead;
    private int responseCount;
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
        if (len <= 0 || len > MAX_PACKET) { throw new IOException("invalid HTTP payload " + len); }
        byte[] body = new byte[len];
        System.arraycopy(payload, off, body, 0, len);
        HttpResponse r = executor.post(url, body, MAX_PACKET);
        if (r.status != 200)
        {
            throw new IOException("MTProto HTTP status " + r.status);
        }
        if (r.body == null || r.body.length == 0)
        {
            throw new IOException("empty MTProto HTTP response");
        }
        if (r.body.length > MAX_PACKET)
        {
            throw new IOException("MTProto HTTP response exceeds " + MAX_PACKET);
        }
        synchronized (this)
        {
            if (!connected) { throw new IOException("HTTP link closed during POST"); }
            while (responseCount == MAX_RESPONSES && connected)
            {
                try { wait(); }
                catch (InterruptedException e) { throw new IOException("HTTP response queue interrupted"); }
            }
            if (!connected) { throw new IOException("HTTP link closed during POST"); }
            int tail = (responseHead + responseCount) % MAX_RESPONSES;
            responses[tail] = r.body;
            responseCount++;
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
        current = new byte[0];
        notifyAll();
    }
    public synchronized long bytesRead() { return rx; }
    public synchronized long bytesWritten() { return tx; }
    public String description() { return "http " + url; }
    public boolean isRequestResponse() { return true; }
    public int hiddenPaddingBlocks() { return 0; }
}
