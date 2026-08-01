package tgtest;

import java.io.File;
import java.io.FileInputStream;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Item;

import tg.api.Peer;
import tg.diag.CrashLog;
import tg.diag.Diag;
import tg.mem.MemoryBudget;
import tg.mem.MemoryPressure;
import tg.plat.RmsAuthKeyStore;
import tg.ui.DialogListScreen;

/**
 * Drives the client through MicroEmulator's MIDP runtime from a script.
 *
 * The GUI emulator is the only way most of this client has ever been exercised,
 * and it needs a person. That makes the flows that matter most - connect, sign
 * in, open a chat, load a photo - the ones with no automated coverage at all:
 * {@code EmulatorSmokeTest} deliberately stops before the network.
 *
 * This drives the same screens by command label and reports what happened,
 * including the diagnostic ring, which is otherwise readable only on the Log
 * screen of a running emulator.
 *
 * Scenarios:
 * <pre>
 *   probe                measure the heap, print the derived budgets, exit
 *   route  &lt;mode&gt;        set the connection mode and connect
 *   login  &lt;phone&gt; &lt;codeFile&gt;   connect, request a code, wait for the file
 *   session              use a stored session: dialogs, open the first chat
 * </pre>
 *
 * The code file exists because the sign-in code arrives on the user's phone and
 * {@code phoneCodeHash} lives only in memory, so it has to be entered by the
 * same process that asked for it. Writing one line into a file is the whole of
 * the human's job.
 *
 * This talks to real Telegram servers in every scenario except {@code probe}.
 */
public final class EmulatorDriver
{
    private static final int CONNECT_TIMEOUT_MS = 120000;

    /** Remembered so a stuck modal alert can be stepped past. */
    private static Displayable dialogList;

    public static void main(String[] args) throws Exception
    {
        String scenario = args.length > 0 ? args[0] : "probe";

        holdBallast();
        EmulatorHarness.install("driver", 240, 320);
        if (System.getProperty("tg.driver.remeasure") != null) { forgetMeasurement(); }
        EmulatorHarness app = new EmulatorHarness();

        int exit = 0;
        try
        {
            app.start();
            // setCurrent posts to MicroEmulator's event dispatcher rather than
            // switching inline, so the first screen has to be waited for.
            System.out.println("start screen: "
                    + EmulatorHarness.describe(app.awaitChange(null, 10000)));
            awaitHeapMeasurement(app);

            if ("probe".equals(scenario)) { exit = 0; }
            else if ("route".equals(scenario))
            {
                exit = route(app, args.length > 1 ? args[1] : "Auto") ? 0 : 1;
            }
            else if ("login".equals(scenario))
            {
                exit = login(app, arg(args, 1), arg(args, 2), arg(args, 3)) ? 0 : 1;
            }
            else if ("session".equals(scenario))
            {
                exit = session(app, arg(args, 1), arg(args, 2)) ? 0 : 1;
            }
            else if ("photos".equals(scenario))
            {
                exit = photos(app, arg(args, 1)) ? 0 : 1;
            }
            else if ("minheap".equals(scenario))
            {
                exit = minheap(app, arg(args, 1), arg(args, 2)) ? 0 : 1;
            }
            else
            {
                System.out.println("unknown scenario " + scenario);
                exit = 2;
            }
        }
        catch (Throwable t)
        {
            System.out.println("DRIVER FAILED: " + t);
            t.printStackTrace(System.out);
            exit = 1;
        }
        finally
        {
            dumpMemory();
            dumpLog();
            try { app.stop(); } catch (Throwable ignored) { }
        }
        System.out.flush();
        System.exit(exit);
    }

    // ------------------------------------------------------------- scenarios

    /**
     * Wait for the client's own first-launch measurement and print what it
     * derived. With a working record store this also proves the value is stored
     * and reused, because a second run reports "stored" rather than "measured".
     */
    private static void awaitHeapMeasurement(EmulatorHarness app) throws Exception
    {
        for (int i = 0; i < 200 && MemoryBudget.source() == MemoryBudget.SOURCE_DEFAULT; i++)
        {
            Thread.sleep(50);
        }
        String[] lines = MemoryBudget.lines();
        for (int i = 0; i < lines.length; i++) { System.out.println("  " + lines[i]); }
    }

