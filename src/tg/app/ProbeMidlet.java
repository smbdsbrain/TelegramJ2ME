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
import tg.plat.Caps;
import tg.plat.ClockProbe;
import tg.plat.DisplayProbe;
import tg.plat.EntropyLog;
import tg.plat.EntropyProbe;
import tg.plat.HeapProbe;
import tg.plat.HttpReportSink;
import tg.plat.ImageProbe;
import tg.plat.ReportUpload;
import tg.plat.RmsCheck;
import tg.plat.TextProbe;
import tg.mt.Dc;
import tg.ui.BackgroundSocketScreen;
import tg.ui.DisplayScreen;
import tg.ui.KeyScreen;
import tg.ui.KeyTimingScreen;
import tg.ui.NetScreen;
import tg.ui.SocketConnectScreen;
import tg.ui.TextScreen;
import tg.ui.TwoSocketScreen;

/**
 * Hardware reconnaissance MIDlet - the one thing that goes onto an unknown
 * handset before the client does.
 *
 * It answers the questions the handoff lists as unresolved, in the order they
 * block the project: what CLDC/MIDP this firmware really is, how much heap a
 * MIDlet can hold, whether RMS persists across a restart, what the QWERTY keys
 * report, whether the crypto vectors still hold after this toolchain compiled
 * and shrank them, what a 2048-bit modPow costs, what the entropy sources are
 * worth - and, the hard gate, whether a raw TCP socket works at all.
 *
 * <h3>Why it is one MIDlet and not two</h3>
 * The crypto vectors and benchmarks used to live in a separate {@code crypto}
 * target, on the argument that this build had to stay tiny because it is
 * installed first. That was true and it cost more than it bought: a device
 * session meant installing two suites, running two menus and uploading two sets
 * of reports, and the figures that have to be read together - what a gather is
 * worth here, how many gathers the seeding barrier then takes, and how that
 * compares to the modPow it precedes - were split across both. One
 * <b>Upload all</b> that leaves a complete picture of a handset on the collector
 * is worth more than a smaller first install.
 *
 * The cost is real and is recorded in {@code docs/building.md}: this JAR now
 * carries the crypto stack including the ported BigInteger, so it is no longer
 * the smallest artifact this project can produce, and
 * {@code tools/build-size-ladder.ps1} can no longer pad it down to its lowest
 * rungs.
 */
public class ProbeMidlet extends MIDlet implements CommandListener
{
    private static final String[] MENU_ITEMS = {
        "Platform & build",
        "Heap probe",
        "RMS test",
        "Entropy measure",
        "Seeding barrier",
        "Crypto vectors",
        "Crypto benchmarks",
        "PBKDF2 x100000",
        "Clock & timers",
        "Text round trip",
        "Display caps",
        "Keys",
        "Key timing",
        "Display size",
        "Public TCP echo",
        "Telegram DC socket :80",
        "Telegram DC socket :443",
        "Telegram DC socket :5222",
        "Telegram DC socket :8443",
        "Two sockets at once",
        "PNG / JPEG decode",
        "Emoji sheet cost",
        "Background socket",
        "Diagnostic log",
        "Crash log",
        "Upload all"
    };

    private static final int ITEM_PLATFORM   = 0;
    private static final int ITEM_HEAP       = 1;
    private static final int ITEM_RMS        = 2;
    private static final int ITEM_ENTROPY    = 3;
    private static final int ITEM_BARRIER    = 4;
    private static final int ITEM_VECTORS    = 5;
    private static final int ITEM_BENCH      = 6;
    private static final int ITEM_PBKDF2     = 7;
    private static final int ITEM_CLOCK      = 8;
    private static final int ITEM_TEXT       = 9;
    private static final int ITEM_DISPLAY    = 10;
    private static final int ITEM_KEYS       = 11;
    private static final int ITEM_KEYTIME    = 12;
    private static final int ITEM_CANVAS     = 13;
    private static final int ITEM_NET        = 14;
    private static final int ITEM_TG_80      = 15;
    private static final int ITEM_TG_443     = 16;
    private static final int ITEM_TG_5222    = 17;
    private static final int ITEM_TG_8443    = 18;
    private static final int ITEM_TWO_SOCK   = 19;
    private static final int ITEM_IMAGE      = 20;
    private static final int ITEM_EMOJI      = 21;
    private static final int ITEM_BG         = 22;
    private static final int ITEM_LOG        = 23;
    private static final int ITEM_CRASH      = 24;
    private static final int ITEM_UPLOAD_ALL = 25;

