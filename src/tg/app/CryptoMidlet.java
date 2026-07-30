package tg.app;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.List;
import javax.microedition.midlet.MIDlet;

import tg.crypto.Entropy;
import tg.crypto.Pbkdf2;
import tg.crypto.SelfTest;
import tg.diag.CrashLog;
import tg.diag.Diag;
import tg.io.Hex;
import tg.ui.TextScreen;

/**
 * Crypto verification and benchmark MIDlet.
 *
 * Separate from {@link ProbeMidlet} on purpose. The probe has to be as small as
 * possible because it is the first thing installed on an unknown handset; this
 * one carries the whole crypto stack including the ported BigInteger, and is
 * installed second, once the probe has shown that the phone runs our JARs at
 * all.
 *
 * What it answers:
 *
 *   - do the FIPS/OpenSSL vectors still hold after this toolchain compiled,
 *     preverified and shrank the code, on this VM;
 *   - how long a 2048-bit modular exponentiation takes, which is the cost of
 *     generating an auth_key and the number the project's viability rests on;
 *   - how fast SHA-256 and AES-IGE are, which bounds message and media
 *     throughput;
 *   - what the entropy sources look like, which is still an open question.
 *
 * Everything runs on a worker thread. The benchmark can take minutes on a
 * 208 MHz CPU, and blocking the UI thread that long looks like a hang and can
 * trip the AMS watchdog.
 */
public class CryptoMidlet extends MIDlet implements CommandListener
{
    private static final String[] MENU_ITEMS = {
        "Run vectors",
        "Run benchmarks",
        "PBKDF2 x100000",
        "Entropy sample",
        "Diagnostic log"
    };

    private static final int ITEM_VECTORS = 0;
    private static final int ITEM_BENCH   = 1;
    private static final int ITEM_PBKDF2  = 2;
    private static final int ITEM_ENTROPY = 3;
    private static final int ITEM_LOG     = 4;

    private final Command cmdExit = new Command("Exit", Command.EXIT, 10);
    private final Command cmdBack = new Command("Back", Command.BACK, 1);
    private final Command cmdCancel = new Command("Cancel", Command.CANCEL, 1);

    private Display display;
    private List menu;
    private volatile boolean pbkdf2Cancelled;

    protected void startApp()
    {
        if (display != null)
        {
            display.setCurrent(menu);
            return;
        }
        display = Display.getDisplay(this);

        Diag.info("crypto midlet " + BuildInfo.VERSION + " build " + BuildInfo.BUILD);
        Diag.mem("startup");

        menu = new List("Crypto " + BuildInfo.VERSION, List.IMPLICIT, MENU_ITEMS, null);
        menu.addCommand(cmdExit);
        menu.setCommandListener(this);
        display.setCurrent(menu);
    }

    protected void pauseApp()
    {
        Diag.info("pauseApp");
    }

    protected void destroyApp(boolean unconditional)
    {
        Diag.info("destroyApp unconditional=" + unconditional);
    }

    public void commandAction(Command c, Displayable d)
    {
        try
        {
            if (c == cmdCancel)
            {
                pbkdf2Cancelled = true;
            }
            else if (c == cmdExit)
            {
                destroyApp(true);
                notifyDestroyed();
            }
            else if (c == cmdBack)
            {
                display.setCurrent(menu);
            }
            else if (c == List.SELECT_COMMAND && d == menu)
            {
                select(menu.getSelectedIndex());
            }
        }
        catch (Throwable t)
        {
            Diag.error("command failed", t);
            CrashLog.save("crypto-ui", t);
            show("Error", new String[] {
                Diag.className(t),
                String.valueOf(t.getMessage()),
                "",
                "recorded in the crash log"
            });
        }
    }

    private void select(int index)
    {
        switch (index)
        {
            case ITEM_VECTORS:
                runAsync("Vectors", new String[] {
                    "running FIPS 180-4 / FIPS-197 /",
                    "OpenSSL IGE vectors...",
                    "",
                    "these are the same vectors that",
                    "pass on the desktop. any FAIL",
                    "here is a toolchain problem,",
                    "not an algorithm problem."
                }, true);
                break;

            case ITEM_BENCH:
                runAsync("Benchmarks", new String[] {
                    "running benchmarks...",
                    "",
                    "the 2048-bit modPow can take",
                    "a long time on this hardware.",
                    "please wait - do not exit."
                }, false);
                break;

            case ITEM_ENTROPY:
                showEntropy();
                break;

            case ITEM_PBKDF2:
                runPbkdf2();
                break;

            case ITEM_LOG:
                show("Log", Diag.snapshot());
                break;

            default:
                break;
        }
    }