    /** Set the connection mode in Settings and save it, the way a user would. */
    private static boolean setMode(EmulatorHarness app, String modeLabel)
            throws Exception
    {
        Displayable start = app.current();
        if (!app.press("Settings"))
        {
            System.out.println("no Settings command on " + EmulatorHarness.describe(start));
            return false;
        }
        Displayable settings = app.awaitChange(start, 5000);
        if (!(settings instanceof Form))
        {
            System.out.println("Settings is not a Form: " + EmulatorHarness.describe(settings));
            return false;
        }

        Form form = (Form) settings;
        ChoiceGroup mode = null;
        for (int i = 0; i < form.size(); i++)
        {
            Item item = form.get(i);
            if (item instanceof ChoiceGroup
                    && "Mode".equals(((ChoiceGroup) item).getLabel()))
            {
                mode = (ChoiceGroup) item;
                break;
            }
        }
        if (mode == null) { System.out.println("no Mode choice on Settings"); return false; }

        int index = -1;
        for (int i = 0; i < mode.size(); i++)
        {
            if (modeLabel.equalsIgnoreCase(mode.getString(i))) { index = i; }
        }
        if (index < 0)
        {
            System.out.println("no such mode: " + modeLabel);
            return false;
        }
        System.out.println("mode: " + mode.getString(mode.getSelectedIndex())
                           + " -> " + mode.getString(index));
        mode.setSelectedIndex(index, true);

        if (!app.press("Save")) { System.out.println("no Save command"); return false; }
        app.awaitChange(settings, 5000);

        // Saving a changed mode makes the client reconnect on its own, so the
        // screen after Save may already be past the start screen. Only press
        // Back while there is one to press.
        for (int i = 0; i < 4 && app.press("Back"); i++)
        {
            app.awaitChange(app.current(), 2000);
        }
        System.out.println("after Save: " + EmulatorHarness.describe(app.current()));
        return true;
    }

    private static boolean route(EmulatorHarness app, String modeLabel)
            throws Exception
    {
        if (!setMode(app, modeLabel)) { return false; }
        return connect(app);
    }

    /**
     * Connect and wait for the client to reach either the phone prompt (no
     * session) or the dialog list (session restored).
     */
    private static boolean connect(EmulatorHarness app) throws Exception
    {
        long t0 = System.currentTimeMillis();
        if (settled(app) != null)
        {
            System.out.println("already past connect: "
                               + EmulatorHarness.describe(app.current()));
            return true;
        }
        if (app.press("Connect"))
        {
            System.out.println("pressed Connect, waiting up to "
                               + (CONNECT_TIMEOUT_MS / 1000) + "s");
        }
        else
        {
            // Saving a changed connection mode reconnects by itself, so there
            // is often nothing left to press. Waiting is still correct.
            System.out.println("no Connect command on "
                               + EmulatorHarness.describe(app.current())
                               + " - waiting for the connection already in flight");
        }

        for (int waited = 0; waited < CONNECT_TIMEOUT_MS; waited += 250)
        {
            String where = settled(app);
            if (where != null)
            {
                System.out.println(where + " in "
                                   + (System.currentTimeMillis() - t0) + " ms");
                return true;
            }
            Thread.sleep(250);
        }
        System.out.println("still on " + EmulatorHarness.describe(app.current())
                           + " after " + (System.currentTimeMillis() - t0) + " ms");
        System.out.println("commands: " + app.labels());
        return false;
    }

    /**
     * Whether the client has finished connecting, and how it ended up.
     *
     * Two outcomes count as done: the phone prompt (the key is not tied to an
     * account) and the chat list (a stored session was restored).
     */
    private static String settled(EmulatorHarness app) throws Exception
    {
        Displayable now = app.current();
        if (now == null) { return null; }
        // By command label, not by title: the dialog list titles itself with
        // the account, and a label is what survives obfuscation anyway.
        if (EmulatorHarness.command(now, "Saved Messages") != null)
        {
            return "restored a session";
        }
        String title = EmulatorHarness.title(now);
        if (title != null && title.indexOf("Phone number") >= 0)
        {
            return "reached the phone prompt";
        }
        return null;
    }

