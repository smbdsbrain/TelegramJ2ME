package tg.ui;

import java.io.IOException;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextField;

import tg.diag.Diag;
import tg.plat.MidpTransport;

/**
 * Raw TCP feasibility screen.
 *
 * This is the hard gate for direct MTProto: some unsigned MIDlets cannot open
 * a plain "socket://" connection on their network. The test is deliberately
 * dumb - connect to tools/echo-server.py, send bytes, read them back - and
 * reports the failure verbatim rather than replacing it with a friendly message.
 *
 * The work runs on its own thread: MIDP delivers commandAction on the UI
 * thread, and blocking it during a GPRS connect freezes the display and can
 * trip the AMS watchdog.
 */
public class NetScreen extends Form
{
    /** Bounded so a misbehaving peer cannot make us allocate without limit. */
    private static final int READ_BUFFER = 512;

    public static final Command CMD_RUN = new Command("Run test", Command.SCREEN, 1);

    private final TextField host;
    private final TextField port;
    private final TextField payload;
    private final StringItem result;

    private boolean running;

    public NetScreen(String defaultHost, int defaultPort)
    {
        this(defaultHost, defaultPort, "PING");
    }

    public NetScreen(String defaultHost, int defaultPort, String defaultPayload)
    {
        super("Raw TCP test");

        host = new TextField("Host", defaultHost, 64, TextField.ANY);
        port = new TextField("Port", String.valueOf(defaultPort), 6, TextField.NUMERIC);
        payload = new TextField("Send", defaultPayload, 64, TextField.ANY);
        result = new StringItem("Result", "not run yet\n(start tools/echo-server.py first)");

        append(host);
        append(port);
        append(payload);
        append(result);
    }

    /** Kick off the test unless one is already in flight. */
    public synchronized void start()
    {
        if (running)
        {
            return;
        }
        running = true;
        result.setText("running...");

        final String h = host.getString().trim();
        final String p = port.getString().trim();
        final String data = payload.getString();

        new Thread(new Runnable()
        {
            public void run()
            {
                String text;
                try
                {
                    text = execute(h, parsePort(p), data);
                }
                catch (Throwable t)
                {
                    // Anything at all, including Errors: this screen must
                    // survive whatever the device's socket stack does.
                    Diag.error("tcp test failed", t);
                    text = "FAILED\n" + Diag.className(t) + "\n" + t.getMessage();
                }
                result.setText(text);
                synchronized (NetScreen.this) { running = false; }
            }
        }).start();
    }

    // ------------------------------------------------------------ internal

    private static int parsePort(String s)
    {
        try
        {
            int v = Integer.parseInt(s);
            if (v > 0 && v < 65536) { return v; }
        }
        catch (Throwable ignored) { }
        return 7777;
    }

    private static String execute(String h, int p, String data) throws IOException
    {
        MidpTransport t = new MidpTransport();
        StringBuffer sb = new StringBuffer(256);
        long t0 = System.currentTimeMillis();

        try
        {
            t.connect(h, p, 30000);
            long tConnect = System.currentTimeMillis() - t0;
            sb.append("connected in ").append(tConnect).append(" ms\n");
            sb.append("local  ").append(t.localInfo()).append('\n');
            sb.append("remote ").append(t.remoteInfo()).append('\n');

            byte[] outBytes = data.getBytes();
            long t1 = System.currentTimeMillis();
            t.write(outBytes, 0, outBytes.length);
            t.flush();
            Diag.hex("tx", outBytes);

            byte[] buf = new byte[READ_BUFFER];
            int n = t.read(buf, 0, buf.length);
            long rtt = System.currentTimeMillis() - t1;

            if (n < 0)
            {
                sb.append("peer closed before sending anything\n");
            }
            else
            {
                Diag.hex("rx", buf, 0, n);
                sb.append("echo ").append(n).append(" bytes in ").append(rtt).append(" ms\n");
                sb.append("text: ").append(new String(buf, 0, n)).append('\n');
                sb.append(matches(outBytes, buf, n) ? "PAYLOAD MATCHES\n" : "payload differs\n");
            }
            sb.append("rx=").append(t.bytesRead()).append(" tx=").append(t.bytesWritten());
        }
        finally
        {
            t.close();
        }
        return sb.toString();
    }

    private static boolean matches(byte[] sent, byte[] got, int gotLen)
    {
        if (gotLen != sent.length) { return false; }
        for (int i = 0; i < gotLen; i++)
        {
            if (sent[i] != got[i]) { return false; }
        }
        return true;
    }
}