    /**
     * @param vectors true to run the self-test, false to run the benchmark
     */
    private void runAsync(String title, String[] placeholder, final boolean vectors)
    {
        final TextScreen screen = new TextScreen(title, placeholder);
        screen.addCommand(cmdBack);
        screen.setCommandListener(this);
        display.setCurrent(screen);

        new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    long t0 = System.currentTimeMillis();
                    String[] lines;
                    if (vectors)
                    {
                        SelfTest.Result r = SelfTest.run();
                        lines = r.lines;
                        Diag.info("selftest passed=" + r.passed + " failed=" + r.failed);
                    }
                    else
                    {
                        lines = SelfTest.benchmark();
                        Diag.info("benchmark complete");
                    }

                    String[] withTime = new String[lines.length + 1];
                    System.arraycopy(lines, 0, withTime, 0, lines.length);
                    withTime[lines.length] =
                            "total " + (System.currentTimeMillis() - t0) + " ms";
                    screen.setLines(withTime);
                    Diag.mem("after " + (vectors ? "vectors" : "benchmark"));
                }
                catch (Throwable t)
                {
                    Diag.error("crypto run failed", t);
                    CrashLog.save(vectors ? "selftest" : "benchmark", t);
                    screen.setLines(new String[] {
                        "FAILED",
                        Diag.className(t),
                        String.valueOf(t.getMessage()),
                        "",
                        "recorded in the crash log"
                    });
                }
            }
        }).start();
    }

    private void runPbkdf2()
    {
        final TextScreen screen = new TextScreen("PBKDF2 x100000", new String[] {
            "PBKDF2-HMAC-SHA512",
            "0 / 100000 (0%)",
            "",
            "Cancel stops after the current",
            "1000-iteration batch."
        });
        pbkdf2Cancelled = false;
        screen.addCommand(cmdCancel);
        screen.setCommandListener(this);
        display.setCurrent(screen);

        new Thread(new Runnable()
        {
            public void run()
            {
                final long started = System.currentTimeMillis();
                try
                {
                    String[] lines = SelfTest.benchmarkPbkdf2(new Pbkdf2.Progress()
                    {
                        public boolean update(int completed, int total)
                        {
                            long elapsed = System.currentTimeMillis() - started;
                            screen.setLines(new String[] {
                                "PBKDF2-HMAC-SHA512",
                                completed + " / " + total + " ("
                                    + (completed * 100L / total) + "%)",
                                "elapsed = " + elapsed + " ms",
                                "",
                                "Cancel stops after the current",
                                "1000-iteration batch."
                            });
                            return !pbkdf2Cancelled;
                        }
                    });
                    screen.removeCommand(cmdCancel);
                    screen.addCommand(cmdBack);
                    screen.setLines(lines);
                    Diag.info("PBKDF2 benchmark complete in "
                              + (System.currentTimeMillis() - started) + " ms");
                    Diag.mem("after PBKDF2");
                }
                catch (IllegalStateException cancelled)
                {
                    screen.removeCommand(cmdCancel);
                    screen.addCommand(cmdBack);
                    screen.setLines(new String[] {
                        "CANCELLED",
                        "elapsed = " + (System.currentTimeMillis() - started) + " ms"
                    });
                    Diag.info("PBKDF2 benchmark cancelled");
                }
                catch (Throwable t)
                {
                    screen.removeCommand(cmdCancel);
                    screen.addCommand(cmdBack);
                    Diag.error("PBKDF2 benchmark failed", t);
                    CrashLog.save("pbkdf2", t);
                    screen.setLines(new String[] {
                        "FAILED",
                        Diag.className(t),
                        String.valueOf(t.getMessage())
                    });
                }
            }
        }).start();
    }

    /**
     * Shows what the entropy collector actually produces here. On a handset
     * with no hardware RNG this is the evidence for - or against - the
     * seeding strategy, so the raw samples are displayed rather than a verdict.
     */
    private void showEntropy()
    {
        final TextScreen screen = new TextScreen("Entropy", new String[] { "sampling..." });
        screen.addCommand(cmdBack);
        screen.setCommandListener(this);
        display.setCurrent(screen);

        new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    String[] out = new String[9];
                    out[0] = "three independent gather() samples;";
                    out[1] = "they MUST differ from each other.";
                    out[2] = "";
                    for (int i = 0; i < 3; i++)
                    {
                        out[3 + i] = Hex.encode(Entropy.gather(), 0, 16);
                    }
                    out[6] = "";
                    out[7] = "jitter (120 ms) = "
                             + Hex.encode(Entropy.collectJitter(120), 0, 16);
                    out[8] = "estimated bits/gather = "
                             + Entropy.estimatedBitsPerGather() + " (unmeasured)";
                    screen.setLines(out);
                }
                catch (Throwable t)
                {
                    Diag.error("entropy sample failed", t);
                    screen.setLines(new String[] { "FAILED", Diag.className(t) });
                }
            }
        }).start();
    }

    private void show(String title, String[] lines)
    {
        TextScreen screen = new TextScreen(title, lines);
        screen.addCommand(cmdBack);
        screen.setCommandListener(this);
        display.setCurrent(screen);
    }
}