    private static boolean login(EmulatorHarness app, String modeLabel,
                                 String phone, String codeFile) throws Exception
    {
        if (phone == null || codeFile == null)
        {
            System.out.println("usage: login <mode> <phone> <codeFile>");
            return false;
        }
        if (!setMode(app, modeLabel == null ? "Auto" : modeLabel)) { return false; }
        if (!connect(app)) { return false; }

        String title = EmulatorHarness.title(app.current());
        if (title != null && title.indexOf("Chats") >= 0)
        {
            System.out.println("already signed in; nothing to do");
            return true;
        }

        if (!app.type(phone)) { System.out.println("phone prompt is not a TextBox"); return false; }
        Displayable phoneScreen = app.current();
        if (!app.press("Next")) { System.out.println("no Next on the phone prompt"); return false; }

        // The code screen is titled with the delivery method the server chose.
        if (!app.awaitCommand("Sign in", 90000))
        {
            System.out.println("no code prompt: " + EmulatorHarness.describe(app.current()));
            System.out.println("commands: " + app.labels());
            return false;
        }
        System.out.println("code requested: " + EmulatorHarness.describe(app.current()));
        System.out.println("WAITING FOR CODE -> write it into " + codeFile);

        String code = awaitCode(new File(codeFile), 600000);
        if (code == null) { System.out.println("no code arrived"); return false; }
        System.out.println("code received (" + code.length() + " digits)");

        if (!app.type(code)) { System.out.println("code prompt is not a TextBox"); return false; }
        Displayable codeScreen = app.current();
        if (!app.press("Sign in")) { System.out.println("no Sign in command"); return false; }

        for (int waited = 0; waited < 120000; waited += 250)
        {
            if (EmulatorHarness.command(app.current(), "Saved Messages") != null)
            {
                System.out.println("SIGNED IN: " + EmulatorHarness.describe(app.current()));
                return openFirstChat(app);
            }
            Thread.sleep(250);
        }
        System.out.println("sign-in did not reach the chat list: "
                           + EmulatorHarness.describe(app.current()));
        System.out.println("commands: " + app.labels());
        return false;
    }

    private static boolean session(EmulatorHarness app, String modeLabel,
                                   String sendText) throws Exception
    {
        if (modeLabel != null && modeLabel.length() > 0
                && !setMode(app, modeLabel)) { return false; }
        if (!connect(app)) { return false; }
        if (EmulatorHarness.command(app.current(), "Saved Messages") == null)
        {
            System.out.println("no stored session; run the login scenario first");
            return false;
        }

        // Let the dialog list finish loading before touching anything.
        //
        // Worker.submit drops a task when the worker is busy rather than
        // queueing it, so pressing Open while messages.getDialogs is still in
        // flight gets messages.getHistory refused - the chat opens empty and
        // the finishing dialog load then pulls the screen back to the list. A
        // person is never this fast; a driver is. Known and deliberately
        // deferred, so wait rather than work around it in the client.
        Thread.sleep(6000);

        boolean ok = openFirstChat(app);
        if (ok) { ok = pageHistoryBack(app); }

        // Back to the list before the next transition, so each one starts from
        // the same place whatever the previous one did. Bounded, and stops as
        // soon as Back changes nothing: the root screen still offers Back, it
        // just has nowhere to go, so an unbounded loop here never ends.
        ok = returnToDialogList(app) && ok;

        if (sendText != null && sendText.length() > 0)
        {
            ok = sendToSavedMessages(app, sendText) && ok;
        }
        return ok;
    }

