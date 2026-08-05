package tg.ui;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;

import tg.diag.Diag;
import tg.plat.MidpTransport;

/**
 * Can this handset hold two sockets open at once?
 *
 * A Samsung GT-C3592 cannot. The second {@code Connector.open} throws
 * {@code ConnectionNotFoundException: socket open: failed} - and, worse, the
 * attempt corrupts the connection already in use, which surfaced as an
 * unrelated-looking {@code invalid FakeTLS application record}. That is why the
 * client routes media over the session connection and why Settings carries a
 * "Single socket mode" switch at all.
 *
 * The test is therefore not just "does the second open succeed" but "is the
 * first one still usable afterwards", because on the handset that failed, the
 * damage was the part that cost days to find.
 */
public final class TwoSocketScreen extends Form
{
    public static final Command CMD_RUN = new Command("Run", Command.SCREEN, 1);

    private static final int TIMEOUT_MS = 30000;

    private final String host;
    private final int port;
    private final StringItem result;
    private boolean running;

    private String[] lines = new String[] { "not run" };

    public TwoSocketScreen(String host, int port)
    {
        super("Two sockets");
        this.host = host;
        this.port = port;
        append("Opens a second socket to " + host + ":" + port
               + " while the first is still open, then checks the first still"
               + " works. A handset that refuses needs Single socket mode.");
        result = new StringItem("Result", "not run");
        append(result);
    }

    public String[] snapshot()
    {
        return lines;
    }

    public synchronized void start()
    {
        if (running) { return; }
        running = true;
        result.setText("running...");

        new Thread(new Runnable()
        {
            public void run()
            {
                MidpTransport first = new MidpTransport();
                MidpTransport second = new MidpTransport();
                String firstState = "not opened";
                String secondState = "not attempted";
                String afterState = "not checked";

                try
                {
                    long t0 = System.currentTimeMillis();
                    first.connect(host, port, TIMEOUT_MS);
                    firstState = "OK in " + (System.currentTimeMillis() - t0)
                            + " ms";

                    try
                    {
                        long t1 = System.currentTimeMillis();
                        second.connect(host, port, TIMEOUT_MS);
                        secondState = "OK in "
                                + (System.currentTimeMillis() - t1) + " ms";
                    }
                    catch (Throwable t)
                    {
                        secondState = "REFUSED " + Diag.className(t) + ": "
                                + String.valueOf(t.getMessage());
                    }

                    // The part that matters. A refusal is survivable; a refusal
                    // that also breaks the working socket is not, and only this
                    // check tells them apart.
                    afterState = probeFirst(first);
                }
                catch (Throwable t)
                {
                    firstState = "FAILED " + Diag.className(t) + ": "
                            + String.valueOf(t.getMessage());
                }
                finally
                {
                    second.close();
                    first.close();
                }

                boolean bothOpen = secondState.startsWith("OK");
                complete(new String[] {
                    "two concurrent sockets to " + host + ":" + port,
                    "first  : " + firstState,
                    "second : " + secondState,
                    "first after second: " + afterState,
                    "",
                    bothOpen
                        ? "VERDICT: concurrent sockets work."
                        : "VERDICT: one socket at a time.",
                    bothOpen
                        ? "Single socket mode can stay optional."
                        : "Single socket mode is required here."
                });
            }

            /** Write a byte and read the echo back; the echo host replies. */
            private String probeFirst(MidpTransport t)
            {
                try
                {
                    byte[] payload = { 'J', '2', 'M', 'E', '\n' };
                    t.write(payload, 0, payload.length);
                    t.flush();
                    byte[] buf = new byte[16];
                    int n = t.read(buf, 0, buf.length);
                    return n > 0 ? "still usable (" + n + " B echoed)"
                            : "closed (read returned " + n + ")";
                }
                catch (Throwable e)
                {
                    return "BROKEN " + Diag.className(e) + ": "
                            + String.valueOf(e.getMessage());
                }
            }
        }).start();
    }

    private synchronized void complete(String[] text)
    {
        lines = text;
        StringBuffer sb = new StringBuffer();
        for (int i = 1; i < text.length; i++)
        {
            if (i > 1) { sb.append('\n'); }
            sb.append(text[i]);
        }
        result.setText(sb.toString());
        running = false;
    }
}
