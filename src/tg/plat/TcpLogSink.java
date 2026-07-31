package tg.plat;

import java.io.IOException;
import java.io.OutputStream;

import javax.microedition.io.Connector;
import javax.microedition.io.SocketConnection;

import tg.app.DevSink;
import tg.diag.DiagSink;
import tg.tl.Utf8;

/**
 * Streams diagnostic lines to tools/log-server.py over plain TCP.
 *
 * Development only. It carries formatted log text and nothing else - never
 * Telegram protocol traffic, never anything the device is supposed to own.
 * Enable it explicitly from the probe UI; it is not wired up by default,
 * because on a metered GPRS connection it costs the user money.
 *
 * Design constraints that come from where this runs:
 *   - logging must never block the caller, so lines go to a bounded queue and a
 *     writer thread drains it; if the queue fills, lines are dropped rather
 *     than backing pressure up into the network or crypto layer;
 *   - a broken sink must be silent, not fatal. Diag already detaches a sink
 *     that throws, and this one additionally stops trying after the connection
 *     dies.
 */
public final class TcpLogSink implements DiagSink, Runnable
{
    /** Bounded: dropping diagnostics beats stalling the thing being diagnosed. */
    private static final int QUEUE_SIZE = 64;

    private final String host;
    private final int port;

    /**
     * First line written after connecting, or null for none.
     *
     * tools/log-server.py accepts anything and needs no greeting.
     * tools/ingest-server.py sits on a public address and requires
     * "&lt;token&gt; &lt;target&gt; &lt;device&gt;" before it will keep the
     * connection, so which server is on the other end decides whether this is
     * set - see {@link #forCollector}.
     */
    private final String greeting;

    private final String[] queue = new String[QUEUE_SIZE];
    private int head;
    private int tail;
    private int size;
    private int droppedLines;

    private volatile boolean running;
    private SocketConnection conn;
    private OutputStream out;

    public TcpLogSink(String host, int port)
    {
        this(host, port, null);
    }

    public TcpLogSink(String host, int port, String greeting)
    {
        this.host = host;
        this.port = port;
        this.greeting = greeting;
    }

    /**
     * Sink aimed at the development collector described by the build, or null
     * when this build has no sink configured.
     *
     * @param target "probe", "crypto" or "tg"
     */
    public static TcpLogSink forCollector(String target)
    {
        if (!DevSink.CONFIGURED) { return null; }
        String device = (DevSink.DEVICE == null || DevSink.DEVICE.length() == 0)
                ? "unknown" : DevSink.DEVICE;
        return new TcpLogSink(DevSink.TCP_HOST, DevSink.TCP_PORT,
                DevSink.TOKEN + " " + target + " " + device);
    }

    /** Connects on a background thread; returns immediately. */
    public void start()
    {
        if (running) { return; }
        running = true;
        new Thread(this).start();
    }

    public void stop()
    {
        running = false;
        synchronized (this) { notifyAll(); }
    }

    public int dropped()
    {
        return droppedLines;
    }

    // --------------------------------------------------------- DiagSink

    public synchronized void write(String line)
    {
        if (!running) { return; }
        if (size == QUEUE_SIZE)
        {
            droppedLines++;
            return;
        }
        queue[tail] = line;
        tail = (tail + 1) % QUEUE_SIZE;
        size++;
        notifyAll();
    }

    // --------------------------------------------------------- Runnable

    public void run()
    {
        try
        {
            conn = (SocketConnection) Connector.open(
                    "socket://" + host + ":" + port, Connector.WRITE, true);
            out = conn.openOutputStream();

            if (greeting != null)
            {
                // Before anything from the queue: the collector drops the
                // connection if the first line does not authenticate.
                out.write(Utf8.encode(greeting));
                out.write('\n');
                out.flush();
            }

            while (running)
            {
                String line;
                synchronized (this)
                {
                    while (running && size == 0)
                    {
                        try { wait(1000); }
                        catch (InterruptedException e) { /* CLDC cannot interrupt */ }
                    }
                    if (!running) { break; }
                    line = queue[head];
                    queue[head] = null;
                    head = (head + 1) % QUEUE_SIZE;
                    size--;
                }

                // String.getBytes() follows microedition.encoding, which is
                // device/vendor-specific.  The collector protocol is UTF-8.
                out.write(Utf8.encode(line));
                out.write('\n');
                out.flush();
            }
        }
        catch (IOException e)
        {
            // Not reported through Diag: this IS the log path, and recursing
            // into it while it is broken helps nobody.
            running = false;
        }
        catch (Throwable t)
        {
            running = false;
        }
        finally
        {
            close();
        }
    }

    private void close()
    {
        running = false;
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
}