    /**
     * How little heap this client actually needs, measured rather than assumed.
     *
     * Prints one verdict line per run so a sweep of -Xmx values reads as a
     * table. Each stage is reported separately because they fail at different
     * heights: an avatar in the dialog list is a decoded Image per row, inline
     * thumbnails are another dozen, and a full photo is the single largest
     * allocation the client ever makes. Which one goes first is the difference
     * between "needs 5 MB" and "needs 5 MB if you want pictures".
     *
     * Everything is wrapped: at the bottom of the ladder the interesting
     * outcome is a failure, and a driver that dies without printing its verdict
     * has measured nothing.
     */
    private static boolean minheap(EmulatorHarness app, String chatTitle,
                                   String pictures) throws Exception
    {
        boolean wantPictures = !"off".equalsIgnoreCase(pictures);
        String connect = "fail";
        String chat = "skip";
        String photo = "skip";
        int avatars = -1;

        try
        {
            setPictures(app, wantPictures);
            if (connect(app))
            {
                connect = "ok";
                if (EmulatorHarness.command(app.current(), "Saved Messages") != null)
                {
                    Thread.sleep(12000);        // dialogs plus the avatar workers
                    avatars = count("task dialog avatar ok");
                    dialogList = app.current();

                    if (chatTitle != null && chatTitle.length() > 0)
                    {
                        if (openChatNamed(app, chatTitle))
                        {
                            Thread.sleep(20000);   // inline thumbnail decodes
                            chat = EmulatorHarness.command(app.current(), "Older") != null
                                    ? "ok" : "lost";
                            if ("ok".equals(chat)) { photo = openOnePhoto(app); }
                        }
                        else { chat = "fail"; }
                    }
                }
                else { connect = "no-session"; }
            }
        }
        catch (Throwable t)
        {
            System.out.println("stage failed: " + t);
        }

        System.out.println("VERDICT"
                + " ceiling=" + (MemoryBudget.ceiling() / 1024) + "KB"
                + " pictures=" + (wantPictures ? "on" : "off")
                + " connect=" + connect
                + " avatars=" + avatars
                + " chat=" + chat
                + " photo=" + photo
                + " sheds=" + MemoryPressure.shedEvents()
                + " freed=" + (MemoryPressure.shedBytes() / 1024) + "KB"
                + " headroom=" + (MemoryPressure.headroom() / 1024) + "KB"
                + " oom=" + count("OutOfMemory")
                + " ballast=" + (ballast == null ? 0 : ballast.length / 1024) + "KB");
        return "ok".equals(connect);
    }

    /** Activate messages until one opens a photo. Reports what happened. */
    private static String openOnePhoto(EmulatorHarness app) throws Exception
    {
        for (int attempt = 0; attempt < 25; attempt++)
        {
            if (EmulatorHarness.command(app.current(), "Older") == null) { return "lost"; }
            app.key(Canvas.KEY_NUM8);
            Thread.sleep(150);
            app.key(Canvas.KEY_NUM5);
            Thread.sleep(3000);

            if (EmulatorHarness.command(app.current(), "Zoom") != null)
            {
                Thread.sleep(15000);
                boolean stillThere =
                        EmulatorHarness.command(app.current(), "Zoom") != null;
                app.press("Back");
                app.awaitChange(app.current(), 5000);
                return stillThere ? "ok" : "died";
            }
            if (app.current() instanceof Alert)
            {
                System.out.println("  photo refused: "
                                   + EmulatorHarness.describe(app.current()));
                dismissAlert(app);
                return "refused";
            }
            if (EmulatorHarness.command(app.current(), "Older") == null)
            {
                app.press("Back");
                app.awaitChange(app.current(), 5000);
            }
        }
        return "none";
    }

    /** Count diagnostic lines containing a marker. */
    private static int count(String marker)
    {
        String[] lines = Diag.snapshot();
        int n = 0;
        for (int i = 0; i < lines.length; i++)
        {
            if (lines[i] != null && lines[i].indexOf(marker) >= 0) { n++; }
        }
        return n;
    }

    /**
     * Turn dialog-list avatars and inline thumbnails on or off.
     *
     * Both are decoded Images the client holds for as long as the screen lives,
     * and they are the first things worth giving up on a small handset - which
     * is why the settings exist at all. Set explicitly on every run: they
     * persist in RMS, so an unset run inherits whatever the previous one chose.
     */
    private static boolean setPictures(EmulatorHarness app, boolean on)
            throws Exception
    {
        Displayable start = app.current();
        if (!app.press("Settings")) { System.out.println("no Settings"); return false; }
        Displayable settings = app.awaitChange(start, 5000);
        if (!(settings instanceof Form)) { return false; }
        Form form = (Form) settings;

        for (int i = 0; i < form.size(); i++)
        {
            Item item = form.get(i);
            if (!(item instanceof ChoiceGroup)) { continue; }
            ChoiceGroup group = (ChoiceGroup) item;
            String label = group.getLabel();
            if ("Chat avatars".equals(label))
            {
                group.setSelectedIndex(on ? 0 : 1, true);      // Load / Off
            }
            else if ("Media previews".equals(label))
            {
                group.setSelectedIndex(on ? 0 : 1, true);      // thumbnail / text
            }
        }
        System.out.println("pictures: " + (on ? "on" : "off"));
        if (!app.press("Save")) { return false; }
        app.awaitChange(settings, 5000);
        for (int i = 0; i < 4 && app.press("Back"); i++)
        {
            app.awaitChange(app.current(), 2000);
        }
        return true;
    }