    /** Which MIDlet the collector files these reports under. */
    private static final String SINK_TARGET = "probe";

    private final Command cmdExit    = new Command("Exit", Command.EXIT, 10);
    private final Command cmdBack    = new Command("Back", Command.BACK, 1);
    private final Command cmdRefresh = new Command("Refresh", Command.SCREEN, 2);
    // Named to match what KeyTimingScreen tells the user to press.
    private final Command cmdReport  = new Command("Report", Command.SCREEN, 2);
    private final Command cmdClear   = new Command("Clear", Command.SCREEN, 3);
    private final Command cmdUpload  = new Command("Upload", Command.SCREEN, 4);
    // Only PBKDF2 offers this: it is the one measurement here that runs for
    // minutes, and a tester who started it by mistake should not have to pull
    // the battery.
    private final Command cmdCancel  = new Command("Cancel", Command.CANCEL, 1);

    private Display display;
    private List menu;
    private volatile boolean pbkdf2Cancelled;

    private KeyScreen keyScreen;
    private KeyTimingScreen keyTimingScreen;
    private DisplayScreen displayScreen;
    private TextScreen entropyScreen;
    private NetScreen netScreen;
    private SocketConnectScreen telegramScreen;
    private TwoSocketScreen twoSocketScreen;
    private BackgroundSocketScreen backgroundScreen;
    private TextScreen logScreen;

    // What "Upload" would send. Set by every screen that displays a result, so
    // the command does not have to know which scenario produced it.
    private String pendingSection;
    private String[] pendingLines;

    // -------------------------------------------------------- MIDlet life

    protected void startApp()
    {
        // First statement in the method on purpose. This value is the evidence
        // for whether the wall clock survives a power cycle, so it has to be
        // read before anything else has had a chance to advance it.
        final long startupMillis = System.currentTimeMillis();

        if (display != null)
        {
            // Returning from pause: nothing to rebuild.
            display.setCurrent(menu);
            if (backgroundScreen != null) { backgroundScreen.onResume(); }
            return;
        }

        display = Display.getDisplay(this);

        Diag.info("probe start " + BuildInfo.VERSION + " build " + BuildInfo.BUILD);
        Diag.mem("startup");

        String platform = Caps.prop("microedition.platform");
        String config = Caps.prop("microedition.configuration");
        String profiles = Caps.prop("microedition.profiles");
        Diag.info("platform=" + platform);
        Diag.info("config=" + config + " profiles=" + profiles);

        // Written on every launch, read from the previous one - this is how we
        // learn whether RMS really survives MIDlet exit on this firmware.
        Diag.info("rms " + RmsCheck.checkPersistenceMarker());

        // Same idea, applied to the RNG seed: record what gather() produces this
        // launch so a later launch can prove it never repeats. Two gathers cost
        // about 240 ms of jitter collection, so it runs off the UI thread -
        // the timestamp it needs was already taken above.
        new Thread(new Runnable()
        {
            public void run()
            {
                Diag.info("entropy " + EntropyLog.recordLaunch("probe", startupMillis));
            }
        }).start();

        menu = new List("Probe " + BuildInfo.VERSION, List.IMPLICIT, MENU_ITEMS, null);
        menu.addCommand(cmdExit);
        menu.setCommandListener(this);
        display.setCurrent(menu);
    }

    protected void pauseApp()
    {
        Diag.info("pauseApp");
        if (backgroundScreen != null) { backgroundScreen.onPause(); }
    }

    protected void destroyApp(boolean unconditional)
    {
        Diag.info("destroyApp unconditional=" + unconditional);
    }

    // ------------------------------------------------------------ commands

