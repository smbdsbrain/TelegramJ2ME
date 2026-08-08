package tgtest;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextBox;

/**
 * Semantic E2E driver for an exact packaged normal or obfuscated RC JAR.
 *
 * Unlike {@link EmulatorDriver}, this class deliberately imports no production
 * type except the kept MIDlet entry point inherited by {@link EmulatorHarness}.
 * It can therefore run with dist/&lt;artifact&gt;.jar ahead of test-classes on the
 * classpath even when every implementation class has been renamed.
 *
 * Usernames are exchanged through ignored private files and never printed.
 * Peer titles and message bodies are likewise absent from output. The only
 * shared text is an opaque test marker created by the orchestration script.
 */
public final class PackagedRcE2EDriver
{
    private static final int CONNECT_MS = 150000;
    private static final int RPC_MS = 90000;

    public static void main(String[] args) throws Exception
    {
        int exit = 1;
        EmulatorHarness app = null;
        try
        {
            String role = arg(args, 0);
            File state = new File(arg(args, 1));
            String side = arg(args, 2).toLowerCase();
            if (!state.isDirectory() || !("a".equals(side) || "b".equals(side)))
            {
                throw new Exception("invalid private E2E state directory/side");
            }
            EmulatorHarness.install("rc-e2e", 240, 320);
            app = new EmulatorHarness();
            app.start();
            waitForHeapProbe(app);
            requireSession(app);

            if ("identity".equals(role))
            {
                write(new File(state, side + ".username"), selfUsername(app));
                exit = 0;
            }
            else if ("sender".equals(role) && "a".equals(side))
            {
                exit = sender(app, state) ? 0 : 1;
            }
            else if ("receiver".equals(role) && "b".equals(side))
            {
                exit = receiver(app, state) ? 0 : 1;
            }
            else if ("cleanup".equals(role) && "a".equals(side))
            {
                exit = cleanup(app, state) ? 0 : 1;
            }
            else
            {
                throw new Exception("invalid packaged RC E2E role");
            }
        }
        catch (Throwable t)
        {
            System.out.println("PACKAGED RC E2E FAIL: "
                    + t.getClass().getName() + ": " + safe(t.getMessage()));
            exit = 1;
        }
        finally
        {
            if (app != null) { try { app.stop(); } catch (Throwable ignored) { } }
            System.out.flush();
        }
        System.exit(exit);
    }

    private static boolean sender(EmulatorHarness app, File state)
            throws Exception
    {
        String peer = read(new File(state, "b.username"));
        String marker = read(new File(state, "marker"));
        String original = marker + " https://example.com e2e@example.com"
                + " +12025550123";
        String edited = marker + " edited https://example.com"
                + " e2e@example.com +12025550123";
        openPeer(app, peer);
        awaitFile(new File(state, "receiver-ready"), CONNECT_MS);

        press(app, "Write");
        TextBox composer = awaitTextBox(app, 10000);
        composer.setString(original);
        press(app, "Send");
        awaitCanvas(app, 15000);
        awaitFile(new File(state, "receiver-saw-message"), RPC_MS);

        if (!findMessage(app, marker, true))
        {
            throw new Exception("marked message was not found");
        }
        if ("1".equals(System.getProperty("tg.driver.reactionflow")))
        {
            reactionRace(app);
        }
        press(app, "View full text");
        TextBox full = awaitTextBox(app, 10000);
        if (!original.equals(full.getString()))
        {
            throw new Exception("full-text copy did not match the marked send");
        }
        press(app, "Links");
        List entities = awaitList(app, "Message actions", 10000);
        if (entities.size() < 3)
        {
            throw new Exception("entity picker did not expose URL/email/phone");
        }
        press(app, "Select");
        Form confirm = awaitForm(app, "Open external target", 10000);
        String confirmation = formText(confirm);
        if (confirmation.indexOf("Shown text:") < 0
                || confirmation.indexOf("Actual target:") < 0)
        {
            throw new Exception("external confirmation omitted label or target");
        }
        // Back is the assertion: the real platform launch must not happen.
        press(app, "Back");
        awaitList(app, "Message actions", 5000);
        press(app, "Back");
        awaitTextBox(app, 5000);
        press(app, "Back");
        awaitCanvas(app, 5000);

        if (!app.awaitCommand("Edit", 10000))
        {
            throw new Exception("opened own search result offered no Edit");
        }
        press(app, "Edit");
        TextBox edit = awaitTextBox(app, 10000);
        edit.setString(edited);
        signal(state, "edit-requested");
        press(app, "Send");
        awaitCanvas(app, 15000);
        awaitFile(new File(state, "receiver-saw-edit"), RPC_MS);
        awaitFile(new File(state, "receiver-saw-edited-label"), RPC_MS);

        press(app, "Delete");
        awaitForm(app, "Delete message", 10000);
        press(app, "For everyone");
        awaitCanvas(app, 20000);
        signal(state, "cleanup-requested");
        File cleaned = new File(state, "receiver-confirmed-cleanup");
        File manual = new File(state, "manual-cleanup-required");
        awaitEither(cleaned, manual, RPC_MS);
        signal(state, "sender-complete");
        System.out.println("PACKAGED RC E2E SENDER PASS");
        return true;
    }