    /**
     * Open a picture-heavy conversation and decode as many photos as it offers.
     *
     * This is the scenario the memory work actually has to survive. Each photo
     * is a download plus a full decode whose peak the budget estimates before
     * committing, and every inline thumbnail is another decoded Image held by
     * the chat screen - so it is also the only way to make the pressure ladder
     * fire on anything but a contrived heap.
     */
    private static boolean photos(EmulatorHarness app, String chatTitle)
            throws Exception
    {
        if (chatTitle == null || chatTitle.length() == 0)
        {
            System.out.println("usage: photos <chat title>");
            return false;
        }
        if (!connect(app)) { return false; }
        if (EmulatorHarness.command(app.current(), "Saved Messages") == null)
        {
            System.out.println("no stored session; run the login scenario first");
            return false;
        }
        Thread.sleep(6000);          // let messages.getDialogs finish
        dialogList = app.current();

        if (!openChatNamed(app, chatTitle)) { return false; }
        // Inline thumbnails are decoded on their own worker after the history
        // lands; give them room to actually happen.
        Thread.sleep(20000);
        System.out.println("after thumbnails: " + EmulatorHarness.describe(app.current()));
        report("thumbnails decoded");

        int opened = 0;
        for (int attempt = 0; attempt < 30 && opened < 6; attempt++)
        {
            if (EmulatorHarness.command(app.current(), "Older") == null)
            {
                System.out.println("lost the chat screen: "
                                   + EmulatorHarness.describe(app.current()));
                break;
            }
            app.key(Canvas.KEY_NUM8);          // move focus one message down
            Thread.sleep(150);
            app.key(Canvas.KEY_NUM5);          // activate it
            Thread.sleep(2500);

            if (EmulatorHarness.command(app.current(), "Zoom") != null)
            {
                opened++;
                System.out.println("photo " + opened + " open, waiting for the decode");
                Thread.sleep(12000);
                System.out.println("  " + EmulatorHarness.describe(app.current()));
                report("photo " + opened);
                app.press("Back");
                app.awaitChange(app.current(), 5000);
            }
            else if (app.current() instanceof Alert)
            {
                System.out.println("refused: " + EmulatorHarness.describe(app.current()));
                dismissAlert(app);
            }
            else if (EmulatorHarness.command(app.current(), "Older") == null)
            {
                // A reaction palette or profile screen; step back out.
                app.press("Back");
                app.awaitChange(app.current(), 5000);
            }
        }
        System.out.println("photos opened: " + opened);
        return opened > 0;
    }

    /** Walk the dialog list with the keypad until the wanted chat is selected. */
    private static boolean openChatNamed(EmulatorHarness app, String title)
            throws Exception
    {
        Displayable list = app.current();
        if (!(list instanceof DialogListScreen))
        {
            System.out.println("not the dialog list: " + EmulatorHarness.describe(list));
            return false;
        }
        DialogListScreen screen = (DialogListScreen) list;
        for (int i = 0; i < 250; i++)
        {
            Peer peer = screen.selectedPeer();
            String name = peer == null ? null : peer.title;
            if (name != null && name.indexOf(title) >= 0)
            {
                System.out.println("selected chat: " + name);
                if (!app.press("Open")) { System.out.println("no Open"); return false; }
                Displayable chat = app.awaitChange(list, 60000);
                System.out.println("opened: " + EmulatorHarness.describe(chat));
                return app.awaitCommand("Older", 60000);
            }
            if (!app.key(Canvas.KEY_NUM8)) { break; }
            Thread.sleep(60);
        }
        System.out.println("no chat matching \"" + title + "\" in the list");
        return false;
    }

    /** One line of memory state, for correlating a step with what it cost. */
    private static void report(String what)
    {
        System.out.println("  [" + what + "] headroom="
                + (MemoryPressure.headroom() / 1024) + "KB sheds="
                + MemoryPressure.shedEvents() + " freed="
                + (MemoryPressure.shedBytes() / 1024) + "KB");
    }

