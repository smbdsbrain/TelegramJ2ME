package tgtest;

import java.lang.reflect.Method;
import java.util.Vector;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.TextBox;

import org.microemu.device.DeviceFactory;

/**
 * A MicroEmulator runtime that a script can drive, with a working record store.
 *
 * <h3>Why this exists</h3>
 * {@code EmulatorSmokeTest} installs a {@link TestDevice} and that is enough to
 * build screens and navigate them, but it is <em>not</em> enough for RMS:
 * {@code javax.microedition.rms.RecordStore}'s static methods go through
 * {@code MIDletBridge.getRecordStoreManager()}, and with no
 * {@code MicroEmulator} registered every call throws a NullPointerException
 * that {@code RmsAuthKeyStore} dutifully turns into "RMS read failed". The
 * smoke test never noticed because it never signs in.
 *
 * Anything that has to survive a restart - an auth key, a signed-in session, the
 * stored heap measurement - therefore could not be exercised without a human
 * clicking the GUI. This class closes that gap: it installs a device and, through
 * {@link EmulatorRecords}, a record store that writes under {@code user.home},
 * so a driver gets the same isolated, persistent profile that
 * {@code tools/run-emulator.ps1 -EmulatorProfile} gives the real emulator.
 *
 * <h3>What it still is not</h3>
 * A desktop JVM. Nothing here says anything about a handset - see
 * docs/emulator-notes.md. It replaces clicking, not hardware.
 */
public final class EmulatorHarness
{
    private final MidletHarness midlet = new MidletHarness();
    private Display display;

    /**
     * Install only the record store, with no device.
     *
     * {@link #install} also registers a {@link TestDevice}, which is right for
     * a driver that builds screens and unnecessary weight for a test that only
     * needs {@code RecordStore} to work - and a device pulls in the emulator's
     * Swing side, which a headless suite should not depend on.
     */
    public static void installRecordStore()
    {
        EmulatorRecords.install();
    }

    /** Install the device and record store. Call once per JVM. */
    public static void install(String deviceName, int width, int height)
    {
        // withInput(): the driver reaches TextBox screens - the phone number,
        // the sign-in code, the message composer - and MIDP's TextBox cannot be
        // shown without an input method. Without it the failure is a
        // NullPointerException on the event thread, after which setCurrent
        // stops working and every later screen change is silently lost.
        DeviceFactory.setDevice(new TestDevice(deviceName, width, height).withInput());
        EmulatorRecords.install();
    }

    public void start() throws Exception
    {
        midlet.startApp();
        display = Display.getDisplay(midlet);
    }

    public void stop() throws Exception
    {
        midlet.destroyApp(true);
    }

    public Display display() { return display; }

    public Displayable current() { return display.getCurrent(); }

    /** Invoke a command as the user would, on the current screen. */
    public void press(Command command) throws Exception
    {
        midlet.commandAction(command, display.getCurrent());
    }

    /**
     * Press the command with this label on the current screen.
     *
     * By label rather than by field, because that is what survives obfuscation
     * and what the user actually sees.
     */
    public boolean press(String label) throws Exception
    {
        Command c = command(display.getCurrent(), label);
        if (c == null) { return false; }
        press(c);
        return true;
    }

    /**
     * Send a key to the current Canvas.
     *
     * Raw key codes, not game actions. Both Canvas screens in this client
     * accept the keypad codes directly - KEY_NUM2/8 for up and down, KEY_NUM5
     * for fire - which is what makes them drivable on a device that has no
     * buttons to map a game action from.
     *
     * keyPressed is protected in Canvas, so it takes reflection to reach, the
     * same way getCommands does.
     */
    public boolean key(int keyCode) throws Exception
    {
        Displayable screen = display.getCurrent();
        if (!(screen instanceof Canvas)) { return false; }
        Method m = Canvas.class.getDeclaredMethod("keyPressed",
                new Class[] { Integer.TYPE });
        m.setAccessible(true);
        m.invoke(screen, new Object[] { new Integer(keyCode) });
        return true;
    }

