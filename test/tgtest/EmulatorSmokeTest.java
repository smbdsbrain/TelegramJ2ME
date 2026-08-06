package tgtest;

import java.lang.reflect.Method;
import java.util.Vector;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;

import org.microemu.device.DeviceFactory;

import tg.app.TgMidlet;
import tg.crypto.AuthKeySeeding;
import tg.crypto.Rng;
import tg.mem.MemoryBudget;

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

        budgetsAreRuntimeValues();
        theSeedingBarrierSizesItself();

        Harness midlet = new Harness();
        midlet.start();

        Display display = Display.getDisplay(midlet);
        Displayable first = awaitChange(display, null);
        Assert.isTrue("the MIDlet reached a screen", first != null);
        System.out.println("  start screen: " + describe(first));

        // Before anything is pressed, not after. The heap probe rebuilds the
        // start screen when it finishes - it has a "measuring" line to drop and
        // possibly a warning to add - and MicroEmulator's setCurrent posts
        // rather than switches, so a visit that overlaps with that lands on the
        // *new* start screen and reports the screen it opened as having no
        // Back. Seen on a CI runner as "Settings -> Form Telegram J2ME": not a
        // navigation defect, a race with the probe.
        awaitHeapMeasurement();
        Displayable start = awaitSettled(display);
        Assert.isTrue("a screen survived the measurement", start != null);
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
     * Prove, against the artifact that actually ships, that the memory budgets
     * are still runtime values.
     *
     * Every one of them used to be a {@code static final int} with a constant
     * initialiser, which javac inlines at each use site. The desktop suite
     * cannot catch a regression here: it runs against build/desktop/classes,
     * which ProGuard never touches, and config/proguard-debug.pro turns
     * optimisation off for every non-release build. This is the only place the
     * obfuscated, optimised jar gets asked the question.
     *
     * The budget is reset afterwards, so the MIDlet starts on the same profile
     * it would have on a device with no stored measurement.
     *
     * Only MemoryBudget is named here. Its consumers are obfuscated away in
     * tg-min and keeping them would trade jar size for a check the desktop
     * suite already makes - tgtest.Phase7DesignTest asserts that the caches
     * size themselves from these numbers.
     */
    private static void budgetsAreRuntimeValues()
    {
        try
        {
            MemoryBudget.init(1024 * 1024, 512 * 1024, MemoryBudget.SOURCE_MEASURED);
            Assert.isTrue("a measured ceiling reaches maxHistory in the shipped jar",
                    MemoryBudget.maxHistory() < 120);
            Assert.isTrue("a measured ceiling reaches packetBytes in the shipped jar",
                    MemoryBudget.packetBytes() < 1024 * 1024);
            Assert.isTrue("a measured ceiling reaches photoPixels in the shipped jar",
                    MemoryBudget.photoPixels() < 307200);
        }
        finally { MemoryBudget.reset(); }
        Assert.equal("resetting restores the shipped history budget", 120,
                MemoryBudget.maxHistory());
        System.out.println("  budgets respond to a measurement");
    }

    /**
     * The auth-key seeding barrier, run against the artifact that ships.
     *
     * The count is no longer a constant: the barrier measures what this
     * runtime's clock yields and keeps gathering until it has 256 credited
     * bits. That makes it the second thing in this client - after the memory
     * budgets - whose behaviour a ProGuard pass could plausibly change and no
     * desktop run would notice, because the desktop suite tests classes
     * ProGuard never touched. Here the jar answers for itself.
     *
     * The figures are a desktop JVM's and mean nothing about a handset; what is
     * asserted is that the packaged barrier still terminates, still sizes itself
     * inside its own bounds, and still credits what it claims.
     */
    private static void theSeedingBarrierSizesItself()
    {
        int before = AuthKeySeeding.completedBarriers();
        Rng rng = new Rng();
        try
        {
            AuthKeySeeding.Outcome o = AuthKeySeeding.strengthen(rng, 2, false, false);
            System.out.println("  seeding barrier: " + o.describe());

            Assert.equal("the packaged barrier completed", before + 1,
                    AuthKeySeeding.completedBarriers());
            Assert.isTrue("sized within its own bounds: " + o.describe(),
                    o.gathers >= AuthKeySeeding.MIN_GATHERS
                            && o.gathers <= AuthKeySeeding.MAX_GATHERS);
            Assert.isTrue("credited something: " + o.describe(), o.bits > 0);
            Assert.isTrue("a desktop clock reaches the target: " + o.describe(),
                    !o.shortOfTarget);
        }
        finally { rng.wipe(); }
    }

    /**
     * Wait for the MIDlet's own first-launch heap probe and report what it
     * found.
     *
     * This is the end-to-end half of the budget story: the class-level check
     * above proves the values can move, this proves the packaged client
     * actually measures the VM it was given and derives from the answer. Run it
     * under {@code -JavaArgs -Xmx24m} and the printed ceiling follows.
     *
     * Not asserted as an exact figure - it is whatever the host JVM offers -
     * but a source of "default" here would mean the probe never ran, never
     * returned, or was refused, and that is worth failing on.
     */
    /**
     * The screen the client has settled on, once nothing is repainting it.
     *
     * A measurement being installed and the screen that reports it being posted
     * are two different moments: {@code finishHeapProbe} rebuilds the start
     * screen from a {@code callSerially} that runs after
     * {@link MemoryBudget#init}, and MicroEmulator's {@code setCurrent} posts to
     * a dispatcher rather than switching inline. Waiting for the budget alone
     * therefore still races the repaint it causes.
     */
    private static Displayable awaitSettled(Display display) throws Exception
    {
        Displayable stable = display.getCurrent();
        int unchanged = 0;
        long deadline = System.currentTimeMillis() + 10000;
        while (unchanged < 4 && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(150);
            Displayable now = display.getCurrent();
            if (now == stable) { unchanged++; }
            else { stable = now; unchanged = 0; }
        }
        return stable;
    }

    private static void awaitHeapMeasurement() throws Exception
    {
        // Generous on purpose. The probe fills the heap and then collects it
        // several times, and this runs on CI hosts that are slower and busier
        // than a developer's machine - a tight bound here is a flaky check
        // that says "the client never measured its heap" when the truth is
        // "the box was loaded".
        long t0 = System.currentTimeMillis();
        long deadline = t0 + 60000;
        while (MemoryBudget.source() == MemoryBudget.SOURCE_DEFAULT
                && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(100);
        }
        long waited = System.currentTimeMillis() - t0;

        String[] lines = MemoryBudget.lines();
        for (int i = 0; i < lines.length; i++)
        {
            System.out.println("  " + lines[i]);
        }
        Assert.isTrue("the client measured its own heap on first launch"
                        + " (waited " + waited + " ms)",
                MemoryBudget.source() != MemoryBudget.SOURCE_DEFAULT);
        Assert.isTrue("the measured ceiling is a real number",
                MemoryBudget.ceiling() > 0);
        System.out.println("  measured in " + waited + " ms");
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