    /**
     * Step past a modal alert.
     *
     * Alerts here are Alert.FOREVER and this harness has no UI to dismiss one
     * with, so pressing OK is not enough - the display has to be pointed
     * somewhere else. Only ever used after the alert's text has been reported.
     */
    private static void dismissAlert(EmulatorHarness app) throws Exception
    {
        if (!(app.current() instanceof Alert)) { return; }
        app.press("OK");
        for (int waited = 0; waited < 3000 && app.current() instanceof Alert;
             waited += 100)
        {
            Thread.sleep(100);
        }
        if (app.current() instanceof Alert && dialogList != null)
        {
            app.show(dialogList);
            for (int waited = 0; waited < 3000 && app.current() instanceof Alert;
                 waited += 100)
            {
                Thread.sleep(100);
            }
        }
    }

    /**
     * Walk Back until the dialog list is showing.
     *
     * Bounded and change-sensitive on purpose. The root screen still offers a
     * Back command that does nothing, so "press Back while there is one" spins
     * forever.
     */
    private static boolean returnToDialogList(EmulatorHarness app) throws Exception
    {
        dismissAlert(app);
        for (int i = 0; i < 6; i++)
        {
            if (EmulatorHarness.command(app.current(), "Saved Messages") != null)
            {
                return true;
            }
            Displayable before = app.current();
            if (!app.press("Back")) { break; }
            app.awaitChange(before, 3000);
            if (app.current() == before) { break; }
        }
        boolean back = EmulatorHarness.command(app.current(), "Saved Messages") != null;
        if (!back)
        {
            System.out.println("could not get back to the dialog list: "
                               + EmulatorHarness.describe(app.current()));
        }
        return back;
    }

    /** Open a conversation - the most memory-expensive transition there is. */
    private static boolean openFirstChat(EmulatorHarness app) throws Exception
    {
        Displayable list = app.current();
        dialogList = list;
        System.out.println("chat list: " + EmulatorHarness.describe(list));
        if (!app.press("Open"))
        {
            System.out.println("no Open command; commands: " + app.labels());
            return false;
        }
        Displayable chat = app.awaitChange(list, 60000);
        System.out.println("opened: " + EmulatorHarness.describe(chat));
        if (!app.awaitCommand("Older", 60000))
        {
            System.out.println("chat screen never offered Older: "
                               + EmulatorHarness.describe(app.current()));
            return false;
        }
        Thread.sleep(8000);          // let the history land and wrap
        Displayable after = app.current();
        System.out.println("after history: " + EmulatorHarness.describe(after));
        if (EmulatorHarness.command(after, "Older") == null)
        {
            System.out.println("the chat screen did not survive loading its"
                               + " history - see the crash log below");
            return false;
        }
        return true;
    }

    /**
     * Page history backwards.
     *
     * This is the loop the retention budget actually governs: every page merges
     * into openHistory until maxHistory() truncates it, and each request is
     * preceded by a MemoryPressure.reserve on the worker thread.
     */
    private static boolean pageHistoryBack(EmulatorHarness app) throws Exception
    {
        for (int page = 1; page <= 4; page++)
        {
            if (!app.press("Older"))
            {
                System.out.println("no Older command on "
                                   + EmulatorHarness.describe(app.current()));
                return false;
            }
            Thread.sleep(6000);
            Displayable now = app.current();
            System.out.println("older page " + page + ": "
                               + EmulatorHarness.describe(now));
            // The limit alert is a success, not a failure: it is the budget
            // saying so out loud.
            if (now instanceof Alert) { dismissAlert(app); break; }
        }
        return true;
    }

    /**
     * Send one message to Saved Messages.
     *
     * Deliberately the user own self-chat: the send path has to be exercised
     * against a real account, and nobody else should receive a test message.
     */
    private static boolean sendToSavedMessages(EmulatorHarness app, String text)
            throws Exception
    {
        Displayable list = app.current();
        if (!app.press("Saved Messages"))
        {
            System.out.println("no Saved Messages command; commands: " + app.labels());
            return false;
        }
        Displayable chat = app.awaitChange(list, 60000);
        System.out.println("saved messages: " + EmulatorHarness.describe(chat));
        if (!app.awaitCommand("Write", 30000))
        {
            System.out.println("no Write command: " + EmulatorHarness.describe(app.current()));
            return false;
        }
        Thread.sleep(6000);

        Displayable before = app.current();
        if (!app.press("Write")) { System.out.println("Write did not open"); return false; }
        app.awaitChange(before, 10000);
        if (!app.type(text))
        {
            System.out.println("compose is not a TextBox: "
                               + EmulatorHarness.describe(app.current()));
            return false;
        }
        System.out.println("composed: " + text);
        if (!app.press("Send")) { System.out.println("no Send command"); return false; }

        Thread.sleep(12000);
        System.out.println("after send: " + EmulatorHarness.describe(app.current()));
        return true;
    }

