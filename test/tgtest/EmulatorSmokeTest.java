package tgtest;

import java.lang.reflect.Method;
import java.util.Vector;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;

import org.microemu.device.DeviceFactory;

import tg.app.TgMidlet;

/**
 * End-to-end launch of a built MIDlet inside MicroEmulator's MIDP runtime.
 *
 * This is deliberately not part of {@code AllTests}: it is run by
 * {@code tools/smoke-emulator.ps1} against {@code dist/*.jar} rather than
 * against {@code build/desktop/classes}, because the whole point is to
 * exercise the artifact that ships. Everything the desktop suite covers runs
 * on classes ProGuard never touched, so a keep rule that stopped covering the
 * code, a stripped resource or a broken preverification pass would all reach a
 * handset before anything noticed.
 *
 * <p>What it establishes:
 *
 * <ul>
 * <li>the packaged MIDlet constructs, starts and reaches a screen - the check
 *     that catches shrinking and obfuscation damage;</li>
 * <li>commands route: invoking one really does change the current screen;</li>
 * <li>the menu-ordering contract below holds on every screen visited;</li>
 * <li>the MIDlet shuts down without leaving a thread behind.</li>
 * </ul>
 *
 * <p>What it does not establish: anything about a handset. MicroEmulator runs
 * on the desktop JVM, so heap limits, the AMS permission policy, JAR
 * verification, timing and key codes are all out of scope - see
 * docs/emulator-notes.md. It also never presses Connect, so the run is offline.
 */
public final class EmulatorSmokeTest
{
    /** Screens are visited by command label, which survives obfuscation. */
    private static final String[] VISIT = { "Settings", "Log", "Diagnostics" };

