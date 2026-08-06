package tgtest;

import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.List;

import org.microemu.device.DeviceFactory;

import tg.crypto.AuthKeySeeding;
import tg.diag.Diag;

/**
 * End-to-end run of the packaged probe suite inside MicroEmulator.
 *
 * The counterpart to {@link EmulatorSmokeTest}, which drives the client. This
 * one drives {@code dist/probe.jar} - the suite a tester installs on an unknown
 * handset - and it exists because two of its measurements are now things the
 * client's behaviour depends on rather than things a human reads off a screen:
 *
 * <ul>
 * <li><b>Seeding barrier</b> runs the real {@code AuthKeySeeding.strengthen} and
 *     reports how many gathers this runtime needed. Nothing in the desktop suite
 *     exercises that through a MIDlet, through ProGuard's output, on a menu index
 *     that has to line up with a label;</li>
 * <li><b>Crypto vectors</b> came from a MIDlet that no longer exists, so the
 *     wiring behind it is new code even though the vectors are not.</li>
 * </ul>
 *
 * <p>Run by {@code tools/smoke-emulator.ps1 -ArtifactName probe}. Not part of
 * {@code AllTests}: it needs the packaged jar on the classpath, which is the
 * whole point - a keep rule that stopped covering this code would exit ProGuard
 * 0 and fail here.
 *
 * <p>What it does not establish: anything about a handset. The gather count
 * printed below is a desktop JVM's, and a desktop JVM has a good clock. See
 * docs/emulator-notes.md.
 */
public final class ProbeSmokeTest
{
    /** Every measurement the sweep is supposed to offer, by menu label. */
    private static final String[] REQUIRED_ITEMS = {
        "Platform & build", "Heap probe", "RMS test", "Entropy measure",
        "Seeding barrier", "Crypto vectors", "Crypto benchmarks",
        "PBKDF2 x100000", "Clock & timers", "Upload all"
    };

    public static void main(String[] args)
    {
        String label = args.length > 0 ? args[0] : "probe";
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
        System.out.flush();
        System.exit(0);
    }

    private static void run(String label) throws Exception
    {
        System.out.println("probe smoke :: " + label);

        // Not EmulatorHarness.install: that class holds a TgMidlet subclass, and
        // resolving it against dist/probe.jar - which has no client in it - is a
        // NoClassDefFoundError before anything is installed. The two pieces it
        // would have set up are set up here directly.
        DeviceFactory.setDevice(new TestDevice("probe-smoke", 240, 320));
        // The probe writes its cross-restart entropy log on startup, so the
        // record store has to work or the first thing it does is throw.
        EmulatorRecords.install();

        Harness midlet = new Harness();
        midlet.start();

        Display display = Display.getDisplay(midlet);
        List menu = awaitMenu(display);
        System.out.println("  menu: " + menu.size() + " items");

        everyMeasurementIsOffered(menu);
        theSeedingBarrierSizesItself(midlet, menu);
        theCryptoVectorsStillPass(midlet, menu);

        midlet.stop();
    }

    /**
     * The menu is the suite. An item lost to a bad merge is a measurement that
     * silently stops being taken on every handset session afterwards.
     */
    private static void everyMeasurementIsOffered(List menu)
    {
        for (int i = 0; i < REQUIRED_ITEMS.length; i++)
        {
            Assert.isTrue("the menu offers \"" + REQUIRED_ITEMS[i] + "\"",
                    indexOf(menu, REQUIRED_ITEMS[i]) >= 0);
        }
    }

    /**
     * The barrier, in the artifact that ships, sizing itself from what this
     * runtime's clock is worth.
     *
     * Waiting on the barrier counter rather than on a screen: the measurement
     * runs on a worker thread, and MicroEmulator's setCurrent posts rather than
     * switches, so the counter is the only thing here that changes exactly once
     * and exactly when the work is done.
     */
    private static void theSeedingBarrierSizesItself(Harness midlet, List menu)
            throws Exception
    {
        int before = AuthKeySeeding.completedBarriers();
        select(midlet, menu, "Seeding barrier");

        long deadline = System.currentTimeMillis() + 60000;
        while (AuthKeySeeding.completedBarriers() == before
                && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(100);
        }

        Assert.equal("the packaged probe ran one barrier", before + 1,
                AuthKeySeeding.completedBarriers());

        AuthKeySeeding.Outcome o = AuthKeySeeding.lastOutcome();
        Assert.isTrue("it published an outcome", o != null);
        System.out.println("  seeding barrier: " + o.describe());

        Assert.isTrue("sized within its own bounds: " + o.describe(),
                o.gathers >= AuthKeySeeding.MIN_GATHERS
                        && o.gathers <= AuthKeySeeding.MAX_GATHERS);
        Assert.isTrue("credited something: " + o.describe(), o.bits > 0);
        Assert.isTrue("took no longer than its cap allows: " + o.describe(),
                o.millis <= AuthKeySeeding.MAX_MILLIS * 2L);
        Assert.isTrue("a desktop clock reaches the target: " + o.describe(),
                !o.shortOfTarget);
    }

    /**
     * The vectors, reached through the menu they moved to.
     *
     * Read from the diagnostic ring rather than from the screen: the result
     * screen is a TextScreen whose contents no MIDP API exposes, and the line
     * the run logs carries the same pass/fail counts.
     */
    private static void theCryptoVectorsStillPass(Harness midlet, List menu)
            throws Exception
    {
        select(midlet, menu, "Crypto vectors");

        String line = null;
        long deadline = System.currentTimeMillis() + 60000;
        while (line == null && System.currentTimeMillis() < deadline)
        {
            line = find(Diag.snapshot(), "selftest passed=");
            if (line == null) { Thread.sleep(100); }
        }

        Assert.isTrue("the vectors ran and reported", line != null);
        System.out.println("  " + line.trim());
        Assert.isTrue("no vector failed: " + line, line.indexOf("failed=0") >= 0);
    }

    // ----------------------------------------------------------------- driving

    /** Select a menu entry by its label, the way a keypress would. */
    private static void select(Harness midlet, List menu, String label)
    {
        int index = indexOf(menu, label);
        Assert.isTrue("the menu offers \"" + label + "\"", index >= 0);
        menu.setSelectedIndex(index, true);
        midlet.commandAction(List.SELECT_COMMAND, menu);
    }

    private static int indexOf(List menu, String label)
    {
        for (int i = 0; i < menu.size(); i++)
        {
            if (label.equals(menu.getString(i))) { return i; }
        }
        return -1;
    }

    private static List awaitMenu(Display display) throws Exception
    {
        for (int attempt = 0; attempt < 100; attempt++)
        {
            Displayable current = display.getCurrent();
            if (current instanceof List) { return (List) current; }
            Thread.sleep(50);
        }
        Displayable stuck = display.getCurrent();
        Assert.fail("the probe never reached its menu: "
                + (stuck == null ? "(no screen)" : stuck.getClass().getName()));
        return null;
    }

    private static String find(String[] lines, String needle)
    {
        for (int i = 0; i < lines.length; i++)
        {
            if (lines[i] != null && lines[i].indexOf(needle) >= 0) { return lines[i]; }
        }
        return null;
    }

    /** startApp and destroyApp are protected; a subclass is the way in. */
    private static final class Harness extends tg.app.ProbeMidlet
    {
        void start() throws Exception { startApp(); }
        void stop() throws Exception { destroyApp(true); }
    }
}