    /** Held for the run, so nothing can collect it. */
    private static byte[] ballast;

    /**
     * Occupy part of the heap before the client starts.
     *
     * This exists because -Xmx cannot express the sizes that matter. On this
     * JVM every value between 1536k and 3584k resolves to one of those two -
     * with or without -Xms, with either collector - so the whole interval where
     * a feature phone lives is unreachable by that lever alone.
     *
     * Holding a block instead is both finer and more faithful: a handset with a
     * 3 MB heap whose AMS is already sitting on a megabyte is exactly this
     * situation, and it is the client's *free* heap that decides whether an
     * avatar or a photo fits, not the number in the spec sheet.
     */
    private static void holdBallast()
    {
        String kb = System.getProperty("tg.driver.ballast");
        if (kb == null) { return; }
        try
        {
            int bytes = Integer.parseInt(kb.trim()) * 1024;
            if (bytes <= 0) { return; }
            ballast = new byte[bytes];
            // Touch every page: a lazily committed block would not actually
            // take the memory away from anyone.
            for (int i = 0; i < bytes; i += 4096) { ballast[i] = 1; }
            ballast[bytes - 1] = 1;
            Runtime rt = Runtime.getRuntime();
            System.out.println("ballast " + (bytes / 1024) + "KB held; free now "
                    + (rt.freeMemory() / 1024) + "KB of " + (rt.totalMemory() / 1024) + "KB");
        }
        catch (Throwable t)
        {
            System.out.println("BALLAST FAILED at " + kb + "KB: " + t);
            ballast = null;
        }
    }

    /**
     * Drop the stored heap measurement so the next start measures again.
     *
     * A profile carries the ceiling of whatever JVM first ran it, and that is
     * correct on a handset - its heap does not change between launches. Under
     * -Xmx it is a lie in the dangerous direction: the client believes it has
     * room it does not have. Clearing the keys before startup is what makes a
     * constrained run measure the heap it is actually given.
     */
    private static void forgetMeasurement()
    {
        try
        {
            RmsAuthKeyStore store = new RmsAuthKeyStore();
            store.saveString("heap.ceiling", null);
            store.saveString("heap.block", null);
            store.saveString("heap.probe.version", null);
            store.saveString("heap.probe.attempts", null);
            System.out.println("stored heap measurement cleared; will re-measure");
        }
        catch (Throwable t)
        {
            System.out.println("could not clear the stored measurement: " + t);
        }
    }

    // --------------------------------------------------------------- helpers

    private static String awaitCode(File file, int timeoutMs) throws Exception
    {
        for (int waited = 0; waited < timeoutMs; waited += 1000)
        {
            if (file.exists() && file.length() > 0)
            {
                FileInputStream in = new FileInputStream(file);
                try
                {
                    byte[] buf = new byte[64];
                    int n = in.read(buf);
                    if (n > 0)
                    {
                        String code = new String(buf, 0, n, "UTF-8").trim();
                        if (code.length() > 0) { return code; }
                    }
                }
                finally { in.close(); }
            }
            Thread.sleep(1000);
        }
        return null;
    }

    private static String arg(String[] args, int index)
    {
        return args.length > index ? args[index] : null;
    }

    private static void dumpMemory()
    {
        System.out.println("---- memory ----");
        String[] budget = MemoryBudget.lines();
        for (int i = 0; i < budget.length; i++) { System.out.println(budget[i]); }
        String[] pressure = MemoryPressure.lines();
        for (int i = 0; i < pressure.length; i++) { System.out.println(pressure[i]); }
    }

    private static void dumpLog()
    {
        // The crash log first: a chat that failed to open records here and then
        // vanishes from the screen, so the ring alone can look like nothing
        // happened.
        String[] crash = CrashLog.load();
        if (crash != null && crash.length > 0)
        {
            System.out.println("---- crash log ----");
            for (int i = 0; i < crash.length; i++) { System.out.println(crash[i]); }
        }
        System.out.println("---- diagnostic ring ----");
        String[] lines = Diag.snapshot();
        for (int i = 0; i < lines.length; i++) { System.out.println(lines[i]); }
    }
}