    /** Reproduce the handset foreground-worker race on a fragmented slow link. */
    private static void reactionRace(EmulatorHarness app) throws Exception
    {
        long openedAt = System.currentTimeMillis();
        press(app, "Reactions");
        Canvas palette = awaitReactionPalette(app, 500);
        long openMs = System.currentTimeMillis() - openedAt;
        if (openMs > 500)
        {
            throw new Exception("reaction palette waited on the network: "
                    + openMs + " ms");
        }

        // Select must target the first emoji, not the View reactions action.
        press(app, "Select");
        awaitChat(app, 3000);

        // Wait for the authoritative update without naming obfuscated fields.
        long until = System.currentTimeMillis() + RPC_MS;
        int lastProbe = 0;
        for (;;)
        {
            press(app, "Reactions");
            palette = awaitReactionPalette(app, 500);
            lastProbe = paletteProbe(palette);
            if ((lastProbe & 3) == 3) { break; }
            press(app, "Back");
            awaitChat(app, 3000);
            if (System.currentTimeMillis() >= until)
            {
                throw new Exception("reaction update did not reach the palette"
                        + " (chosen=" + ((lastProbe & 1) != 0)
                        + " actions=" + ((lastProbe & 2) != 0) + ")");
            }
            Thread.sleep(500);
        }

        // Default is the first emoji, so one Up reaches View reactions.
        press(app, "Up");
        Displayable beforeActors = app.current();
        press(app, "Select");
        Displayable actors = awaitDifferentScreen(app, beforeActors, 3000);
        if (EmulatorHarness.command(actors, "Select") != null
                || EmulatorHarness.command(actors, "Find messages") != null)
        {
            throw new Exception("reaction actor Loading screen did not open");
        }

        // Leave while getMessageReactionsList is slow, then toggle the emoji.
        // An actor read on the foreground worker yields Finishing ... first.
        press(app, "Back");
        awaitSameScreen(app, beforeActors, 3000);
        press(app, "Down");
        press(app, "Select");
        Canvas chat = awaitChat(app, 3000);
        awaitChatStatusClear(app, chat, "reacting...", RPC_MS);
        System.out.println("PACKAGED RC SLOW REACTION RACE PASS");
    }

