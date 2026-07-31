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
import tg.plat.EntropyLog;
import tg.plat.EntropyProbe;
import tg.plat.ReportUpload;
import tg.ui.KeyTimingScreen;
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
        "Entropy measure",
        "Key timing",
        "Diagnostic log"
    };

    private static final int ITEM_VECTORS = 0;
    private static final int ITEM_BENCH   = 1;
    private static final int ITEM_PBKDF2  = 2;
    private static final int ITEM_ENTROPY = 3;
    private static final int ITEM_KEYTIME = 4;
    private static final int ITEM_LOG     = 5;

    private final Command cmdExit = new Command("Exit", Command.EXIT, 10);
    private final Command cmdBack = new Command("Back", Command.BACK, 1);
    private final Command cmdCancel = new Command("Cancel", Command.CANCEL, 1);
    private final Command cmdReport = new Command("Report", Command.SCREEN, 2);
    private final Command cmdUpload = new Command("Upload", Command.SCREEN, 4);

    /** Which MIDlet the collector files these reports under. */
    private static final String SINK_TARGET = "crypto";

    private Display display;
    private List menu;
    private KeyTimingScreen keyTimingScreen;
    private volatile boolean pbkdf2Cancelled;

    // What "Upload" would send. Set by every screen that shows a result, so the
    // command does not need to know which scenario produced it.
    private String pendingSection;
    private String[] pendingLines;

    protected void startApp()
    {
        // First statement on purpose: this is the evidence for whether the wall
        // clock survives a power cycle, so nothing may advance it first.
        final long startupMillis = System.currentTimeMillis();

        if (display != null)
        {
            display.setCurrent(menu);
            return;
        }
        display = Display.getDisplay(this);

        Diag.info("crypto midlet " + BuildInfo.VERSION + " build " + BuildInfo.BUILD);
        Diag.mem("startup");

        // Record this launch's seed so a later launch can prove it never
        // repeats. Off the UI thread - two gathers cost ~240 ms - using the
        // timestamp taken on the first line of this method.
        new Thread(new Runnable()
        {
            public void run()
            {
                Diag.info("entropy " + EntropyLog.recordLaunch("crypto", startupMillis));
            }
        }).start();

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
            else if (c == cmdReport && keyTimingScreen != null)
            {
                show("Key timing", keyTimingScreen.snapshot());
            }
            else if (c == cmdUpload)
            {
                uploadPending();
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

            case ITEM_KEYTIME:
                if (keyTimingScreen == null)
                {
                    keyTimingScreen = new KeyTimingScreen();
                    keyTimingScreen.addCommand(cmdBack);
                    keyTimingScreen.addCommand(cmdReport);
                    keyTimingScreen.setCommandListener(this);
                }
                display.setCurrent(keyTimingScreen);
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
    private void runAsync(final String title, String[] placeholder, final boolean vectors)
    {
        final TextScreen screen = new TextScreen(title, placeholder);
        screen.addCommand(cmdBack);
        screen.addCommand(cmdUpload);
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
                    publish(title, withTime);
                    screen.setLines(withTime);
                    Diag.mem("after " + (vectors ? "vectors" : "benchmark"));
                }
                catch (Throwable t)
                {
                    Diag.error("crypto run failed", t);
                    CrashLog.save(vectors ? "selftest" : "benchmark", t);
                    String[] failure = new String[] {
                        "FAILED",
                        Diag.className(t),
                        String.valueOf(t.getMessage()),
                        "",
                        "recorded in the crash log"
                    };
                    publish(title, failure);
                    screen.setLines(failure);
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
     * Measures the entropy sources rather than merely sampling them.
     *
     * The three-gather comparison this screen used to be is kept as the first
     * thing it prints: it is a real assertion, it costs nothing, and a failure
     * there makes everything below it moot. What follows is
     * {@link EntropyProbe}, which quantifies the sources instead of inviting
     * the reader to eyeball three hex strings for "no obvious repetition" - the
     * verdict this screen's previous version actually produced on hardware.
     */
    private void showEntropy()
    {
        final TextScreen screen = new TextScreen("Entropy", new String[] {
            "measuring...",
            "",
            "up to 25 s. do not exit."
        });
        screen.addCommand(cmdBack);
        screen.setCommandListener(this);
        display.setCurrent(screen);

        new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    String[] head = new String[6];
                    head[0] = "three gather() samples;";
                    head[1] = "they MUST differ.";
                    for (int i = 0; i < 3; i++)
                    {
                        head[2 + i] = Hex.encode(Entropy.gather(), 0, 8);
                    }
                    head[5] = "estimated bits/gather = "
                              + Entropy.estimatedBitsPerGather()
                              + (Entropy.estimatedBitsPerGather() == 0
                                 ? " (unmeasured)" : "");

                    String[] body = EntropyProbe.run(new EntropyProbe.Progress()
                    {
                        public void step(String what, int done, int total)
                        {
                            screen.setLines(new String[] {
                                "Entropy probe",
                                "[" + done + "/" + total + "] " + what,
                                "",
                                "do not exit."
                            });
                        }
                    });

                    String[] out = new String[head.length + 1 + body.length];
                    System.arraycopy(head, 0, out, 0, head.length);
                    out[head.length] = "";
                    System.arraycopy(body, 0, out, head.length + 1, body.length);
                    screen.setLines(out);
                }
                catch (Throwable t)
                {
                    Diag.error("entropy measure failed", t);
                    CrashLog.save("entropy", t);
                    screen.setLines(new String[] {
                        "FAILED", Diag.className(t), String.valueOf(t.getMessage())
                    });
                }
            }
        }).start();
    }

    private void show(String title, String[] lines)
    {
        pendingSection = title;
        pendingLines = lines;

        TextScreen screen = new TextScreen(title, lines);
        screen.addCommand(cmdBack);
        screen.addCommand(cmdUpload);
        screen.setCommandListener(this);
        display.setCurrent(screen);
    }

    /**
     * Make a result reachable by the Upload command.
     *
     * The vectors, benchmark and PBKDF2 runs all finish on a worker thread, so
     * without this the command would still be offering whatever was on screen
     * when the run started.
     */
    private void publish(String section, String[] lines)
    {
        pendingSection = section;
        pendingLines = lines;
    }

    /**
     * Send the result currently on screen.
     *
     * The number worth carrying off this handset is the 2048-bit modPow timing
     * from "Run benchmarks": it is what an auth_key handshake costs, and it
     * decides whether the client is usable on this hardware at all.
     */
    private void uploadPending()
    {
        if (pendingLines == null)
        {
            show("Upload", new String[] { "nothing to upload yet" });
            return;
        }

        final TextScreen screen = new TextScreen("Upload", new String[] { "starting..." });
        screen.addCommand(cmdBack);
        screen.setCommandListener(this);
        display.setCurrent(screen);

        ReportUpload.send(SINK_TARGET, pendingSection, pendingLines,
                new ReportUpload.Progress()
                {
                    public void lines(String[] text) { screen.setLines(text); }
                });
    }
}