    public void commandAction(Command c, Displayable d)
    {
        try
        {
            handle(c, d);
        }
        catch (Throwable t)
        {
            // The whole point of this build is to bring back information from a
            // device we cannot attach a debugger to, so a failure here is
            // recorded and shown rather than allowed to kill the MIDlet.
            Diag.error("command failed", t);
            CrashLog.save("ui", t);
            showText("Error", new String[] {
                Diag.className(t),
                String.valueOf(t.getMessage()),
                "",
                "recorded in the crash log"
            });
        }
    }

    private void handle(Command c, Displayable d)
    {
        if (c == cmdExit)
        {
            destroyApp(true);
            notifyDestroyed();
            return;
        }

        if (c == cmdCancel)
        {
            pbkdf2Cancelled = true;
            return;
        }

        if (c == cmdBack)
        {
            display.setCurrent(menu);
            return;
        }

        if (c == NetScreen.CMD_RUN && netScreen != null)
        {
            netScreen.start();
            return;
        }

        if (c == SocketConnectScreen.CMD_RUN && telegramScreen != null)
        {
            telegramScreen.start();
            return;
        }

        if (c == BackgroundSocketScreen.CMD_ARM && backgroundScreen != null)
        {
            backgroundScreen.arm();
            return;
        }

        if (c == TwoSocketScreen.CMD_RUN && twoSocketScreen != null)
        {
            twoSocketScreen.start();
            return;
        }

        if (c == DisplayScreen.CMD_FULLSCREEN && displayScreen != null)
        {
            displayScreen.toggleFullScreen();
            return;
        }

        // These two draw their own results rather than going through showText,
        // so Upload has to be told what is on screen before it can send it.
        if (c == cmdUpload && d == displayScreen && displayScreen != null)
        {
            uploadOne("Display size", displayScreen.snapshot());
            return;
        }

        if (c == cmdUpload && d == twoSocketScreen && twoSocketScreen != null)
        {
            uploadOne("Two sockets", twoSocketScreen.snapshot());
            return;
        }

        if (c == cmdRefresh)
        {
            if (d == logScreen && logScreen != null)
            {
                logScreen.setLines(Diag.snapshot());
                logScreen.scrollToEnd();
            }
            else if (d == keyScreen && keyScreen != null)
            {
                showText("Key report", keyScreen.snapshot());
            }
            return;
        }

        if (c == cmdReport && keyTimingScreen != null)
        {
            showText("Key timing", keyTimingScreen.snapshot());
            return;
        }

        if (c == cmdUpload)
        {
            uploadPending();
            return;
        }

        if (c == cmdClear)
        {
            if (d == entropyScreen && entropyScreen != null)
            {
                // Starts a fresh cross-restart series. The tester needs this
                // after a run made under the wrong conditions - a warm restart
                // mixed into a cold-boot series poisons the comparison.
                EntropyLog.reset();
                entropyScreen.setLines(new String[] {
                    "cross-restart history cleared.",
                    "",
                    "power the phone fully off and",
                    "on, then run Entropy measure",
                    "again to start a new series."
                });
            }
            else if (d == logScreen)
            {
                Diag.clear();
                logScreen.setLines(Diag.snapshot());
            }
            else
            {
                CrashLog.clear();
                showText("Crash log", new String[] { "cleared" });
            }
            return;
        }

        if (c == List.SELECT_COMMAND && d == menu)
        {
            select(menu.getSelectedIndex());
        }
    }

