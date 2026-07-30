package tg.ui;

import java.io.IOException;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;

import tg.diag.Diag;
import tg.plat.MidpTransport;

/** Holds an echo socket across pauseApp/startApp and reports whether it survived. */
public final class BackgroundSocketScreen extends Form
{
    public static final Command CMD_ARM = new Command("Arm test", Command.SCREEN, 1);

    private final StringItem result;
    private MidpTransport transport;
    private long pausedAt;
    private boolean armed;
    private boolean running;

    public BackgroundSocketScreen()
    {
        super("Background socket");
        append("Arm the test, wait for READY, then background the MIDlet. "
               + "Return after at least one minute.");
        result = new StringItem("State", "not armed");
        append(result);
    }

    public synchronized void arm()
    {
        if (running) { return; }
        running = true;
        result.setText("connecting to tcpbin.com:4242...");
        new Thread(new Runnable()
        {
            public void run()
            {
                MidpTransport t = new MidpTransport();
                try
                {
                    t.connect("tcpbin.com", 4242, 30000);
                    exchange(t, "ARM\n");
                    synchronized (BackgroundSocketScreen.this)
                    {
                        transport = t;
                        armed = true;
                    }
                    result.setText("READY\nbackground the MIDlet now");
                    t = null;
                }
                catch (Throwable e)
                {
                    Diag.error("background socket arm failed", e);
                    result.setText("ARM FAIL\n" + Diag.className(e) + "\n"
                                   + String.valueOf(e.getMessage()));
                }
                finally
                {
                    if (t != null) { t.close(); }
                    synchronized (BackgroundSocketScreen.this) { running = false; }
                }
            }
        }).start();
    }

    public synchronized void onPause()
    {
        if (armed)
        {
            pausedAt = System.currentTimeMillis();
            result.setText("paused with socket open");
        }
    }

    public synchronized void onResume()
    {
        if (!armed || running || pausedAt == 0) { return; }
        running = true;
        final long elapsed = System.currentTimeMillis() - pausedAt;
        new Thread(new Runnable()
        {
            public void run()
            {
                MidpTransport t;
                synchronized (BackgroundSocketScreen.this) { t = transport; }
                try
                {
                    exchange(t, "RESUME\n");
                    result.setText("PASS\nsocket survived " + elapsed + " ms");
                }
                catch (Throwable e)
                {
                    Diag.error("background socket resume failed", e);
                    result.setText("FAIL after " + elapsed + " ms\n"
                                   + Diag.className(e) + "\n"
                                   + String.valueOf(e.getMessage()));
                }
                finally
                {
                    if (t != null) { t.close(); }
                    synchronized (BackgroundSocketScreen.this)
                    {
                        armed = false; running = false; transport = null; pausedAt = 0;
                    }
                }
            }
        }).start();
    }

    private static void exchange(MidpTransport t, String text) throws IOException
    {
        byte[] sent = text.getBytes();
        t.write(sent, 0, sent.length);
        t.flush();
        byte[] got = new byte[sent.length];
        t.readFully(got, 0, got.length);
        for (int i = 0; i < got.length; i++)
        {
            if (got[i] != sent[i]) { throw new IOException("echo mismatch at " + i); }
        }
    }
}
