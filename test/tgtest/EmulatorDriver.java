package tgtest;

import java.io.File;
import java.io.FileInputStream;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Item;

import tg.api.Dialog;
import tg.api.Peer;
import tg.api.Telegram;
import tg.crypto.Rng;
import tg.diag.CrashLog;
import tg.diag.Diag;
import tg.mem.MemoryBudget;
import tg.mem.MemoryPressure;
import tg.mt.ConnectionConfig;
import tg.mt.Dc;
import tg.plat.RmsAuthKeyStore;
import tg.ui.ChatScreen;
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
 *   photos &lt;title&gt;       open a picture-heavy chat and decode its photos
 *   minheap &lt;title&gt; &lt;on|off&gt;    one verdict line: what works at this heap
 *   scroll &lt;title&gt; [pages]      read a chat backwards, then forwards again
 *   chats  [pages]              scroll the chat list down and back up again
 *   hashprobe [limit]           does messages.getDialogs honour a hash?
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
            else if ("scroll".equals(scenario))
            {
                exit = scroll(app, arg(args, 1), arg(args, 2), arg(args, 3))
                        ? 0 : 1;
            }
            else if ("chats".equals(scenario))
            {
                exit = chats(app, arg(args, 1), arg(args, 2)) ? 0 : 1;
            }
            else if ("hashprobe".equals(scenario))
            {
                exit = hashProbe(arg(args, 1));
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
        System.out.println("  dialogBytes = " + measureDialogBytes(2000)
                + " (measured, " + Dialog.PREVIEW_MAX + "-char preview)");
    }

    /**
     * What one retained chat-list row actually costs.
     *
     * Every other number in {@link MemoryBudget} is a fraction of a measured
     * heap. The dialog cap was not derived that way and could not have been:
     * until the preview was clipped at ingest a Dialog held the whole of the
     * last message, so it had no fixed size and no count of them bounded
     * anything.
     *
     * Weighed here rather than inferred from the running client, because the
     * client's list also drags in decoded avatars, peer-cache entries and the
     * garbage of parsing a TL response - all separately budgeted, all larger
     * than the thing being measured. What is built is one row's worth: the
     * Dialog, the Peer it holds alive, a title and a preview at the cap.
     *
     * Strings are built rather than written as literals, or the constant pool
     * would hand the same one back every time and the answer would be a
     * reference.
     */
    private static long measureDialogBytes(int count)
    {
        Dialog[] held = new Dialog[count];
        long before = usedHeap();
        for (int i = 0; i < count; i++)
        {
            Dialog d = new Dialog();
            d.peer = new Peer(Peer.USER, 100000L + i);
            d.peer.title = fill("name ", 24, i);
            d.peer.accessHash = i;
            d.topMessageId = 1000 + i;
            d.unreadCount = i & 7;
            d.date = 1767225600 - i;
            d.lastMessage = fill("preview ", Dialog.PREVIEW_MAX, i);
            held[i] = d;
        }
        long after = usedHeap();
        // Touched after the second measurement so nothing can be collected
        // early, and so no optimiser can decide the array was never needed.
        int alive = 0;
        for (int i = 0; i < count; i++)
        {
            if (held[i] != null && held[i].peer != null) { alive++; }
        }
        return alive == count ? (after - before) / count : -1;
    }

    /** A distinct String of exactly {@code length} characters. */
    private static String fill(String prefix, int length, int seed)
    {
        StringBuffer sb = new StringBuffer(length);
        sb.append(prefix).append(seed);
        while (sb.length() < length) { sb.append((char) ('a' + (seed + sb.length()) % 26)); }
        sb.setLength(length);
        return sb.toString();
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

    /**
     * Read a conversation backwards and watch what it costs.
     *
     * This is the scenario issue #4 exists for, and the three numbers that
     * matter are all invisible from the screen:
     *
     *   - the laid-out line count, which is what makes the memory bounded. It
     *     has to stop growing, not merely grow slowly;
     *   - the number of {@code messages.getHistory/older} requests, which has to
     *     track pages scrolled rather than keys pressed;
     *   - the number of transcript reflows, which is the same claim from the
     *     other side: a reader crossing an eviction boundary should reflow once.
     *
     * The descent afterwards is not decoration. Oscillating across a boundary is
     * where a naive window turns into a fetch storm, and it is the one failure
     * mode a person clicking Older could never have produced.
     */
    private static boolean scroll(EmulatorHarness app, String chatTitle,
                                  String pagesArg, String singleSocket)
            throws Exception
    {
        if (chatTitle == null || chatTitle.length() == 0)
        {
            System.out.println("usage: scroll <chat title> [pages] [single]");
            return false;
        }
        int pages = 40;
        try { if (pagesArg != null) { pages = Integer.parseInt(pagesArg.trim()); } }
        catch (Throwable ignored) { }

        boolean single = "single".equalsIgnoreCase(singleSocket);
        if (!setSingleSocket(app, single)) { return false; }
        if (!connect(app)) { return false; }
        if (EmulatorHarness.command(app.current(), "Saved Messages") == null)
        {
            System.out.println("no stored session; run the login scenario first");
            return false;
        }
        Thread.sleep(6000);          // let messages.getDialogs finish
        dialogList = app.current();

        if (!openChatNamed(app, chatTitle)) { return false; }
        Thread.sleep(8000);          // the first page, wrapped

        ChatScreen chat = chatScreen(app);
        if (chat == null)
        {
            System.out.println("the chat screen did not survive opening");
            return false;
        }

        int openLines = chat.transcriptLineCount();
        int openLayouts = chat.layoutCount();
        System.out.println("opened: lines=" + openLines
                + " messages=" + chat.messageCount()
                + " window=" + chat.windowScreens() + " screens"
                + " layouts=" + openLayouts);

        int maxLines = openLines;
        int reached = 0;
        for (int i = 0; i < pages; i++)
        {
            ChatScreen live = chatScreen(app);
            if (live == null)
            {
                System.out.println("lost the chat screen on page up " + i);
                break;
            }
            chat = live;
            app.key(Canvas.KEY_NUM4);
            // Long enough for a page to land on a real connection. Too short
            // and this measures the driver rather than the client.
            Thread.sleep(1200);
            int lines = chat.transcriptLineCount();
            if (lines > maxLines) { maxLines = lines; }
            reached = i + 1;
            if ((i + 1) % 5 == 0)
            {
                System.out.println("  up " + (i + 1) + ": lines=" + lines
                        + " messages=" + chat.messageCount()
                        + " older=" + chat.messagesOlderThanViewport()
                        + " layouts=" + chat.layoutCount()
                        + " fetches=" + count("messages.getHistory/older")
                        + " thumbs=" + thumbnailsHeld(chat)
                        + "/" + thumbnailCandidates(chat)
                        + " headroom=" + (MemoryPressure.headroom() / 1024) + "KB");
            }
        }

        int upFetches = count("messages.getHistory/older started");
        int upLayouts = chat.layoutCount();
        System.out.println("turning round after " + reached + " pages up");

        // Down again, further than we came up. The retention window is smaller
        // than the distance covered, so getting back to the present means
        // fetching messages that were already loaded once and then evicted -
        // and that is exactly the path a person takes after reading history.
        int downPages = reached + 20;
        for (int i = 0; i < downPages; i++)
        {
            ChatScreen live = chatScreen(app);
            if (live == null) { break; }
            chat = live;
            app.key(Canvas.KEY_NUM6);
            Thread.sleep(1200);
            int lines = chat.transcriptLineCount();
            if (lines > maxLines) { maxLines = lines; }
            if ((i + 1) % 10 == 0)
            {
                System.out.println("  down " + (i + 1) + ": lines=" + lines
                        + " newer=" + chat.messagesNewerThanViewport()
                        + " atEnd=" + chat.isAtEnd()
                        + " forward=" + count("messages.getHistory/newer started")
                        + " layouts=" + chat.layoutCount());
            }
        }

        boolean alive = chatScreen(app) != null;
        boolean returned = alive && chat.isAtEnd();
        int forwardFetches = count("messages.getHistory/newer started");
        // Clamped because Diag is a bounded ring: on a long run the lines
        // counted at the turn-round can age out of it, and a negative delta
        // reads as a defect when it is only the log forgetting.
        int downFetches = Math.max(0,
                count("messages.getHistory/older started") - upFetches);
        System.out.println("VERDICT"
                + " single=" + single
                + " pages=" + reached
                + " openLines=" + openLines
                + " maxLines=" + maxLines
                + " messages=" + chat.messageCount()
                + " upFetches=" + upFetches
                + " downFetches=" + downFetches
                + " forwardFetches=" + forwardFetches
                + " upLayouts=" + (upLayouts - openLayouts)
                + " downLayouts=" + (chat.layoutCount() - upLayouts)
                + " returnedToEnd=" + returned
                + " thumbs=" + thumbnailsHeld(chat) + "/" + thumbnailCandidates(chat)
                + " thumbOk=" + count("thumbnail ok")
                + " thumbDropped=" + count("thumbnail dropped")
                + " thumbCancelled=" + count("thumbnails cancelled")
                + " busy=" + count("worker busy")
                + " sheds=" + MemoryPressure.shedEvents()
                + " headroom=" + (MemoryPressure.headroom() / 1024) + "KB"
                + " oom=" + count("OutOfMemory")
                + " alive=" + alive);

        // The bound is what is being claimed, so it is what is checked. Two
        // windows of slack: a rebuild can legitimately land on a run of
        // picture messages that wrap taller than the ones it replaced.
        boolean bounded = maxLines <= openLines * 3 + 40;
        if (!bounded)
        {
            System.out.println("FAIL: the laid-out transcript grew from "
                    + openLines + " to " + maxLines);
        }
        if (!alive) { System.out.println("FAIL: the chat screen did not survive"); }
        if (!returned)
        {
            System.out.println("FAIL: scrolling forward did not get back to the"
                    + " newest message - evicted blocks are not coming back");
        }
        return alive && bounded && returned && count("OutOfMemory") == 0;
    }

    /**
     * Scroll the chat list to its end and back, and watch what it costs.
     *
     * This is the scenario issue #6 exists for. Everything it reports is
     * invisible from the screen:
     *
     *   - the reader's absolute position, which has to keep climbing past the
     *     retention cap. Stopping at the cap is the wall this change exists to
     *     remove, and it would look exactly like success from the retained
     *     count alone;
     *   - the retained count, which has to stop growing. It is a window: if it
     *     tracks the position, memory still depends on how far somebody
     *     scrolled and a long chat list is an OutOfMemoryError with extra
     *     steps;
     *   - requests against pages scrolled. One per few pages is the margin
     *     doing its job; one per page is a margin too wide to be a prefetch,
     *     and more than one per page is a fetch storm;
     *   - whether the way back up works. Runs dropped off the top have to come
     *     back, one request each, and the reader has to arrive at row zero -
     *     messages.getDialogs pages downwards only, so this is the half that
     *     could quietly not work.
     *
     * Read-only by construction: it never opens a chat, so nothing is marked
     * read and nothing is sent.
     */
    private static boolean chats(EmulatorHarness app, String pagesArg,
                                 String pictures) throws Exception
    {
        int pages = 40;
        try { if (pagesArg != null) { pages = Integer.parseInt(pagesArg.trim()); } }
        catch (Throwable ignored) { }

        if (!setPictures(app, !"off".equalsIgnoreCase(pictures))) { return false; }
        if (!connect(app)) { return false; }
        if (EmulatorHarness.command(app.current(), "Saved Messages") == null)
        {
            System.out.println("no stored session; run the login scenario first");
            return false;
        }
        Thread.sleep(8000);          // the first page, and its avatars
        dialogList = app.current();

        DialogListScreen list = dialogScreen(app);
        if (list == null)
        {
            System.out.println("not the dialog list: "
                    + EmulatorHarness.describe(app.current()));
            return false;
        }

        int openCount = list.dialogCount();
        int cap = MemoryBudget.maxDialogs();
        long usedAtOpen = usedHeap();
        System.out.println("opened: retained=" + openCount
                + " total=" + list.totalCount()
                + " cap=" + cap
                + " rows=" + list.visibleRows()
                + " used=" + (usedAtOpen / 1024) + "KB");

        int maxRetained = openCount;
        int deepest = 0;
        int anchorSlips = 0;
        int reached = 0;
        for (int i = 0; i < pages; i++)
        {
            DialogListScreen live = dialogScreen(app);
            if (live == null)
            {
                System.out.println("lost the dialog list on page down " + i);
                break;
            }
            list = live;
            app.key(Canvas.KEY_NUM6);            // one screen down
            // Long enough for a page to land on a real connection. Too short
            // and this measures the driver rather than the client.
            String before = peerKey(list.selectedPeer());
            Thread.sleep(1200);
            // Nothing was pressed during that sleep, so a selection that moved
            // is the list reordering under the reader - a message arriving in
            // some chat and promoting it. Anchoring on the peer is what is
            // supposed to make that a no-op for whoever is reading.
            if (!before.equals(peerKey(list.selectedPeer()))) { anchorSlips++; }
            if (list.dialogCount() > maxRetained) { maxRetained = list.dialogCount(); }
            int at = list.windowStart() + list.selectedIndex();
            if (at > deepest) { deepest = at; }
            reached = i + 1;
            if ((i + 1) % 10 == 0)
            {
                System.out.println("  down " + (i + 1)
                        + ": at=" + at + "/" + list.totalCount()
                        + " window=" + list.windowStart()
                        + "+" + list.dialogCount()
                        + " fetches=" + count("messages.getDialogs/more started")
                        + " back=" + count("messages.getDialogs/back started")
                        + " headroom=" + (MemoryPressure.headroom() / 1024) + "KB");
            }
        }

        int downFetches = count("messages.getDialogs/more started");
        long heapDelta = usedHeap() - usedAtOpen;
        System.out.println("turning round at row " + deepest + " after "
                + reached + " pages down");

        // Back up, further than we came down. This is the half that could
        // quietly not work: everything above the window was dropped, and
        // messages.getDialogs cannot ask for it directly.
        int upPages = reached + 20;
        for (int i = 0; i < upPages; i++)
        {
            DialogListScreen live = dialogScreen(app);
            if (live == null) { break; }
            list = live;
            app.key(Canvas.KEY_NUM4);
            Thread.sleep(1200);
            if (list.dialogCount() > maxRetained) { maxRetained = list.dialogCount(); }
            if ((i + 1) % 10 == 0)
            {
                System.out.println("  up " + (i + 1)
                        + ": at=" + (list.windowStart() + list.selectedIndex())
                        + " window=" + list.windowStart()
                        + "+" + list.dialogCount()
                        + " back=" + count("messages.getDialogs/back started"));
            }
        }

        boolean alive = dialogScreen(app) != null;
        int landedAt = alive ? list.windowStart() + list.selectedIndex() : -1;
        boolean returnedToTop = landedAt == 0;
        int backFetches = count("messages.getDialogs/back started");

        System.out.println("VERDICT"
                + " pages=" + reached
                + " openRetained=" + openCount
                + " maxRetained=" + maxRetained
                + " cap=" + cap
                + " total=" + list.totalCount()
                + " deepest=" + deepest
                + " landedAt=" + landedAt
                + " fetches=" + downFetches
                + " backFetches=" + backFetches
                // Raw, and deliberately not divided by anything. Everything
                // the run allocated is in here - avatars, peer-cache entries,
                // the garbage of parsing a TL response, and on an unbounded
                // host JVM whatever the collector had not got round to. All of
                // it is budgeted elsewhere; none of it is what a row costs.
                // dialogBytes on the probe line is the row itself, weighed.
                + " heapDelta=" + (heapDelta / 1024) + "KB"
                + " anchorSlips=" + anchorSlips
                + " returnedToTop=" + returnedToTop
                + " busy=" + count("worker busy")
                + " pageFailed=" + count("dialog page failed")
                + " backFailed=" + count("dialog page back failed")
                + " sheds=" + MemoryPressure.shedEvents()
                + " headroom=" + (MemoryPressure.headroom() / 1024) + "KB"
                + " oom=" + count("OutOfMemory")
                + " alive=" + alive);

        // Each of these is a way of getting it wrong that would still look
        // like it worked from one of the other numbers.
        boolean scrolled = deepest > openCount;
        boolean bounded = maxRetained <= cap;
        boolean pastTheCap = list.totalCount() <= cap || deepest >= cap;
        boolean stormFree = downFetches <= reached;
        boolean cameBack = returnedToTop || deepest < openCount;

        if (!scrolled)
        {
            System.out.println("FAIL: never got past the first page ("
                    + openCount + ") - nothing is being fetched on scroll");
        }
        if (!bounded)
        {
            System.out.println("FAIL: retained " + maxRetained + " rows against"
                    + " a window of " + cap + " - memory still depends on how"
                    + " far somebody scrolled");
        }
        if (!pastTheCap)
        {
            System.out.println("FAIL: stopped at row " + deepest + " with a cap"
                    + " of " + cap + " and " + list.totalCount() + " chats -"
                    + " the wall moved rather than went");
        }
        if (!stormFree)
        {
            System.out.println("FAIL: " + downFetches + " requests for "
                    + reached + " pages - the margin is provoking a fetch"
                    + " per keypress");
        }
        if (!cameBack)
        {
            System.out.println("FAIL: paging up ended at row " + landedAt
                    + " rather than 0 - runs dropped off the top are not"
                    + " coming back");
        }
        if (!alive) { System.out.println("FAIL: the dialog list did not survive"); }
        return alive && scrolled && bounded && pastTheCap && stormFree
                && cameBack && count("OutOfMemory") == 0;
    }

    /**
     * Ask the server whether it honours a {@code messages.getDialogs} hash.
     *
     * Here rather than in the live harness because the only signed-in
     * production session on this machine is the emulator's record store, and
     * {@code RmsAuthKeyStore} needs the MIDlet bridge that
     * {@code EmulatorHarness.install} has already put in place.
     *
     * Deliberately does not start the client. It opens its own connection on
     * the same auth key - which MTProto allows, and which is what keeps the
     * measurement clear of avatar traffic and update polling - and it only ever
     * lists dialogs.
     *
     * @return a process exit code: 0 a vector was found, 3 a clean negative,
     *         2 nothing could be concluded
     */
    private static int hashProbe(String limitArg) throws Exception
    {
        int limit = 30;
        try { if (limitArg != null) { limit = Integer.parseInt(limitArg.trim()); } }
        catch (Throwable ignored) { }

        SeTransport transport = new SeTransport();
        transport.setReadTimeoutMs(60000);
        Telegram tg = new Telegram(transport, new Rng(), new RmsAuthKeyStore());
        // FixedLinkFactory is direct-only; the stored mode is whatever the
        // profile last connected with, which on a build carrying a compiled-in
        // MTProxy is MTProxy. Set it back after the config has been loaded.
        tg.connectionConfig().mode = ConnectionConfig.DIRECT;
        try
        {
            tg.connect();
            System.out.println("connected to dc" + tg.dcId()
                    + " (" + (Dc.isTest() ? "test" : "production") + ")");
            if (tg.checkAuthorization() == null)
            {
                System.out.println("no session in this profile; run the login"
                        + " scenario first");
                return 2;
            }
            int winner = LiveDialogHashTest.probe(tg, transport, limit);
            System.out.println("bytes rx/tx : " + transport.bytesRead()
                    + " / " + transport.bytesWritten());
            if (winner >= 0) { return 0; }
            return winner == LiveDialogHashTest.NONE ? 3 : 2;
        }
        finally
        {
            try { tg.close(); } catch (Throwable ignored) { }
        }
    }

    /** The live dialog list, or null if something else is showing. */
    private static DialogListScreen dialogScreen(EmulatorHarness app)
            throws Exception
    {
        Displayable now = app.current();
        return now instanceof DialogListScreen ? (DialogListScreen) now : null;
    }

    /**
     * Live heap in use, after a collect.
     *
     * The one place in this driver that forces a collection. Bytes per dialog
     * is being measured, and uncollected garbage from avatar decodes is larger
     * than the thing being weighed.
     */
    private static long usedHeap()
    {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++)
        {
            rt.gc();
            try { Thread.sleep(120); } catch (InterruptedException ignored) { }
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    /**
     * A peer as an opaque key.
     *
     * Only ever compared with itself. Deliberately not the title: these lines
     * are printed, and a chat list is somebody's address book.
     */
    private static String peerKey(Peer peer)
    {
        return peer == null ? "-" : (peer.kind + ":" + peer.id);
    }

    /** The live chat screen, or null if something else is showing. */
    private static ChatScreen chatScreen(EmulatorHarness app) throws Exception
    {
        Displayable now = app.current();
        return now instanceof ChatScreen ? (ChatScreen) now : null;
    }

    /**
     * Turn single socket mode on or off before connecting.
     *
     * The mode that matters most and is exercised least: it refuses a second
     * concurrent connection outright, so any path that quietly assumed it could
     * open one fails there and only there. Set explicitly on every run, because
     * it persists in RMS and an unset run inherits the previous one's choice.
     */
    private static boolean setSingleSocket(EmulatorHarness app, boolean on)
            throws Exception
    {
        Displayable start = app.current();
        if (!app.press("Settings")) { System.out.println("no Settings"); return false; }
        Displayable settings = app.awaitChange(start, 5000);
        if (!(settings instanceof Form))
        {
            System.out.println("Settings is not a Form: "
                    + EmulatorHarness.describe(settings));
            return false;
        }
        Form form = (Form) settings;
        boolean found = false;
        for (int i = 0; i < form.size(); i++)
        {
            Item item = form.get(i);
            if (item instanceof ChoiceGroup
                    && "Single socket mode".equals(item.getLabel()))
            {
                ((ChoiceGroup) item).setSelectedIndex(0, on);
                found = true;
            }
        }
        if (!found)
        {
            System.out.println("no Single socket mode choice on Settings");
            return false;
        }
        System.out.println("single socket: " + (on ? "on" : "off"));
        if (!app.press("Save")) { return false; }
        app.awaitChange(settings, 5000);
        for (int i = 0; i < 4 && app.press("Back"); i++)
        {
            app.awaitChange(app.current(), 2000);
        }
        return true;
    }

    /** Inline previews actually decoded and held for what is on screen. */
    private static int thumbnailsHeld(ChatScreen chat)
    {
        if (chat == null) { return 0; }
        tg.api.Message[] visible = chat.visibleMessages();
        int held = 0;
        for (int i = 0; i < visible.length; i++)
        {
            tg.api.Message m = visible[i];
            if (m != null && chat.hasThumbnail(m.id)) { held++; }
        }
        return held;
    }

    /** Messages on screen that carry a stripped thumbnail worth decoding. */
    private static int thumbnailCandidates(ChatScreen chat)
    {
        if (chat == null) { return 0; }
        tg.api.Message[] visible = chat.visibleMessages();
        int candidates = 0;
        for (int i = 0; i < visible.length; i++)
        {
            tg.api.Message m = visible[i];
            if (m != null && m.media != null
                    && m.media.kind == tg.api.Media.PHOTO
                    && m.media.photo != null
                    && m.media.photo.stripped() != null)
            {
                candidates++;
            }
        }
        return candidates;
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