    private void select(int index)
    {
        switch (index)
        {
            case ITEM_PLATFORM:
                showText("Platform", Caps.report());
                break;

            case ITEM_HEAP:
                runHeapProbe();
                break;

            case ITEM_RMS:
                showText("RMS", RmsCheck.run());
                break;

            case ITEM_ENTROPY:
                runEntropyProbe();
                break;

            case ITEM_BARRIER:
                runSeedingBarrier();
                break;

            case ITEM_VECTORS:
                runCrypto("Crypto vectors", new String[] {
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
                runCrypto("Crypto benchmarks", new String[] {
                    "running benchmarks...",
                    "",
                    "the 2048-bit modPow can take",
                    "a long time on this hardware.",
                    "please wait - do not exit."
                }, false);
                break;

            case ITEM_PBKDF2:
                runPbkdf2();
                break;

            case ITEM_CLOCK:
                showText("Clock", ClockProbe.run());
                break;

            case ITEM_TEXT:
                showText("Text", TextProbe.run());
                break;

            case ITEM_DISPLAY:
                showText("Display", DisplayProbe.run(display));
                break;

            case ITEM_CANVAS:
                if (displayScreen == null)
                {
                    displayScreen = new DisplayScreen();
                    displayScreen.addCommand(DisplayScreen.CMD_FULLSCREEN);
                    displayScreen.addCommand(cmdBack);
                    displayScreen.addCommand(cmdUpload);
                    displayScreen.setCommandListener(this);
                }
                display.setCurrent(displayScreen);
                break;

            case ITEM_KEYS:
                if (keyScreen == null) { keyScreen = new KeyScreen(); }
                keyScreen.addCommand(cmdBack);
                keyScreen.addCommand(cmdRefresh);
                keyScreen.setCommandListener(this);
                display.setCurrent(keyScreen);
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

            case ITEM_NET:
                if (netScreen == null)
                {
                    netScreen = new NetScreen("tcpbin.com", 4242, "J2ME-PROBE\n");
                    netScreen.addCommand(NetScreen.CMD_RUN);
                    netScreen.addCommand(cmdBack);
                    netScreen.setCommandListener(this);
                }
                display.setCurrent(netScreen);
                break;

            case ITEM_TG_80:
                showTelegramSocket(80);
                break;

            case ITEM_TG_443:
                showTelegramSocket(443);
                break;

            case ITEM_TG_5222:
                showTelegramSocket(5222);
                break;

            case ITEM_TG_8443:
                // The port the client actually reaches Telegram on, via an
                // MTProxy, and the one port never probed here - :80 and :443
                // are refused to an untrusted MIDlet on every handset measured
                // so far, so a green row on one of those was never expected.
                showTelegramSocket(8443);
                break;

            case ITEM_TWO_SOCK:
                if (twoSocketScreen == null)
                {
                    twoSocketScreen = new TwoSocketScreen("tcpbin.com", 4242);
                    twoSocketScreen.addCommand(TwoSocketScreen.CMD_RUN);
                    twoSocketScreen.addCommand(cmdBack);
                    twoSocketScreen.addCommand(cmdUpload);
                    twoSocketScreen.setCommandListener(this);
                }
                display.setCurrent(twoSocketScreen);
                break;

            case ITEM_IMAGE:
                showText("Image decode", ImageProbe.run());
                break;

            case ITEM_EMOJI:
                showText("Emoji sheet", ImageProbe.emojiSheet());
                break;

            case ITEM_BG:
                if (backgroundScreen == null)
                {
                    backgroundScreen = new BackgroundSocketScreen();
                    backgroundScreen.addCommand(BackgroundSocketScreen.CMD_ARM);
                    backgroundScreen.addCommand(cmdBack);
                    backgroundScreen.setCommandListener(this);
                }
                display.setCurrent(backgroundScreen);
                break;

            case ITEM_LOG:
                if (logScreen == null)
                {
                    logScreen = new TextScreen("Log", Diag.snapshot());
                    logScreen.addCommand(cmdBack);
                    logScreen.addCommand(cmdRefresh);
                    logScreen.addCommand(cmdClear);
                    logScreen.setCommandListener(this);
                }
                logScreen.setLines(Diag.snapshot());
                logScreen.scrollToEnd();
                display.setCurrent(logScreen);
                break;

            case ITEM_CRASH:
                showCrashLog();
                break;

            case ITEM_UPLOAD_ALL:
                runUploadAll();
                break;

            default:
                break;
        }
    }

    private void showTelegramSocket(int port)
    {
        telegramScreen = new SocketConnectScreen(Dc.bootstrapAddress(), port);
        telegramScreen.addCommand(SocketConnectScreen.CMD_RUN);
        telegramScreen.addCommand(cmdBack);
        telegramScreen.setCommandListener(this);
        display.setCurrent(telegramScreen);
    }

    // ------------------------------------------------------------- screens

    /**
     * The heap probe intentionally allocates until the VM refuses, so it must
     * not run on the UI thread - a frozen display during an OOM sweep looks
     * exactly like a hung MIDlet.
     */
    private void runHeapProbe()
    {
        final TextScreen screen = new TextScreen("Heap probe", new String[] {
            "running...",
            "",
            "allocating 8 KB blocks until the VM",
            "refuses, then releasing everything.",
            "this can take a while on slow hardware."
        });
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
                    HeapProbe.Result r = HeapProbe.run(8 * 1024);
                    publish("Heap probe", r.lines());
                    screen.setLines(r.lines());
                }
                catch (Throwable t)
                {
                    Diag.error("heap probe failed", t);
                    String[] failure = new String[] {
                        "FAILED", Diag.className(t), String.valueOf(t.getMessage())
                    };
                    publish("Heap probe", failure);
                    screen.setLines(failure);
                }
            }
        }).start();
    }

    /**
     * Make a result reachable by the Upload command.
     *
     * The async screens finish on a worker thread, so without this the command
     * would still be offering whatever was on screen before the probe started.
     */
    private void publish(String section, String[] lines)
    {
        pendingSection = section;
        pendingLines = lines;
    }

    /**
     * The entropy suite blocks for up to about 25 seconds - most of it spent
     * deliberately busy-looping against the clock - so like the heap probe it
     * belongs on a worker thread. The progress callback exists so the display
     * keeps changing while it runs; a frozen screen during a long measurement
     * is indistinguishable from a hung MIDlet, and on a handset the AMS may act
     * on that.
     */
    private void runEntropyProbe()
    {
        final TextScreen screen = new TextScreen("Entropy", new String[] {
            "running...",
            "",
            "measuring clock granularity,",
            "jitter, hashCode and heap",
            "readings, then comparing this",
            "launch against earlier ones.",
            "",
            "up to 25 s. do not exit."
        });
        screen.addCommand(cmdBack);
        screen.addCommand(cmdClear);
        screen.addCommand(cmdUpload);
        screen.setCommandListener(this);
        entropyScreen = screen;
        display.setCurrent(screen);

        new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    // Kept as the first thing printed: three gathers that must
                    // differ is a real assertion, it costs nothing, and a failure
                    // there makes every figure below it moot.
                    String[] head = new String[5];
                    head[0] = "three gather() samples;";
                    head[1] = "they MUST differ.";
                    for (int i = 0; i < 3; i++)
                    {
                        head[2 + i] = Hex.encode(Entropy.gather(), 0, 8);
                    }

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

                    String[] lines = new String[head.length + 1 + body.length];
                    System.arraycopy(head, 0, lines, 0, head.length);
                    lines[head.length] = "";
                    System.arraycopy(body, 0, lines, head.length + 1, body.length);
                    publish("Entropy measure", lines);
                    screen.setLines(lines);
                }
                catch (Throwable t)
                {
                    Diag.error("entropy probe failed", t);
                    CrashLog.save("entropy", t);
                    String[] failure = new String[] {
                        "FAILED", Diag.className(t), String.valueOf(t.getMessage())
                    };
                    publish("Entropy measure", failure);
                    screen.setLines(failure);
                }
            }
        }).start();
    }

    /**
     * How many gathers the auth-key barrier asks this handset for.
     *
     * The number is the finding. It is chosen from what this device's clock
     * actually yields, so it differs by an order of magnitude across the handsets
     * measured so far, and it is the answer to the question section b of the
     * entropy probe only sets up.
     */
    private void runSeedingBarrier()
    {
        final TextScreen screen = new TextScreen("Seeding barrier", new String[] {
            "running the real auth-key",
            "seeding barrier on a throwaway",
            "pool...",
            "",
            "up to 8 s. do not exit."
        });
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
                    String[] lines = EntropyProbe.barrierReport();
                    publish("Seeding barrier", lines);
                    screen.setLines(lines);
                }
                catch (Throwable t)
                {
                    Diag.error("seeding barrier failed", t);
                    CrashLog.save("barrier", t);
                    String[] failure = new String[] {
                        "FAILED", Diag.className(t), String.valueOf(t.getMessage())
                    };
                    publish("Seeding barrier", failure);
                    screen.setLines(failure);
                }
            }
        }).start();
    }

    /**
     * Vectors or benchmarks, both on a worker thread.
     *
     * The benchmark can take minutes on a 208 MHz CPU, and blocking the UI thread
     * that long looks like a hang and can trip the AMS watchdog.
     *
     * @param vectors true to run the self-test, false to run the benchmark
     */
    private void runCrypto(final String title, String[] placeholder,
                           final boolean vectors)
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

    /**
     * Telegram's cloud-password KDF, at the iteration count the protocol uses.
     *
     * Four and a half minutes on the slowest handset measured, which is why this
     * one is cancellable and why it is not in the Upload all sweep.
     */
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
                    screen.addCommand(cmdUpload);
                    publish("PBKDF2", lines);
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

    private void showCrashLog()
    {
        String[] entries = CrashLog.load();
        if (entries.length == 0)
        {
            showText("Crash log", new String[] { "no crashes recorded" });
            return;
        }

        // Entries are multi-line blobs; split them for the line-oriented view.
        int total = 0;
        for (int i = 0; i < entries.length; i++)
        {
            total += countLines(entries[i]) + 1;
        }
        String[] lines = new String[total];
        int w = 0;
        for (int i = 0; i < entries.length; i++)
        {
            lines[w++] = "=== entry " + (i + 1) + " ===";
            w = splitInto(entries[i], lines, w);
        }

        TextScreen screen = new TextScreen("Crash log", lines);
        screen.addCommand(cmdBack);
        screen.addCommand(cmdClear);
        screen.setCommandListener(this);
        display.setCurrent(screen);
    }

    private void showText(String title, String[] lines)
    {
        pendingSection = title;
        pendingLines = lines;

        TextScreen screen = new TextScreen(title, lines);
        screen.addCommand(cmdBack);
        screen.addCommand(cmdUpload);
        screen.setCommandListener(this);
        display.setCurrent(screen);
    }

    // -------------------------------------------------------------- uploads

    /**
     * Send whatever result is currently on screen.
     *
     * On a worker thread without exception: MIDP's HttpConnection has no
     * timeout control, and a handset with no data session can block inside
     * Connector.open for a long time. Doing that on the lcdui thread would
     * freeze the display, which on some AMS implementations gets the MIDlet
     * killed - while diagnosing a crash.
     */
    private void uploadPending()
    {
        if (pendingLines == null)
        {
            showText("Upload", new String[] { "nothing to upload yet" });
            return;
        }
        uploadOne(pendingSection, pendingLines);
    }

    private void uploadOne(String section, String[] lines)
    {
        final TextScreen screen = new TextScreen("Upload", new String[] { "starting..." });
        screen.addCommand(cmdBack);
        screen.setCommandListener(this);
        display.setCurrent(screen);

        ReportUpload.send(SINK_TARGET, section, lines, new ReportUpload.Progress()
        {
            public void lines(String[] text) { screen.setLines(text); }
        });
    }

    /**
     * Run every non-interactive scenario and upload each result.
     *
     * This is the point of the build: one menu entry that leaves a full picture
     * of an unknown handset on the collector, so nothing has to be read off the
     * screen and retyped. The interactive scenarios - Keys, Key timing, the
     * socket screens, Background socket - are left out because they need
     * someone pressing buttons.
     *
     * PBKDF2 is left out for a different reason: at Telegram's iteration count it
     * blocks for four and a half minutes on the slowest handset measured, which
     * is longer than everything else here put together. It stays a deliberate
     * choice from the menu.
     */
    private void runUploadAll()
    {
        final HttpReportSink sink = HttpReportSink.createDefault();
        if (sink == null)
        {
            showText("Upload all", ReportUpload.noSinkMessage());
            return;
        }

        final TextScreen screen = new TextScreen("Upload all", new String[] {
            "starting...",
            "",
            "runs the heap and entropy probes,",
            "the crypto vectors and the modPow",
            "benchmark, so allow several",
            "minutes. do not exit."
        });
        screen.addCommand(cmdBack);
        screen.setCommandListener(this);
        display.setCurrent(screen);

        new Thread(new Runnable()
        {
            public void run()
            {
                // Order matters. Platform and heap come first because they are
                // what every other number has to be read against, and because
                // if the handset dies partway through a sweep those are the two
                // that must already have arrived. The entropy trio is contiguous
                // and in dependency order - what a gather is worth, then how many
                // the barrier took, then whether a seed ever repeated - because
                // the three are read together or not at all.
                String[] names = {
                    "Platform", "Heap probe", "RMS", "Clock", "Text",
                    "Display", "Image decode", "Emoji sheet",
                    "Crypto vectors", "Crypto benchmarks",
                    "Entropy measure", "Seeding barrier", "Entropy log",
                    "Diagnostic log", "Crash log"
                };

                int sent = 0;
                int failed = 0;
                String lastError = null;

                for (int i = 0; i < names.length; i++)
                {
                    screen.setLines(new String[] {
                        "[" + (i + 1) + "/" + names.length + "] " + names[i],
                        "",
                        "sent " + sent + ", failed " + failed,
                        "do not exit."
                    });

                    String[] lines;
                    try
                    {
                        lines = collect(i);
                    }
                    catch (Throwable t)
                    {
                        // A scenario that blows up is itself a finding; report
                        // it rather than abandoning the remaining ones.
                        Diag.error("upload-all " + names[i], t);
                        lines = new String[] {
                            "scenario FAILED",
                            Diag.className(t),
                            String.valueOf(t.getMessage())
                        };
                    }

                    if (sink.send(SINK_TARGET, names[i], lines))
                    {
                        sent++;
                    }
                    else
                    {
                        failed++;
                        lastError = sink.lastError();
                    }
                }

                String[] done = new String[failed == 0 ? 5 : 7];
                done[0] = failed == 0 ? "done" : "done with errors";
                done[1] = "";
                done[2] = "sent " + sent + " of " + names.length;
                done[3] = "collector: " + DevSink.DEVICE;
                // The POST ceiling this handset turned out to have is a device
                // finding in its own right - it belongs in the hardware note
                // next to the heap and JAR-size limits, not just in a log.
                done[4] = "post chunk: " + sink.acceptedChunkBytes() + " B"
                        + (sink.refusedChunkBytes() > 0
                           ? " (refused " + sink.refusedChunkBytes() + " B)" : "");
                if (failed > 0)
                {
                    done[5] = "failed " + failed;
                    done[6] = String.valueOf(lastError);
                }
                screen.setLines(done);
            }

            private String[] collect(int index)
            {
                switch (index)
                {
                    case 0: return Caps.report();
                    case 1: return HeapProbe.run(8 * 1024).lines();
                    case 2: return RmsCheck.run();
                    case 3: return ClockProbe.run();
                    case 4: return TextProbe.run();
                    case 5: return DisplayProbe.run(display);
                    case 6: return ImageProbe.run();
                    case 7: return ImageProbe.emojiSheet();
                    case 8: return SelfTest.run().lines;
                    case 9: return SelfTest.benchmark();
                    case 10: return EntropyProbe.run(null);
                    case 11: return EntropyProbe.barrierReport();
                    case 12: return EntropyLog.report();
                    case 13: return Diag.snapshot();
                    default: return crashLines();
                }
            }
        }).start();
    }

    /** Crash entries flattened for a line-oriented report. */
    private static String[] crashLines()
    {
        String[] entries = CrashLog.load();
        if (entries.length == 0) { return new String[] { "no crashes recorded" }; }

        int total = 0;
        for (int i = 0; i < entries.length; i++)
        {
            total += countLines(entries[i]) + 1;
        }
        String[] lines = new String[total];
        int w = 0;
        for (int i = 0; i < entries.length; i++)
        {
            lines[w++] = "=== entry " + (i + 1) + " ===";
            w = splitInto(entries[i], lines, w);
        }
        return lines;
    }

    // CLDC has no String.split(), and a regex engine is not something we want
    // on this heap anyway.
    private static int countLines(String s)
    {
        int n = 1;
        for (int i = 0; i < s.length(); i++)
        {
            if (s.charAt(i) == '\n') { n++; }
        }
        return n;
    }

    private static int splitInto(String s, String[] out, int at)
    {
        int start = 0;
        for (int i = 0; i < s.length() && at < out.length; i++)
        {
            if (s.charAt(i) == '\n')
            {
                out[at++] = s.substring(start, i);
                start = i + 1;
            }
        }
        if (at < out.length) { out[at++] = s.substring(start); }
        return at;
    }
}
