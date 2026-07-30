package tg.ui;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;

import tg.diag.Diag;
import tg.plat.MidpTransport;

/** Connect-only probe for a Telegram DC; it never sends malformed bytes. */
public final class SocketConnectScreen extends Form
{
    public static final Command CMD_RUN = new Command("Connect", Command.OK, 1);
    private final String host;
    private final int port;
    private final StringItem result;
    private boolean running;
    private int attempt;

    public SocketConnectScreen(String host, int port)
    {
        super("Telegram socket");
        this.host = host;
        this.port = port;
        append("Connect-only test to " + host + ":" + port
               + ". Success proves Telegram is reachable; no MTProto bytes are sent.");
        result = new StringItem("Result", "not run");
        append(result);
    }

    public synchronized void start()
    {
        if (running) { return; }
        running = true;
        final int id = ++attempt;
        result.setText("connecting...");
        final Thread worker = new Thread(new Runnable()
        {
            public void run()
            {
                MidpTransport t = new MidpTransport();
                long t0 = System.currentTimeMillis();
                try
                {
                    t.connect(host, port, 30000);
                    complete(id, "PASS in " + (System.currentTimeMillis() - t0)
                                   + " ms\nremote " + t.remoteInfo());
                }
                catch (Throwable e)
                {
                    Diag.error("Telegram socket probe failed", e);
                    complete(id, "FAIL\n" + Diag.className(e) + "\n"
                                   + String.valueOf(e.getMessage()));
                }
                finally
                {
                    t.close();
                }
            }
        });
        worker.start();

        // MIDP has no connect timeout and several VMs ignore Thread.interrupt()
        // inside Connector.open. At least make a silently dropped port visible
        // instead of leaving the user at "connecting..." forever. A timed-out
        // Connector thread may remain blocked, so another attempt is disabled
        // until the MIDlet is restarted.
        new Thread(new Runnable()
        {
            public void run()
            {
                try { Thread.sleep(30000); }
                catch (InterruptedException ignored) { return; }
                synchronized (SocketConnectScreen.this)
                {
                    if (running && attempt == id)
                    {
                        attempt++; // invalidate any late worker result
                        result.setText("TIMEOUT after 30000 ms\n"
                                + "Connector.open is still blocked.\n"
                                + "Restart the MIDlet before retrying.");
                        Diag.warn("Telegram socket probe timed out at "
                                  + host + ":" + port);
                        worker.interrupt();
                    }
                }
            }
        }).start();
    }

    private synchronized void complete(int id, String text)
    {
        if (attempt != id) { return; }
        result.setText(text);
        running = false;
    }
}