    /** Type into the current TextBox. */
    public boolean type(String text)
    {
        Displayable screen = display.getCurrent();
        if (!(screen instanceof TextBox)) { return false; }
        ((TextBox) screen).setString(text);
        return true;
    }

    /** Wait until the current screen is not {@code previous}. */
    public Displayable awaitChange(Displayable previous, int timeoutMs)
            throws Exception
    {
        for (int waited = 0; waited < timeoutMs; waited += 50)
        {
            Displayable c = display.getCurrent();
            if (c != null && c != previous) { return c; }
            Thread.sleep(50);
        }
        return display.getCurrent();
    }

    /** Wait until a command with this label is offered by the current screen. */
    public boolean awaitCommand(String label, int timeoutMs) throws Exception
    {
        for (int waited = 0; waited < timeoutMs; waited += 100)
        {
            if (command(display.getCurrent(), label) != null) { return true; }
            Thread.sleep(100);
        }
        return false;
    }

    /** Wait until the current screen's title contains this text. */
    public boolean awaitTitle(String contains, int timeoutMs) throws Exception
    {
        for (int waited = 0; waited < timeoutMs; waited += 100)
        {
            String t = title(display.getCurrent());
            if (t != null && t.indexOf(contains) >= 0) { return true; }
            Thread.sleep(100);
        }
        return false;
    }

    public static String describe(Displayable screen)
    {
        if (screen == null) { return "(none)"; }
        // Titles and Alert bodies can contain a peer title or message summary.
        // Driver output is release evidence, so screen class is the privacy-safe
        // diagnostic boundary. Feature-specific assertions read bounded status
        // flags directly instead of dumping user-visible text.
        return screen.getClass().getName();
    }

    /**
     * Put a screen on the display directly.
     *
     * Only for getting unstuck. An Alert with Alert.FOREVER waits to be
     * dismissed, and this harness has no UI to dismiss it with, so a driver
     * that has read the alert needs a way past it.
     */
    public void show(Displayable screen)
    {
        display.setCurrent(screen);
    }

    public static String title(Displayable screen)
    {
        if (screen == null) { return null; }
        try { return screen.getTitle(); }
        catch (Throwable t) { return null; }
    }

    /** Labels the current screen offers, for a driver that has to report. */
    public String labels() throws Exception
    {
        Vector commands = commandsOf(display.getCurrent());
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < commands.size(); i++)
        {
            if (i > 0) { sb.append(", "); }
            sb.append(((Command) commands.elementAt(i)).getLabel());
        }
        return sb.toString();
    }

    public static Command command(Displayable screen, String label)
            throws Exception
    {
        Vector commands = commandsOf(screen);
        for (int i = 0; i < commands.size(); i++)
        {
            Command c = (Command) commands.elementAt(i);
            if (label.equals(c.getLabel())) { return c; }
        }
        return null;
    }

    /**
     * {@code Displayable.getCommands()} is package-private in MIDP, which is
     * why this check has to live outside src/ - see EmulatorSmokeTest.
     */
    private static Vector commandsOf(Displayable screen) throws Exception
    {
        if (screen == null) { return new Vector(); }
        Method m = Displayable.class.getDeclaredMethod("getCommands", new Class[0]);
        m.setAccessible(true);
        Object result = m.invoke(screen, new Object[0]);
        if (result instanceof Vector) { return (Vector) result; }
        Vector out = new Vector();
        if (result instanceof Command[])
        {
            Command[] arr = (Command[]) result;
            for (int i = 0; i < arr.length; i++) { out.addElement(arr[i]); }
        }
        return out;
    }

    /** Exposes the protected MIDlet lifecycle to the driver. */
    private static final class MidletHarness extends tg.app.TgMidlet
    {
        void startApp2() throws Exception { startApp(); }
        protected void startApp() { super.startApp(); }
        protected void destroyApp(boolean u) { super.destroyApp(u); }
    }
}