    private static Canvas awaitReactionPalette(EmulatorHarness app, int timeout)
            throws Exception
    {
        long until = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < until)
        {
            Displayable current = app.current();
            if (current instanceof Canvas
                    && EmulatorHarness.command(current, "Select") != null
                    && EmulatorHarness.command(current, "Find messages") == null)
            {
                return (Canvas) current;
            }
            Thread.sleep(25);
        }
        throw new Exception("reaction palette did not open locally");
    }

    /**
     * Probe chosen[] plus both action flags by type rather than obfuscated name.
     * Bit 0 is a chosen emoji; bit 1 means remove and actor actions are visible.
     */
    private static int paletteProbe(Canvas palette)
    {
        try
        {
            int enabled = 0;
            boolean chosen = false;
            Class type = palette.getClass();
            while (type != null)
            {
                Field[] fields = type.getDeclaredFields();
                for (int i = 0; i < fields.length; i++)
                {
                    if (Modifier.isStatic(fields[i].getModifiers())) { continue; }
                    fields[i].setAccessible(true);
                    if (fields[i].getType() == Boolean.TYPE
                            && fields[i].getBoolean(palette))
                    {
                        enabled++;
                    }
                    else if (fields[i].getType() == boolean[].class)
                    {
                        boolean[] values = (boolean[]) fields[i].get(palette);
                        for (int j = 0; values != null && j < values.length; j++)
                        {
                            if (values[j]) { chosen = true; break; }
                        }
                    }
                }
                type = type.getSuperclass();
            }
            return (chosen ? 1 : 0) | (enabled >= 2 ? 2 : 0);
        }
        catch (Throwable ignored) { return 0; }
    }

    private static Displayable awaitDifferentScreen(EmulatorHarness app,
            Displayable before, int timeout) throws Exception
    {
        long until = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < until)
        {
            Displayable current = app.current();
            if (current != before) { return current; }
            Thread.sleep(25);
        }
        throw new Exception("requested remote screen did not appear");
    }

    private static void awaitSameScreen(EmulatorHarness app,
            Displayable wanted, int timeout) throws Exception
    {
        long until = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < until)
        {
            if (app.current() == wanted) { return; }
            Thread.sleep(25);
        }
        throw new Exception("Back did not restore the reaction palette");
    }

    private static Canvas awaitChat(EmulatorHarness app, int timeout)
            throws Exception
    {
        long until = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < until)
        {
            Displayable current = app.current();
            if (current instanceof Canvas
                    && EmulatorHarness.command(current, "Find messages") != null)
            {
                return (Canvas) current;
            }
            Thread.sleep(25);
        }
        throw new Exception("reaction action did not return to the chat");
    }

    private static void awaitChatStatusClear(EmulatorHarness app, Canvas chat,
            String status, int timeout) throws Exception
    {
        long until = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < until)
        {
            if (app.current() instanceof Alert)
            {
                throw new Exception("reaction action raised an alert");
            }
            if (app.current() == chat && canvasContains(chat, "reaction failed"))
            {
                throw new Exception("reaction action failed");
            }
            if (app.current() == chat && !canvasContains(chat, status)) { return; }
            Thread.sleep(100);
        }
        throw new Exception("reaction request did not finish on the slow link");
    }

    private static boolean receiver(EmulatorHarness app, File state)
            throws Exception
    {
        String peer = read(new File(state, "a.username"));
        String marker = read(new File(state, "marker"));
        String edited = marker + " edited https://example.com"
                + " e2e@example.com +12025550123";
        openPeer(app, peer);
        signal(state, "receiver-ready");

        awaitCanvasText(app, marker, CONNECT_MS);
        signal(state, "receiver-saw-message");
        awaitFile(new File(state, "edit-requested"), RPC_MS);
        awaitCanvasText(app, edited, RPC_MS);
        signal(state, "receiver-saw-edit");
        awaitEditedLabel(app, RPC_MS);
        signal(state, "receiver-saw-edited-label");

        awaitFile(new File(state, "cleanup-requested"), RPC_MS);
        try
        {
            if (findMessage(app, marker, false))
            {
                throw new Exception("marked message still exists");
            }
            signal(state, "receiver-confirmed-cleanup");
        }
        catch (Throwable t)
        {
            // The marker file is retained for the caller to report privately.
            signal(state, "manual-cleanup-required");
        }
        awaitFile(new File(state, "sender-complete"), 30000);
        System.out.println("PACKAGED RC E2E RECEIVER PASS");
        return true;
    }

    /** Best-effort deterministic cleanup after a sender-side assertion fails. */
    private static boolean cleanup(EmulatorHarness app, File state)
            throws Exception
    {
        String peer = read(new File(state, "b.username"));
        String marker = read(new File(state, "marker"));
        openPeer(app, peer);
        if (!findMessage(app, marker, true))
        {
            System.out.println("PACKAGED RC E2E CLEANUP PASS (already absent)");
            return true;
        }
        press(app, "Delete");
        awaitForm(app, "Delete message", 10000);
        press(app, "For everyone");
        awaitCanvas(app, 20000);
        Thread.sleep(5000);
        if (findMessage(app, marker, false))
        {
            throw new Exception("marked message still exists after cleanup");
        }
        System.out.println("PACKAGED RC E2E CLEANUP PASS");
        return true;
    }

    private static void requireSession(EmulatorHarness app) throws Exception
    {
        if (!app.awaitCommand("Connect", 10000))
        {
            throw new Exception("start screen offered no Connect");
        }
        press(app, "Connect");
        long until = System.currentTimeMillis() + CONNECT_MS;
        while (System.currentTimeMillis() < until)
        {
            if (EmulatorHarness.command(app.current(), "Saved Messages") != null)
            {
                Thread.sleep(5000);
                return;
            }
            Thread.sleep(250);
        }
        throw new Exception("stored production session did not reach dialogs");
    }

    private static void waitForHeapProbe(EmulatorHarness app) throws Exception
    {
        long until = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < until)
        {
            Displayable current = app.current();
            if (current instanceof Form
                    && formText((Form) current).indexOf("Measuring available memory") < 0)
            {
                return;
            }
            Thread.sleep(100);
        }
        throw new Exception("heap measurement did not finish");
    }

    private static String selfUsername(EmulatorHarness app) throws Exception
    {
        Displayable before = app.current();
        press(app, "My profile");
        long until = System.currentTimeMillis() + RPC_MS;
        List profile = null;
        while (System.currentTimeMillis() < until)
        {
            Displayable current = app.current();
            if (current instanceof List && current != before
                    && EmulatorHarness.command(current, "Edit profile") != null)
            {
                profile = (List) current;
                break;
            }
            Thread.sleep(100);
        }
        if (profile == null) { throw new Exception("self profile did not open"); }
        for (int i = 0; i < profile.size(); i++)
        {
            String row = profile.getString(i);
            if (row != null && row.length() > 1 && row.charAt(0) == '@')
            {
                return row.substring(1);
            }
        }
        throw new Exception("authorized self profile has no username");
    }

    private static void openPeer(EmulatorHarness app, String username)
            throws Exception
    {
        press(app, "Find chat");
        TextBox query = awaitTextBox(app, 10000);
        query.setString(username);
        press(app, "Search");
        List results = awaitList(app, "Results for", RPC_MS);
        int found = -1;
        for (int i = 0; i < results.size(); i++)
        {
            String row = results.getString(i);
            if (row != null && row.indexOf("@" + username) >= 0)
            {
                found = i;
                break;
            }
        }
        if (found < 0) { throw new Exception("cross-account peer was not resolved"); }
        results.setSelectedIndex(found, true);
        press(app, "Open");
        awaitCanvas(app, RPC_MS);
        if (!app.awaitCommand("Find messages", RPC_MS))
        {
            throw new Exception("resolved peer did not open as a chat");
        }
        Thread.sleep(5000);
    }

    /** Search the open chat and optionally open a matching result. */
    private static boolean findMessage(EmulatorHarness app, String marker,
            boolean openIfFound) throws Exception
    {
        press(app, "Find messages");
        TextBox query = awaitTextBox(app, 10000);
        query.setString(marker);
        press(app, "Search");
        List results = awaitList(app, "Messages ", RPC_MS);
        boolean found = false;
        int foundAt = -1;
        for (int i = 0; i < results.size(); i++)
        {
            String row = results.getString(i);
            if (row != null && row.indexOf(marker) >= 0)
            {
                found = true;
                foundAt = i;
                break;
            }
        }
        if (found && openIfFound)
        {
            results.setSelectedIndex(foundAt, true);
            press(app, "Open");
            awaitCanvas(app, RPC_MS);
            Thread.sleep(5000);
        }
        return found;
    }

    /** Inspect only the current Canvas object graph; no class/member names. */
    private static boolean canvasContains(Canvas canvas, String needle)
    {
        try
        {
            Field[] fields = canvas.getClass().getDeclaredFields();
            for (int i = 0; i < fields.length; i++)
            {
                Field field = fields[i];
                if (Modifier.isStatic(field.getModifiers())) { continue; }
                field.setAccessible(true);
                Object value = field.get(canvas);
                if (value instanceof String
                        && ((String) value).indexOf(needle) >= 0) { return true; }
                if (value instanceof String[])
                {
                    String[] strings = (String[]) value;
                    for (int j = 0; j < strings.length; j++)
                    {
                        if (strings[j] != null && strings[j].indexOf(needle) >= 0)
                        {
                            return true;
                        }
                    }
                }
                if (value instanceof Object[])
                {
                    Object[] objects = (Object[]) value;
                    for (int j = 0; j < objects.length; j++)
                    {
                        if (objectHasString(objects[j], needle)) { return true; }
                    }
                }
            }
        }
        catch (Throwable ignored) { }
        return false;
    }

    private static boolean objectHasString(Object object, String needle)
            throws Exception
    {
        if (object == null) { return false; }
        Field[] fields = object.getClass().getDeclaredFields();
        for (int i = 0; i < fields.length; i++)
        {
            Field field = fields[i];
            if (Modifier.isStatic(field.getModifiers())
                    || field.getType() != String.class) { continue; }
            field.setAccessible(true);
            String value = (String) field.get(object);
            if (value != null && value.indexOf(needle) >= 0) { return true; }
        }
        return false;
    }

    private static boolean canvasHasExactLine(Canvas canvas, String line)
    {
        try
        {
            Field[] fields = canvas.getClass().getDeclaredFields();
            for (int i = 0; i < fields.length; i++)
            {
                fields[i].setAccessible(true);
                Object value = fields[i].get(canvas);
                if (!(value instanceof String[])) { continue; }
                String[] strings = (String[]) value;
                for (int j = 0; j < strings.length; j++)
                {
                    if (line.equals(strings[j])) { return true; }
                }
            }
        }
        catch (Throwable ignored) { }
        return false;
    }

    private static void awaitCanvasText(EmulatorHarness app, String text,
            int timeout) throws Exception
    {
        long until = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < until)
        {
            Displayable current = app.current();
            if (current instanceof Canvas && canvasContains((Canvas) current, text))
            {
                return;
            }
            Thread.sleep(250);
        }
        throw new Exception("live marked message text was not observed");
    }

    private static void awaitEditedLabel(EmulatorHarness app, int timeout)
            throws Exception
    {
        long until = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < until)
        {
            Displayable current = app.current();
            if (current instanceof Canvas
                    && canvasHasExactLine((Canvas) current, "edited")) { return; }
            Thread.sleep(250);
        }
        throw new Exception("edited label was not observed on receiver");
    }

    private static void press(EmulatorHarness app, String label) throws Exception
    {
        if (!app.awaitCommand(label, 10000) || !app.press(label))
        {
            throw new Exception("command unavailable: " + label);
        }
    }

    private static TextBox awaitTextBox(EmulatorHarness app, int timeout)
            throws Exception
    {
        long until = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < until)
        {
            if (app.current() instanceof TextBox) { return (TextBox) app.current(); }
            Thread.sleep(100);
        }
        throw new Exception("TextBox did not appear");
    }

    private static Canvas awaitCanvas(EmulatorHarness app, int timeout)
            throws Exception
    {
        long until = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < until)
        {
            if (app.current() instanceof Canvas) { return (Canvas) app.current(); }
            Thread.sleep(100);
        }
        throw new Exception("chat Canvas did not appear");
    }

    private static List awaitList(EmulatorHarness app, String title,
            int timeout) throws Exception
    {
        long until = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < until)
        {
            Displayable current = app.current();
            if (current instanceof List)
            {
                String actual = EmulatorHarness.title(current);
                if (title == null || (actual != null && actual.indexOf(title) >= 0))
                {
                    return (List) current;
                }
            }
            Thread.sleep(100);
        }
        throw new Exception("expected List did not appear: " + safe(title));
    }

    private static Form awaitForm(EmulatorHarness app, String title, int timeout)
            throws Exception
    {
        long until = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < until)
        {
            Displayable current = app.current();
            if (current instanceof Form)
            {
                String actual = EmulatorHarness.title(current);
                if (actual != null && actual.indexOf(title) >= 0) return (Form) current;
            }
            Thread.sleep(100);
        }
        throw new Exception("expected Form did not appear");
    }

    private static String formText(Form form)
    {
        StringBuffer out = new StringBuffer();
        for (int i = 0; i < form.size(); i++)
        {
            if (form.get(i) instanceof StringItem)
            {
                String text = ((StringItem) form.get(i)).getText();
                if (text != null) { out.append(text); }
            }
        }
        return out.toString();
    }

    private static void signal(File state, String name) throws Exception
    {
        write(new File(state, name), "ok");
    }

    private static void awaitFile(File file, int timeout) throws Exception
    {
        long until = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < until)
        {
            if (file.isFile()) { return; }
            Thread.sleep(100);
        }
        throw new Exception("peer E2E phase timed out");
    }

    private static void awaitEither(File first, File second, int timeout)
            throws Exception
    {
        long until = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < until)
        {
            if (first.isFile() || second.isFile()) { return; }
            Thread.sleep(100);
        }
        throw new Exception("cleanup phase timed out");
    }

    private static String read(File file) throws Exception
    {
        FileInputStream in = new FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try
        {
            byte[] buffer = new byte[128];
            for (int n; (n = in.read(buffer)) >= 0; ) { out.write(buffer, 0, n); }
        }
        finally { in.close(); }
        return new String(out.toByteArray(), "UTF-8").trim();
    }

    private static void write(File file, String text) throws Exception
    {
        FileOutputStream out = new FileOutputStream(file);
        try { out.write(text.getBytes("UTF-8")); }
        finally { out.close(); }
    }

    private static String arg(String[] args, int at) throws Exception
    {
        if (at >= args.length || args[at] == null || args[at].length() == 0)
        {
            throw new Exception("missing packaged RC E2E argument");
        }
        return args[at];
    }

    private static String safe(String text)
    {
        if (text == null) { return "failure"; }
        return text.length() > 160 ? text.substring(0, 160) : text;
    }
}