    public static void main(String[] args)
    {
        String label = args.length > 0 ? args[0] : "tg";
        try
        {
            run(label);
            System.out.println("=== SMOKE OK (" + label + ") ===");
        }
        catch (Throwable t)
        {
            System.out.println("=== SMOKE FAILED (" + label + ") ===");
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
        // MicroEmulator leaves a non-daemon Timer of its own running, so the
        // JVM would never exit on its own. On a handset the AMS reclaims the
        // MIDlet; here the harness has to say so explicitly.
        System.out.flush();
        System.exit(0);
    }

    private static void run(String label) throws Exception
    {
        System.out.println("emulator smoke :: " + label);

        DeviceFactory.setDevice(new TestDevice("smoke", 240, 320));

        Harness midlet = new Harness();
        midlet.start();

        Display display = Display.getDisplay(midlet);
        Displayable start = awaitChange(display, null);
        Assert.isTrue("the MIDlet reached a screen", start != null);
        System.out.println("  start screen: " + describe(start));
        checkMenuOrdering("start screen", start);

        for (int i = 0; i < VISIT.length; i++)
        {
            visitAndReturn(midlet, display, start, VISIT[i]);
        }

        Assert.isTrue("back at the start screen", display.getCurrent() == start);

        midlet.stop();
        Assert.isTrue("no MIDlet thread outlives destroyApp", quiesced());
    }

    /**
     * Open one screen, check it, and come back.
     *
     * Asserting that the screen actually changed is the part that matters: a
     * command that silently does nothing is exactly the failure the handset
     * showed, and it leaves no other trace.
     */
    private static void visitAndReturn(Harness midlet, Display display,
                                       Displayable start, String label)
            throws Exception
    {
        Command open = byLabel(start, label);
        Assert.isTrue("start screen offers " + label, open != null);

        midlet.commandAction(open, start);
        Displayable opened = awaitChange(display, start);
        Assert.isTrue(label + " opened a different screen", opened != start);
        System.out.println("  " + label + " -> " + describe(opened));
        checkMenuOrdering(label, opened);

        Command back = byLabel(opened, "Back");
        Assert.isTrue(label + " offers Back", back != null);
        midlet.commandAction(back, opened);
        Assert.isTrue("Back returned from " + label,
                      awaitChange(display, opened) == start);
    }

    /**
     * MicroEmulator's {@code Display.setCurrent} posts to an event dispatcher
     * rather than switching screens inline, so every navigation step has to be
     * waited for. Returns the new screen, or whatever is current once the wait
     * runs out, so the caller's assertion is the one that reports the failure.
     */
    private static Displayable awaitChange(Display display, Displayable previous)
            throws Exception
    {
        for (int attempt = 0; attempt < 100; attempt++)
        {
            Displayable current = display.getCurrent();
            if (current != null && current != previous) { return current; }
            Thread.sleep(50);
        }
        return display.getCurrent();
    }

    /**
     * The contract learned the hard way on a physical handset.
     *
     * MIDP only promises to honour a command's priority within a single type;
     * placement across types is the handset's business, and a real one was
     * measured emitting its Options menu type by type with SCREEN ahead of OK.
     * That put the primary action of every screen - "Next", "Sign in",
     * "Write" - underneath "Log", at the bottom of the menu.
     *
     * So: everything that shares the menu must share the type, leaving
     * priority in charge. Back, Exit and Cancel are exempt because handsets
     * map them to a dedicated key rather than into the menu.
     */
    private static void checkMenuOrdering(String screen, Displayable d)
            throws Exception
    {
        Vector commands = commandsOf(d);
        for (int i = 0; i < commands.size(); i++)
        {
            Command c = (Command) commands.elementAt(i);
            int type = c.getCommandType();
            if (type == Command.BACK || type == Command.EXIT
                    || type == Command.CANCEL)
            {
                continue;
            }
            if (type != Command.SCREEN)
            {
                Assert.fail(screen + ": \"" + c.getLabel() + "\" is command type "
                        + type + ", not SCREEN. A handset that orders its menu "
                        + "by type will place it after every SCREEN command "
                        + "regardless of its priority.");
            }
        }
    }

    private static Command byLabel(Displayable d, String label) throws Exception
    {
        Vector commands = commandsOf(d);
        for (int i = 0; i < commands.size(); i++)
        {
            Command c = (Command) commands.elementAt(i);
            if (label.equals(c.getLabel())) { return c; }
        }
        return null;
    }

    /**
     * MIDP gives no way to read a screen's commands back, so this reaches for
     * MicroEmulator's package-private accessor. Test-only, and the reason the
     * check has to live outside src/.
     */
    private static Vector commandsOf(Displayable d) throws Exception
    {
        Method m = Displayable.class.getDeclaredMethod("getCommands", new Class[0]);
        m.setAccessible(true);
        Vector v = (Vector) m.invoke(d, new Object[0]);
        return v == null ? new Vector() : v;
    }

    private static String describe(Displayable d)
    {
        String title = d.getTitle();
        return d.getClass().getName() + (title == null ? "" : " \"" + title + "\"");
    }

    /**
     * The draft autosave loop is a non-daemon thread that only notices its
     * stop flag after a 3-second sleep, so this waits rather than sampling
     * once. A thread of ours still running here would keep a handset's AMS
     * from reclaiming the MIDlet cleanly.
     *
     * Threads are identified by having a frame in our own code: MicroEmulator
     * runs a non-daemon Timer of its own that never stops, and that is not
     * something this test gets to have an opinion about.
     */
    private static boolean quiesced() throws Exception
    {
        for (int attempt = 0; attempt < 60; attempt++)
        {
            if (ours() == null) { return true; }
            Thread.sleep(100);
        }
        Thread lingering = ours();
        if (lingering != null)
        {
            System.out.println("  still running: " + lingering.getName());
            StackTraceElement[] stack = lingering.getStackTrace();
            for (int i = 0; i < stack.length && i < 6; i++)
            {
                System.out.println("    at " + stack[i]);
            }
        }
        return false;
    }

    /**
     * A live non-daemon thread executing project code, or null.
     *
     * Identified by exclusion rather than by a "tg." prefix, because the
     * obfuscated build renames the very classes this needs to recognise.
     * Anything outside the emulator and the JDK is ours by elimination.
     */
    private static Thread ours()
    {
        Thread[] threads = new Thread[Thread.activeCount() * 2 + 16];
        int n = Thread.enumerate(threads);
        for (int i = 0; i < n; i++)
        {
            Thread t = threads[i];
            if (t == null || t == Thread.currentThread()
                    || t.isDaemon() || !t.isAlive())
            {
                continue;
            }
            StackTraceElement[] stack = t.getStackTrace();
            for (int f = 0; f < stack.length; f++)
            {
                if (!isPlatform(stack[f].getClassName())) { return t; }
            }
        }
        return null;
    }

    private static boolean isPlatform(String className)
    {
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun.")
                || className.startsWith("org.microemu.")
                || className.startsWith("tgtest.");
    }

    /** startApp and destroyApp are protected; a subclass is the way in. */
    private static final class Harness extends TgMidlet
    {
        void start() throws Exception { startApp(); }
        void stop() throws Exception { destroyApp(true); }
    }
}
