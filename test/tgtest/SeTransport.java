package tgtest;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import tg.io.Transport;

/**
 * Desktop implementation of {@link Transport}, on java.net.Socket.
 *
 * This is the single most useful piece of test infrastructure in the project.
 * Because every layer above Transport - framing, crypto, TL, MTProto, the
 * Telegram API - is written in the CLDC subset and knows nothing about sockets,
 * swapping this in lets the entire stack run against a real Telegram data
 * centre from a desktop JVM: full speed, real debugger, real stack traces, no
 * emulator, no handset.
 *
 * Anything proved here still has to be re-proved on the device, but bugs found
 * here cost minutes instead of a sideload cycle.
 */
public final class SeTransport implements Transport
{
    private Socket socket;
    private InputStream in;
    private OutputStream out;

    private long rx;
    private long tx;

    /** Read timeout applied to the socket; 0 means block forever. */
    private int readTimeoutMs = 30000;

    public void setReadTimeoutMs(int ms)
    {
        readTimeoutMs = ms;
        if (socket != null)
        {
            try { socket.setSoTimeout(ms); } catch (IOException ignored) { }
        }
    }

    public void connect(String host, int port, int timeoutMs) throws IOException
    {
        close();
        socket = new Socket();
        // Unlike MIDP, we can bound the connect here - a hung DC should fail
        // the test rather than hang the run.
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(readTimeoutMs);
        in = socket.getInputStream();
        out = new BufferedOutputStream(socket.getOutputStream(), 4096);
        rx = 0;
        tx = 0;
    }

    public int read(byte[] buf, int off, int len) throws IOException
    {
        if (in == null) { throw new IOException("not connected"); }
        int n = in.read(buf, off, len);
        if (n > 0) { rx += n; }
        return n;
    }

    public void readFully(byte[] buf, int off, int len) throws IOException
    {
        int got = 0;
        while (got < len)
        {
            int n = read(buf, off + got, len - got);
            if (n < 0)
            {
                throw new IOException("eof after " + got + "/" + len);
            }
            got += n;
        }
    }

    public void write(byte[] buf, int off, int len) throws IOException
    {
        if (out == null) { throw new IOException("not connected"); }
        out.write(buf, off, len);
        tx += len;
    }

    public void flush() throws IOException
    {
        if (out != null) { out.flush(); }
    }

    public boolean isConnected()
    {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public void close()
    {
        if (in != null)
        {
            try { in.close(); } catch (IOException ignored) { }
            in = null;
        }
        if (out != null)
        {
            try { out.close(); } catch (IOException ignored) { }
            out = null;
        }
        if (socket != null)
        {
            try { socket.close(); } catch (IOException ignored) { }
            socket = null;
        }
    }

    public long bytesRead()
    {
        return rx;
    }

    public long bytesWritten()
    {
        return tx;
    }
}
