package tg.plat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.microedition.io.Connector;
import javax.microedition.io.SocketConnection;

import tg.diag.Diag;
import tg.io.Transport;

/**
 * Transport backed by MIDP 2.0 raw sockets.
 *
 * This is the only place in the device build that touches
 * javax.microedition.io. An AMS may prompt before allowing "socket://", may
 * silently refuse it, or may only permit HTTP. The probe build exists to
 * discover that behaviour, so failures here are reported precisely rather than
 * swallowed.
 */
public final class MidpTransport implements Transport
{
    private SocketConnection conn;
    private InputStream in;
    private OutputStream out;

    private long rx;
    private long tx;

    public void connect(String host, int port, int timeoutMs) throws IOException
    {
        close();

        String url = "socket://" + host + ":" + port;
        Diag.info("net connect " + url);
        long t0 = System.currentTimeMillis();

        // MIDP has no connect timeout. Connector.open blocks for as long as the
        // AMS decides to; timeoutMs is accepted for interface symmetry and
        // honoured only by the desktop implementation.
        conn = (SocketConnection) Connector.open(url, Connector.READ_WRITE, true);

        // Nagle would batch our small MTProto frames into extra round trips on
        // a link that already has GPRS latency. Optional on MIDP: several
        // handsets throw, and that is not fatal.
        try
        {
            conn.setSocketOption(SocketConnection.DELAY, 0);
        }
        catch (Throwable t)
        {
            Diag.warn("net DELAY option rejected: " + Diag.className(t));
        }

        in = conn.openInputStream();
        out = conn.openOutputStream();
        rx = 0;
        tx = 0;

        Diag.info("net connected in " + (System.currentTimeMillis() - t0) + " ms");
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
        return conn != null;
    }

    public void close()
    {
        // Order matters and every step is independent: a stream that fails to
        // close must not leave the connection open.
        if (in != null)
        {
            try { in.close(); } catch (Throwable ignored) { }
            in = null;
        }
        if (out != null)
        {
            try { out.close(); } catch (Throwable ignored) { }
            out = null;
        }
        if (conn != null)
        {
            try { conn.close(); } catch (Throwable ignored) { }
            conn = null;
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

    /** Peer address as the handset resolved it - useful when DNS is suspect. */
    public String remoteInfo()
    {
        if (conn == null) { return "not connected"; }
        try
        {
            return conn.getAddress() + ":" + conn.getPort();
        }
        catch (Throwable t)
        {
            return "unavailable (" + Diag.className(t) + ")";
        }
    }

    /** Local address, which on GPRS reveals the operator NAT pool. */
    public String localInfo()
    {
        if (conn == null) { return "not connected"; }
        try
        {
            return conn.getLocalAddress() + ":" + conn.getLocalPort();
        }
        catch (Throwable t)
        {
            return "unavailable (" + Diag.className(t) + ")";
        }
    }
}
