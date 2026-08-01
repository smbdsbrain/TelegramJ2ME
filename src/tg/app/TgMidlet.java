package tg.app;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;
import java.io.ByteArrayInputStream;

import tg.api.Dialog;
import tg.api.AppSettings;
import tg.api.ForwardInfo;
import tg.api.Message;
import tg.api.OutgoingMessage;
import tg.api.PageMerge;
import tg.api.Peer;
import tg.api.ReadState;
import tg.api.ReactionUpdate;
import tg.api.ReactionCatalog;
import tg.api.ReactionSummary;
import tg.api.ReactionActor;
import tg.api.ReactionActorsPage;
import tg.api.Media;
import tg.api.DownloadToken;
import tg.api.PhotoInputStream;
import tg.api.PhotoRef;
import tg.api.Profile;
import tg.api.Telegram;
import tg.api.UpdateBatch;
import tg.api.UpdateState;
import tg.crypto.Rng;
import tg.diag.CrashLog;
import tg.diag.Diag;
import tg.mem.MemoryBudget;
import tg.mem.MemoryPressure;
import tg.mem.MemoryRelief;
import tg.mt.Dc;
import tg.mt.ConnectionConfig;
import tg.mt.ConnectionDiagnostics;
import tg.mt.RpcError;
import tg.plat.MidpLinkFactory;
import tg.plat.RmsAuthKeyStore;
import tg.plat.RmsAvatarCache;
import tg.plat.RmsConversationCache;
import tg.plat.RmsDraftStore;
import tg.plat.RmsOutgoingStore;
import tg.plat.ReportUpload;
import tg.plat.RmsUpdateStateStore;
import tg.plat.TcpLogSink;
import tg.ui.ChatScreen;
import tg.ui.AvatarCache;
import tg.ui.DialogListScreen;
import tg.ui.EmojiText;
import tg.ui.SettingsScreen;
import tg.ui.TextScreen;
import tg.ui.PhotoScreen;
import tg.ui.ImageScaler;
import tg.ui.JpegDecoder;
import tg.ui.ReactionScreen;
import tg.ui.StrippedJpeg;
import tg.ui.Theme;

/**
 * The Telegram client.
 *
 * Screen flow:
 * <pre>
 *   phone -> code -> dialog list -> conversation -> compose
 * </pre>
 *
 * Every network call goes through {@link Worker}; nothing blocks the lcdui
 * thread. The first launch is the slow one - generating an auth_key costs two
 * 2048-bit modular exponentiations - so the UI says what it is doing rather than
 * appearing to hang.
 */
public class TgMidlet extends MIDlet implements CommandListener, MemoryRelief
{
    /**
     * Room to ask for before opening a conversation.
     *
     * A ChatScreen, the emoji sprite sheet on first paint (49 KB measured),
     * the wrapped transcript and the inflated history response. Not a
     * precise figure and it does not need to be: it is the trigger for a
     * shed, and shedding when it was not strictly necessary costs a redraw.
     */
    private static final int CHAT_OPEN_BYTES = 320 * 1024;

    /*
     * How many dialogs and messages this client fetches and holds now comes
     * from tg.mem.MemoryBudget, which sizes them from the heap the handset
     * actually reported. On four megabytes or more they are the numbers that
     * shipped before the budget existed - 40/30 per request, 200/120 held.
     */

    private final Command cmdExit    = new Command("Exit", Command.EXIT, 10);
    private final Command cmdBack    = new Command("Back", Command.BACK, 2);

    // Command.SCREEN, not Command.OK, and the reason is measured rather than
    // stylistic. MIDP leaves menu placement to the handset, and it only
    // promises to honour priority *within* a type. The Alcatel OT-810D builds
    // its Options menu type by type with SCREEN ahead of OK, which buried
    // "Connect", "Next" and "Sign in" underneath "Log" - the single most
    // important action on each screen was the last line of the menu. Declaring
    // the primary action in the same type as everything else it shares a menu
    // with puts priority back in charge, and priority 1 puts it first.
    private final Command cmdConnect = new Command("Connect", Command.SCREEN, 1);
    private final Command cmdNext    = new Command("Next", Command.SCREEN, 1);
    private final Command cmdSignIn  = new Command("Sign in", Command.SCREEN, 1);
    private final Command cmdCheckPassword =
            new Command("Check password", Command.SCREEN, 1);
    private final Command cmdResendCode =
            new Command("Resend code", Command.SCREEN, 2);
    private final Command cmdChangeNumber =
            new Command("Change number", Command.BACK, 2);
    private final Command cmdOpen    = new Command("Open", Command.SCREEN, 1);
    private final Command cmdRefresh = new Command("Refresh", Command.SCREEN, 3);
    private final Command cmdWrite   = new Command("Write", Command.SCREEN, 1);
    private final Command cmdSend    = new Command("Send", Command.SCREEN, 1);
    // Diagnostics belong at the bottom of every menu they appear in.
    private final Command cmdLog     = new Command("Log", Command.SCREEN, 20);
    private final Command cmdDiag    = new Command("Diagnostics", Command.SCREEN, 19);
    private final Command cmdCrashLog = new Command("Crash log", Command.SCREEN, 21);
    private final Command cmdUpload  = new Command("Upload", Command.SCREEN, 18);
    private final Command cmdClearCrash = new Command("Clear", Command.SCREEN, 3);
    private final Command cmdSettings = new Command("Settings", Command.SCREEN, 8);
    private final Command cmdLogOut  = new Command("Log out", Command.SCREEN, 9);
    private final Command cmdLogOutEverywhere =
            new Command("Log out everywhere", Command.SCREEN, 10);
    private final Command cmdConfirmLogOutEverywhere =
            new Command("Log out everywhere", Command.SCREEN, 1);
    private final Command cmdOutbox  = new Command("Outbox", Command.SCREEN, 6);
    private final Command cmdRetrySend = new Command("Retry", Command.SCREEN, 1);
    private final Command cmdDeleteSend = new Command("Delete", Command.SCREEN, 3);
    private final Command cmdReconnect = new Command("Reconnect now", Command.SCREEN, 2);
    private final Command cmdTestDrop = new Command("Test reconnect", Command.SCREEN, 9);
    private final Command cmdReactions = new Command("Reactions", Command.SCREEN, 1);
    private final Command cmdRetryPhoto = new Command("Retry", Command.SCREEN, 1);
    private final Command cmdZoomPhoto = new Command("Zoom", Command.SCREEN, 1);
    private final Command cmdOlder = new Command("Older", Command.SCREEN, 4);
    private final Command cmdMoreDialogs = new Command("More", Command.SCREEN, 4);
    private final Command cmdFilter = new Command("Filter", Command.SCREEN, 3);
    private final Command cmdApplyFilter = new Command("Apply", Command.SCREEN, 1);
    private final Command cmdClearFilter = new Command("Clear", Command.SCREEN, 2);
    private final Command cmdSaved = new Command("Saved Messages", Command.SCREEN, 2);
    private final Command cmdMyProfile = new Command("My profile", Command.SCREEN, 3);
    private final Command cmdProfile = new Command("Profile", Command.SCREEN, 3);
    private final Command cmdReply = new Command("Reply", Command.SCREEN, 1);
    private final Command cmdForward = new Command("Forward", Command.SCREEN, 2);
    private final Command cmdForwardHere = new Command("Forward here", Command.SCREEN, 1);
    private final Command cmdDeleteMessage = new Command("Delete", Command.SCREEN, 3);
    private final Command cmdDeleteLocal = new Command("Only for me", Command.SCREEN, 1);
    private final Command cmdDeleteRevoke = new Command("For everyone", Command.SCREEN, 2);
    private final Command cmdDeleteChannel =
            new Command("Delete from channel", Command.SCREEN, 1);
    private final Command cmdMarkAllRead = new Command("Mark all read", Command.SCREEN, 4);
    private final Command cmdOpenAvatar = new Command("Open avatar", Command.SCREEN, 1);
    private final Command cmdEditProfile = new Command("Edit profile", Command.SCREEN, 1);
    private final Command cmdSaveProfile = new Command("Save", Command.SCREEN, 1);

    private final Worker worker = new Worker();
    private final Worker avatarWorker = new Worker();
    /*
     * Not final, and not built here. Both size their arrays from
     * MemoryBudget, and instance field initialisers run in the constructor -
     * before startApp() has had a chance to install the measurement. Built at
     * the top of startApp() instead, and rebuilt if a first-launch probe later
     * changes the ceiling.
     */
    private ScreenStack navigation = new ScreenStack();
    private AvatarCache avatarCache = new AvatarCache();

    private Display display;
    private Telegram telegram;
    private RmsAuthKeyStore store;
    private RmsOutgoingStore outgoingStore;
    private RmsDraftStore draftStore;
    private RmsUpdateStateStore updateStateStore;
    private RmsAvatarCache avatarDiskCache;
    private RmsConversationCache conversationCache;
    private ConnectionConfig connectionConfig;
    private ConnectionDiagnostics connectionDiagnostics;
    private SettingsScreen settingsScreen;
    private AppSettings appSettings;
    private TcpLogSink remoteLogSink;

    /**
     * Text entry is done with TextBox, not a TextField inside a Form.
     *
     * A Form field has to be focused and then activated before it accepts
     * input, and how that works depends entirely on the handset - on some it
     * needs the select key, on others a soft key, and in MicroEmulator it
     * simply does not respond. A TextBox is a full-screen editor that is
     * editable the moment it is shown, which is the only behaviour that can be
     * relied on across an unknown 2011 device, an emulator and a QWERTY phone.
     */
    private TextBox phoneBox;
    private TextBox codeBox;
    private TextBox passwordBox;
    private DialogListScreen dialogList;
    private Dialog[] visibleDialogs = new Dialog[0];
    private TextBox filterBox;
    private String dialogFilter = "";
    private ChatScreen chatScreen;
    private TextBox composeBox;
    private List outboxList;
    private ReactionScreen reactionScreen;
    private TextScreen reactionActorsScreen;
    private int reactionMessageId;
    private Peer reactionOptionsPeer;
    private String[] reactionPalette = ReactionCatalog.EMOJI;
    private String[] reactionLabels = ReactionCatalog.LABELS;
    private boolean reactionOptionsLoading;
    private PhotoScreen photoScreen;
    private DownloadToken photoToken;
    private Message photoMessage;
    private boolean photoReferenceExpired;
    private long cachedPhotoId;
    private Image cachedPhoto;
    private int thumbnailGeneration;
    private OutgoingMessage[] outboxItems = new OutgoingMessage[0];

    private String phoneNumber;
    private String phoneCodeHash;

    private Dialog[] dialogs = new Dialog[0];
    private Peer openPeer;
    private Message[] openHistory = new Message[0];

    /**
     * Highest message id ever seen for the open conversation.
     *
     * {@code openHistory[0]} used to be that by construction. It is not any
     * more: reading backwards slides the retained window off the newest end, and
     * marking read against whatever happens to be at the head of the array would
     * report a message the user scrolled past ten minutes ago.
     */
    private int newestKnownId;

    /** An older page is on the wire; a second request would only be dropped. */
    private boolean historyPageInFlight;

    /** The last page came back empty: this is the start of the conversation. */
    private boolean historyExhausted;

    /**
     * A forward fetch returned nothing newer.
     *
     * Separate from clamping {@link #newestKnownId}, which would look like the
     * same thing and would quietly move the read mark backwards. This only
     * stops asking; it clears the moment anything newer actually turns up.
     */
    private boolean historyForwardStalled;
    private Message replyTarget;
    private List forwardList;
    private Peer[] forwardTargets = new Peer[0];
    private Message actionMessage;
    private Peer actionPeer;
    private Form deleteConfirm;
    private List profileScreen;
    private Form editProfileForm;
    private javax.microedition.lcdui.TextField profileFirstName;
    private javax.microedition.lcdui.TextField profileLastName;
    private javax.microedition.lcdui.TextField profileAbout;
    private Profile currentProfile;
    private int profileAvatarIndex = -1;
    private boolean profilePhoto;
    private String connectionLabel = "idle";
    private String updateLabel = "stopped";
    private volatile boolean draftAutosaveRunning;
    private String lastSavedDraft = "";
    private volatile boolean snapshotRefreshScheduled;
    private volatile int avatarGeneration;

    /**
     * Set once this handset has refused a second concurrent socket.
     *
     * Not persisted: it is a property of the device plus the network in front
     * of it, and a different network may behave differently.
     */
    private volatile boolean avatarsUnavailable;

    /**
     * Heap measurement state.
     *
     * {@code heapMeasured} is written by the probe thread and read by the UI
     * thread, hence volatile. {@code heapProbeRunning} is only ever touched on
     * the UI thread - set before the probe starts, cleared in the callSerially
     * that follows it.
     */
    private volatile boolean heapMeasured;
    private boolean heapProbeRunning;

    private final Object readLock = new Object();
    private Peer pendingReadPeer;
    private int pendingReadMaxId;
    private boolean readDrainRunning;

    // -------------------------------------------------------- MIDlet life

    protected void startApp()
    {
        if (display != null)
        {
            if (telegram != null) { telegram.resume(); }
            return;                            // returning from pause
        }
        display = Display.getDisplay(this);

        Diag.info("client " + BuildInfo.VERSION + " build " + BuildInfo.BUILD
                  + " env " + BuildInfo.ENV);
        Diag.mem("startup");

        store = new RmsAuthKeyStore();

        // Before anything sized from it is built. A stored measurement is a
        // cheap RMS read; a first launch gets the reference profile now and a
        // real one from the background probe started after the start screen is
        // up. Either way the caches below are built against the right number.
        heapMeasured = HeapMeasurement.applyStored(store);
        MemoryPressure.setRelief(this);
        navigation = new ScreenStack();
        avatarCache = new AvatarCache();

        outgoingStore = new RmsOutgoingStore();
        draftStore = new RmsDraftStore();
        updateStateStore = new RmsUpdateStateStore();
        avatarDiskCache = new RmsAvatarCache();
        conversationCache = new RmsConversationCache();
        connectionConfig = new ConnectionConfig();
        appSettings = new AppSettings();
        appSettings.load(store);
        configureLogging();
        connectionDiagnostics = new ConnectionDiagnostics();
        telegram = new Telegram(new MidpLinkFactory(), new Rng(), store,
                                connectionConfig, connectionDiagnostics);
        telegram.setOutgoingStore(outgoingStore);
        telegram.setUpdateStateStore(updateStateStore);
        telegram.setConnectionListener(new Telegram.ConnectionListener()
        {
            public void onConnectionState(final int state, final int retrySeconds,
                                          final String detail)
            {
                display.callSerially(new Runnable()
                {
                    public void run()
                    {
                        updateConnectionUi(state, retrySeconds, detail);
                    }
                });
            }
        });
        telegram.setOutgoingListener(new Telegram.OutgoingListener()
        {
            public void onOutboxChanged()
            {
                display.callSerially(new Runnable()
                {
                    public void run() { updateOutboxUi(); }
                });
            }
        });
        telegram.setUpdateListener(new Telegram.UpdateListener()
        {
            public void onUpdates(final UpdateBatch batch)
            {
                display.callSerially(new Runnable()
                {
                    public void run() { applyUpdateBatch(batch); }
                });
            }
        });
        startDraftAutosave();

        // Do not touch the network on startup. An unsigned MIDlet may get only
        // one useful permission prompt, and on restricted handsets the user
        // must be able to configure MTProxy before the first direct attempt.
        // Decided before the screen is drawn, so the first paint can say so.
        heapProbeRunning = !heapMeasured && !HeapMeasurement.exhausted(store);
        showStartScreen();
        startHeapProbe();
    }

    /**
     * Measure the heap once, on first launch, in the quietest moment the client
     * ever has.
     *
     * Startup deliberately touches no network, so between the start screen
     * appearing and the user pressing Connect nothing else is allocating - and
     * the probe fills the heap, so whatever else allocated would be the thing
     * that received the OutOfMemoryError. Connect is gated on this finishing,
     * which is what keeps those two from ever overlapping.
     */
    private void startHeapProbe()
    {
        if (!heapProbeRunning) { return; }

        Thread probe = new Thread(new Runnable()
        {
            public void run()
            {
                final boolean changed = HeapMeasurement.measure(store);
                heapMeasured = true;
                display.callSerially(new Runnable()
                {
                    public void run() { finishHeapProbe(changed); }
                });
            }
        });
        probe.start();
    }

    /**
     * Adopt a fresh measurement on the UI thread.
     *
     * Both caches size their arrays at construction, so a new ceiling means new
     * instances. This is safe only because it runs before Connect: the avatar
     * cache is empty and the stack holds nothing but the start screen. A
     * ChatScreen opened later reads the new budget when it is created, which is
     * on every chat open.
     */
    private void finishHeapProbe(boolean changed)
    {
        heapProbeRunning = false;
        if (!navigation.isRoot())
        {
            // The user walked into Settings while this ran. Their back path is
            // worth more than a right-sized stack, and the stack we already
            // have was built from the default profile, so it is too large
            // rather than too small. Leave it; the next launch gets both.
            return;
        }
        if (changed)
        {
            navigation = new ScreenStack();
            avatarCache = new AvatarCache();
            if (dialogList != null) { dialogList.setAvatarCache(avatarCache); }
        }
        showStartScreen();          // drops the "measuring" line, adds any warning
    }

    protected void pauseApp()
    {
        Diag.info("pauseApp");
        saveDraftNow();
        if (telegram != null) { telegram.pause(); }
    }

    protected void destroyApp(boolean unconditional)
    {
        Diag.info("destroyApp");
        // First: the pressure hook is a static and would otherwise hold this
        // MIDlet, and everything it references, alive past teardown.
        MemoryPressure.setRelief(null);
        saveDraftNow();
        draftAutosaveRunning = false;
        stopRemoteLog();
        if (telegram != null)
        {
            telegram.close();
        }
    }

    // --------------------------------------------------------- navigation

    private void resetRoot(Displayable screen)
    {
        navigation.resetRoot(screen);
        restoreScreen(screen);
    }

    private void pushScreen(Displayable screen)
    {
        navigation.push(screen);
        restoreScreen(screen);
    }

    private void replaceScreen(Displayable screen)
    {
        navigation.replace(screen);
        restoreScreen(screen);
    }

    private void restoreScreen(Displayable screen)
    {
        if (screen == dialogList)
        {
            openPeer = null;
            telegram.setActivePeer(null);
        }
        ChatScreen context = null;
        if (screen instanceof ChatScreen)
        {
            context = (ChatScreen) screen;
        }
        else
        {
            for (int i = navigation.depth() - 1; i >= 0; i--)
            {
                if (navigation.at(i) instanceof ChatScreen)
                {
                    context = (ChatScreen) navigation.at(i);
                    break;
                }
            }
        }
        if (context != null)
        {
            chatScreen = context;
            openPeer = context.peer();
            setOpenHistory(context.messages());
            telegram.setActivePeer(openPeer);
        }
        display.setCurrent(screen);
    }

    public void commandAction(Command c, Displayable d)
    {
        try
        {
            route(c, d);
        }
        catch (Throwable t)
        {
            Diag.error("command failed", t);
            CrashLog.save("ui", t);
            showError("Something went wrong", t);
        }
    }

    private void route(Command c, Displayable d)
    {
        if (c == cmdExit)
        {
            destroyApp(true);
            notifyDestroyed();
        }
        else if (c == cmdConnect)
        {
            if (heapProbeRunning)
            {
                // The probe fills the heap on purpose. Letting a connect start
                // underneath it means the handshake's 2048-bit exponentiation
                // is the thing that gets the OutOfMemoryError.
                showAlert("Still measuring memory. This happens once, on the"
                          + " first launch. Try Connect again in a moment.",
                          AlertType.INFO, display.getCurrent());
                return;
            }
            showBusy("Connecting", "Connecting using "
                     + ConnectionConfig.name(connectionConfig.mode) + "...\n\n"
                     + "The first run generates an encryption key, which takes "
                     + "a while on this hardware.");
            connectAndCheck();
        }
        else if (c == cmdNext)
        {
            requestCode();
        }
        else if (c == cmdSignIn)
        {
            signIn();
        }
        else if (c == cmdCheckPassword)
        {
            checkPassword();
        }
        else if (c == cmdResendCode)
        {
            resendCode();
        }
        else if (c == cmdChangeNumber)
        {
            changeNumber();
        }
        else if (c == cmdOpen)
        {
            openSelectedDialog();
        }
        else if (c == cmdForwardHere
                || (c == List.SELECT_COMMAND && d == forwardList))
        {
            forwardToSelectedDialog();
        }
        else if (c == cmdOpenAvatar
                || (c == List.SELECT_COMMAND && d == profileScreen))
        {
            openCurrentAvatar();
        }
        else if (c == cmdRefresh)
        {
            if (d == chatScreen)
            {
                loadOpenHistory(openPeer);
            }
            else
            {
                loadDialogs();
            }
        }
        else if (c == cmdWrite)
        {
            showCompose();
        }
        else if (c == cmdOlder)
        {
            // Scrolling fetches on its own now; this stays as a manual nudge
            // for a link slow enough that waiting for the margin to be crossed
            // feels like nothing is happening.
            loadOlderPage(true);
        }
        else if (c == cmdMoreDialogs)
        {
            loadMoreDialogs();
        }
        else if (c == cmdFilter)
        {
            showDialogFilter();
        }
        else if (c == cmdApplyFilter)
        {
            applyDialogFilter();
        }
        else if (c == cmdClearFilter)
        {
            dialogFilter = "";
            if (d == filterBox && navigation.current() == filterBox)
            {
                navigation.pop();
            }
            showDialogList();
        }
        else if (c == cmdSaved)
        {
            openSavedMessages();
        }
        else if (c == cmdMyProfile)
        {
            showProfile(telegram.peers().self(), dialogList);
        }
        else if (c == cmdProfile)
        {
            showContextProfile();
        }
        else if (c == cmdReply)
        {
            beginReply();
        }
        else if (c == cmdForward)
        {
            beginForward();
        }
        else if (c == cmdDeleteMessage)
        {
            confirmDeleteMessage();
        }
        else if (c == cmdDeleteLocal)
        {
            performDelete(false);
        }
        else if (c == cmdDeleteRevoke || c == cmdDeleteChannel)
        {
            performDelete(true);
        }
        else if (c == cmdMarkAllRead)
        {
            markAllReadNow();
        }
        else if (c == cmdEditProfile)
        {
            showProfileEditor();
        }
        else if (c == cmdSaveProfile)
        {
            saveProfile();
        }
        else if (c == cmdReactions && d == chatScreen)
        {
            showReactionPalette(chatScreen.focusedMessageId());
        }
        else if (c == cmdRetryPhoto && d == photoScreen)
        {
            if (photoReferenceExpired) { refreshPhotoReferenceAndOpen(); }
            else { openPhoto(photoMessage); }
        }
        else if (c == cmdZoomPhoto && d == photoScreen)
        {
            photoScreen.nextZoom();
        }
        else if (c == cmdSend)
        {
            sendComposed();
        }
        else if (c == cmdOutbox)
        {
            showOutbox();
        }
        else if (c == cmdRetrySend)
        {
            retrySelectedOutgoing();
        }
        else if (c == cmdDeleteSend)
        {
            deleteSelectedOutgoing();
        }
        else if (c == cmdReconnect)
        {
            telegram.reconnectNow();
        }
        else if (c == cmdTestDrop)
        {
            telegram.testDrop();
        }
        else if (c == cmdBack)
        {
            goBack(d);
        }
        else if (c == cmdLog)
        {
            showLog();
        }
        else if (c == cmdDiag)
        {
            showDiagnostics();
        }
        else if (c == cmdCrashLog)
        {
            showCrashLog();
        }
        else if (c == cmdUpload)
        {
            uploadDiagnostics();
        }
        else if (c == cmdClearCrash)
        {
            CrashLog.clear();
            // Refresh in place rather than navigating: the tester has usually
            // just uploaded the entries and wants to see the store is empty
            // before reproducing the fault again.
            if (d instanceof TextScreen)
            {
                ((TextScreen) d).setLines(crashLogLines());
            }
        }
        else if (c == cmdSettings)
        {
            showSettings();
        }
        else if (c == SettingsScreen.CMD_SAVE && d == settingsScreen)
        {
            saveSettings();
        }
        else if (c == cmdLogOut)
        {
            logOut();
        }
        else if (c == cmdLogOutEverywhere)
        {
            confirmLogOutEverywhere();
        }
        else if (c == cmdConfirmLogOutEverywhere)
        {
            logOutEverywhere();
        }
    }

    private void goBack(Displayable from)
    {
        if (from == composeBox)
        {
            saveDraftNow();
            replyTarget = null;
            composeBox.setTitle("Message");
        }
        if (from == photoScreen)
        {
            if (photoToken != null) { photoToken.cancel(); }
        }
        if (navigation.isRoot())
        {
            destroyApp(true);
            notifyDestroyed();
            return;
        }
        restoreScreen(navigation.pop());
    }

    // -------------------------------------------------------------- login

    private void showStartScreen()
    {
        Form form = new Form("Telegram J2ME");
        form.append("No network connection has been opened yet.\n\n");
        form.append("State: " + connectionLabel + "\n");
        form.append("Mode: " + ConnectionConfig.name(connectionConfig.mode) + "\n");
        if (connectionConfig.hasProxy())
        {
            form.append("MTProxy: " + connectionConfig.proxyHost + ":"
                        + connectionConfig.proxyPort + "\n");
        }
        else
        {
            form.append("MTProxy: not configured\n");
        }
        form.append("DC: " + Dc.describe());
        if (heapProbeRunning)
        {
            form.append("\n\nMeasuring available memory...");
        }
        else if (!MemoryBudget.viable())
        {
            // A warning, never a refusal. A handset that under-reports its heap
            // would otherwise be permanently locked out of an app that might
            // have run on it perfectly well.
            form.append("\n\nThis handset reports only "
                    + (MemoryBudget.ceiling() / 1024) + " KB of usable memory."
                    + " Everything is at its smallest setting and signing in"
                    + " may not be possible.");
        }
        if (DevSink.CONFIGURED)
        {
            // On the first screen, not buried in Settings. A build that can
            // send diagnostics somewhere should say so before it is used, not
            // only to whoever goes looking.
            form.append("\nDiagnostics: can upload to " + DevSink.TCP_HOST
                    + " on request. Never automatically.");
        }
        form.addCommand(cmdConnect);
        form.addCommand(cmdSettings);
        form.addCommand(cmdDiag);
        // Reachable before sign-in on purpose: a MIDlet that dies on the way to
        // the dialog list leaves its evidence here, and the dialog list is
        // then exactly the screen the tester cannot get to.
        form.addCommand(cmdCrashLog);
        form.addCommand(cmdLog);
        form.addCommand(cmdExit);
        form.setCommandListener(this);
        resetRoot(form);
    }

    private void connectAndCheck()
    {
        worker.submit(new Worker.Task()
        {
            public String name() { return "connect"; }

            public Object run() throws Exception
            {
                telegram.connect();
                return telegram.checkAuthorization();
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (result != null)
                {
                    loadDialogs();
                }
                else
                {
                    showPhoneBox();
                }
            }

            public void onFailure(Throwable error)
            {
                if (!showCachedDialogsOffline())
                {
                    showRetryableError("Could not connect", error);
                }
                else
                {
                    Diag.warn("startup using cached dialogs: "
                            + shortMessage(error));
                }
            }
        });
    }

    private void showPhoneBox()
    {
        if (phoneBox == null)
        {
            phoneBox = new TextBox("Phone number, e.g. +1234567890",
                                   "+", 20, TextField.PHONENUMBER);
            phoneBox.addCommand(cmdNext);
            phoneBox.addCommand(cmdLog);
            phoneBox.addCommand(cmdExit);
            phoneBox.setCommandListener(this);
        }
        resetRoot(phoneBox);
    }

    private void requestCode()
    {
        phoneNumber = phoneBox.getString().trim();
        if (phoneNumber.length() < 5)
        {
            showAlert("Enter a phone number in international format, e.g. +1234567890.",
                      AlertType.WARNING, phoneBox);
            return;
        }
        showBusy("Sign in", "Requesting a code for " + phoneNumber + "...");

        worker.submit(new Worker.Task()
        {
            public String name() { return "auth.sendCode"; }

            public Object run() throws Exception
            {
                return telegram.sendCode(phoneNumber);
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                phoneCodeHash = (String) result;
                showCodeBox();
            }

            public void onFailure(Throwable error)
            {
                showRetryableError("Could not request a code", error);
            }
        });
    }

    private void showCodeBox()
    {
        int digits = telegram.lastSentCodeLength();
        String title = "Code via " + telegram.lastSentCodeTypeName()
                       + (digits > 0 ? (" (" + digits + " digits)") : "");
        if (codeBox == null)
        {
            codeBox = new TextBox(title, "", 8, TextField.NUMERIC);
            codeBox.addCommand(cmdSignIn);
            codeBox.addCommand(cmdResendCode);
            codeBox.addCommand(cmdChangeNumber);
            codeBox.addCommand(cmdLog);
            codeBox.setCommandListener(this);
        }
        else
        {
            codeBox.setString("");
        }
        // TextBox titles are settable, so the delivery method can be shown
        // without a Form wrapper.
        codeBox.setTitle(title);
        pushScreen(codeBox);
    }

    private void signIn()
    {
        final String code = codeBox.getString().trim();
        if (code.length() == 0)
        {
            showAlert("Enter the code first.", AlertType.WARNING, codeBox);
            return;
        }
        showBusy("Sign in", "Signing in...");

        worker.submit(new Worker.Task()
        {
            public String name() { return "auth.signIn"; }

            public Object run() throws Exception
            {
                return telegram.signIn(phoneNumber, phoneCodeHash, code);
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                Peer me = (Peer) result;
                Diag.info("signed in as " + (me == null ? "?" : me.title));
                loadDialogs();
            }

            public void onFailure(Throwable error)
            {
                if (error instanceof RpcError && ((RpcError) error).isPasswordNeeded())
                {
                    requestPasswordHint();
                    return;
                }
                showAlertThen("Sign-in failed", error, codeBox);
            }
        });
    }

    private void resendCode()
    {
        showBusy("Sign in", "Requesting another code...");
        worker.submit(new Worker.Task()
        {
            public String name() { return "auth.resendCode"; }

            public Object run() throws Exception
            {
                return telegram.resendCode(phoneNumber, phoneCodeHash);
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                phoneCodeHash = (String) result;
                showCodeBox();
            }

            public void onFailure(Throwable error)
            {
                showAlertThen("Could not resend code", error, codeBox);
            }
        });
    }

    private void changeNumber()
    {
        final String oldPhone = phoneNumber;
        final String oldHash = phoneCodeHash;
        phoneCodeHash = null;
        passwordBox = null;
        if (oldPhone == null || oldHash == null)
        {
            showPhoneBox();
            return;
        }
        showBusy("Sign in", "Cancelling the code for " + oldPhone + "...");
        worker.submit(new Worker.Task()
        {
            public String name() { return "auth.cancelCode"; }

            public Object run() throws Exception
            {
                telegram.cancelCode(oldPhone, oldHash);
                return null;
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result) { showPhoneBox(); }

            public void onFailure(Throwable error)
            {
                // Changing the local input must remain possible even if the
                // old flow already expired or the network disappeared.
                Diag.warn("could not cancel old login code: " + shortMessage(error));
                showPhoneBox();
            }
        });
    }

    private void requestPasswordHint()
    {
        showBusy("Two-step verification", "Loading password parameters...");
        worker.submit(new Worker.Task()
        {
            public String name() { return "account.getPassword"; }

            public Object run() throws Exception
            {
                return telegram.passwordHint();
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                showPasswordBox((String) result);
            }

            public void onFailure(Throwable error)
            {
                showAlertThen("Could not load 2FA", error, codeBox);
            }
        });
    }

    private void showPasswordBox(String hint)
    {
        String title = "2FA password";
        if (hint != null && hint.length() > 0) { title += " (hint: " + hint + ")"; }
        passwordBox = new TextBox(title, "", 128,
                                  TextField.ANY | TextField.PASSWORD);
        passwordBox.addCommand(cmdCheckPassword);
        passwordBox.addCommand(cmdChangeNumber);
        passwordBox.addCommand(cmdLog);
        passwordBox.setCommandListener(this);
        pushScreen(passwordBox);
    }

    private void checkPassword()
    {
        final String password = passwordBox.getString();
        if (password.length() == 0)
        {
            showAlert("Enter the password first.", AlertType.WARNING, passwordBox);
            return;
        }
        passwordBox.setString("");
        showBusy("Two-step verification",
                 "Checking the password locally.\n\n"
                 + "This may take several minutes on older hardware. "
                 + "Please keep the app open.");
        worker.submit(new Worker.Task()
        {
            public String name() { return "auth.checkPassword"; }

            public Object run() throws Exception
            {
                return telegram.checkPassword(password, null);
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                Peer me = (Peer) result;
                Diag.info("2FA signed in as " + (me == null ? "?" : me.title));
                loadDialogs();
            }

            public void onFailure(Throwable error)
            {
                showAlertThen("2FA sign-in failed", error, passwordBox);
            }
        });
    }

    private void logOut()
    {
        showBusy("Log out", "Logging out...");
        worker.submit(new Worker.Task()
        {
            public String name() { return "auth.logOut"; }

            public Object run() throws Exception
            {
                telegram.logOut();
                return null;
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                finishLoggedOut();
            }

            public void onFailure(Throwable error)
            {
                if (!telegram.isAuthorized())
                {
                    // Telegram.logOut always clears the local key in finally:
                    // a lost reply must not leave the UI showing stale chats.
                    finishLoggedOut();
                    showAlert("The local session was cleared, but Telegram "
                              + "did not confirm the server logout:\n"
                              + shortMessage(error), AlertType.WARNING, phoneBox);
                }
                else
                {
                    showRetryableError("Log out failed", error);
                }
            }
        });
    }

    private void confirmLogOutEverywhere()
    {
        Form confirm = new Form("Log out everywhere?");
        confirm.append("This ends every Telegram session on every device, "
                     + "including this emulator. Other devices will need to "
                     + "sign in again.");
        confirm.addCommand(cmdConfirmLogOutEverywhere);
        confirm.addCommand(cmdBack);
        confirm.setCommandListener(this);
        pushScreen(confirm);
    }

    private void logOutEverywhere()
    {
        showBusy("Log out everywhere", "Ending all Telegram sessions...");
        worker.submit(new Worker.Task()
        {
            public String name() { return "auth.resetAuthorizations"; }

            public Object run() throws Exception
            {
                telegram.logOutEverywhere();
                return null;
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                finishLoggedOut();
            }

            public void onFailure(Throwable error)
            {
                if (!telegram.isAuthorized())
                {
                    // resetAuthorizations succeeded and the following current
                    // logout cleared local state, but its reply was lost.
                    finishLoggedOut();
                    showAlert("Other sessions were ended and the local session "
                              + "was cleared, but Telegram did not confirm the "
                              + "last logout:\n" + shortMessage(error),
                              AlertType.WARNING, phoneBox);
                }
                else
                {
                    showRetryableError("Log out everywhere failed", error);
                }
            }
        });
    }

    private void finishLoggedOut()
    {
        try { draftStore.clear(); }
        catch (Throwable t) { Diag.error("draft clear failed", t); }
        dialogs = new Dialog[0];
        dialogList = null;
        avatarGeneration++;
        avatarCache.clear();
        try { avatarDiskCache.clear(); }
        catch (Throwable t) { Diag.error("avatar cache clear failed", t); }
        try { conversationCache.clear(); }
        catch (Throwable t) { Diag.error("conversation cache clear failed", t); }
        openPeer = null;
        phoneCodeHash = null;
        passwordBox = null;
        showPhoneBox();
    }

    // ------------------------------------------------------------ dialogs

    private void loadDialogs()
    {
        final Peer selectedPeer = selectedDialogPeer();
        avatarCache.clearFailures();
        boolean fallback = dialogs.length > 0;
        if (!fallback)
        {
            try
            {
                long accountId = cacheAccountId();
                Dialog[] cached = accountId == 0 ? null
                        : conversationCache.loadDialogs(
                                accountId, Dc.isTest());
                if (cached != null && cached.length > 0)
                {
                    dialogs = cached;
                    showDialogList(selectedPeer);
                    dialogList.setStatus("cached/loading", updateLabel);
                    fallback = true;
                }
            }
            catch (Throwable t)
            {
                Diag.warn("dialog cache load failed: " + shortMessage(t));
            }
        }
        if (!fallback) { showBusy("Chats", "Loading chats..."); }
        else if (dialogList != null)
        {
            dialogList.setStatus("refreshing", updateLabel);
        }
        final boolean hasFallback = fallback;
        worker.submit(new Worker.Task()
        {
            public String name() { return "messages.getDialogs"; }

            public Object run() throws Exception
            {
                return telegram.getDialogs(MemoryBudget.dialogPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                dialogs = (Dialog[]) result;
                cacheDialogs(dialogs);
                showDialogList(selectedPeer);
            }

            public void onFailure(Throwable error)
            {
                if (hasFallback && dialogs.length > 0)
                {
                    showDialogList(selectedPeer);
                    dialogList.setStatus("cached/offline", updateLabel);
                    Diag.warn("using cached dialogs: " + shortMessage(error));
                }
                else { showRetryableError("Could not load chats", error); }
            }
        });
    }

    private void showDialogList()
    {
        showDialogList(null);
    }

    private void showDialogList(Peer selectedPeer)
    {
        visibleDialogs = filterDialogs(dialogs, dialogFilter);
        if (dialogList == null)
        {
            dialogList = new DialogListScreen(currentTheme());
            dialogList.addCommand(cmdOpen);
            dialogList.addCommand(cmdRefresh);
            dialogList.addCommand(cmdMoreDialogs);
            dialogList.addCommand(cmdFilter);
            dialogList.addCommand(cmdSaved);
            dialogList.addCommand(cmdMyProfile);
            dialogList.addCommand(cmdDiag);
            dialogList.addCommand(cmdCrashLog);
            dialogList.addCommand(cmdSettings);
            dialogList.addCommand(cmdOutbox);
            dialogList.addCommand(cmdReconnect);
            dialogList.addCommand(cmdLog);
            dialogList.addCommand(cmdLogOut);
            dialogList.addCommand(cmdLogOutEverywhere);
            dialogList.addCommand(cmdBack);
            dialogList.addCommand(cmdExit);
            dialogList.setCommandListener(this);
            dialogList.setActivationListener(
                    new DialogListScreen.ActivationListener()
            {
                public void onDialogActivated(Peer peer) { openDialog(peer); }
            });
            dialogList.setAvatarCache(avatarCache);
            dialogList.setViewportListener(
                    new DialogListScreen.ViewportListener()
            {
                public void onDialogViewportChanged()
                {
                    loadVisibleAvatars();
                }
            });
        }
        dialogList.removeCommand(cmdClearFilter);
        if (dialogFilter.length() > 0) { dialogList.addCommand(cmdClearFilter); }
        dialogList.setTheme(currentTheme());
        dialogList.setStatus(connectionLabel, updateLabel);
        dialogList.setEmptyText(dialogFilter.length() == 0
                ? "(no chats)" : "(no matches)");
        dialogList.setDialogs(visibleDialogs, dialogs.length, selectedPeer);
        if (navigation.root() != dialogList) { resetRoot(dialogList); }
        else { restoreScreen(dialogList); }
        loadVisibleAvatars();
    }

    /** Load only avatars that can currently become visible. */
    private void loadVisibleAvatars()
    {
        if (dialogList == null || avatarWorker.isBusy()
                || navigation.current() != dialogList)
        {
            return;
        }
        if (!appSettings.loadAvatars || avatarsUnavailable)
        {
            return;
        }
        Peer[] candidates = dialogList.visiblePeers();
        for (int i = 0; i < candidates.length; i++)
        {
            final Peer peer = candidates[i];
            if (peer == null || peer.avatar == null) { continue; }
            if (peer.kind != Peer.CHAT && peer.accessHash == 0)
            {
                avatarCache.fail(peer);
                continue;
            }
            if (!avatarCache.markLoading(peer)) { continue; }
            final int generation = avatarGeneration;
            final long photoId = peer.avatar.photoId;
            final int target = Math.max(8, dialogList.avatarSize());
            boolean submitted = avatarWorker.submit(new Worker.Task()
            {
                public String name() { return "dialog avatar"; }

                public Object run() throws Exception
                {
                    long accountId = cacheAccountId();
                    byte[] bytes = accountId == 0 ? null
                            : avatarDiskCache.load(accountId, Dc.isTest(), peer);
                    boolean downloaded = false;
                    if (bytes == null)
                    {
                        PhotoInputStream in = null;
                        try
                        {
                            in = telegram.openAvatar(peer, new DownloadToken());
                            bytes = JpegDecoder.read(in, null);
                            downloaded = true;
                        }
                        finally
                        {
                            if (in != null) { try { in.close(); } catch (Throwable ignored) { } }
                        }
                    }
                    Image decoded = JpegDecoder.decode(bytes, null);
                    Image scaled = ImageScaler.fitBox(decoded, target, target);
                    if (downloaded && accountId != 0)
                    {
                        avatarDiskCache.save(accountId, Dc.isTest(), peer, bytes);
                    }
                    return new AvatarLoad(peer, photoId, scaled);
                }
            }, new Worker.Callback()
            {
                public void onSuccess(final Object result)
                {
                    display.callSerially(new Runnable()
                    {
                        public void run()
                        {
                            AvatarLoad loaded = (AvatarLoad) result;
                            if (generation == avatarGeneration
                                    && loaded.peer.avatar != null
                                    && loaded.peer.avatar.photoId
                                            == loaded.photoId)
                            {
                                avatarCache.put(loaded.peer, loaded.image);
                                if (dialogList != null)
                                {
                                    dialogList.avatarsChanged();
                                }
                                loadVisibleAvatars();
                            }
                            else
                            {
                                try { avatarDiskCache.clear(); }
                                catch (Throwable ignored) { }
                            }
                        }
                    });
                }

                public void onFailure(final Throwable error)
                {
                    // An avatar needs a SECOND connection to the media DC, and
                    // some handsets cannot hold two sockets at once: the open
                    // fails, and on the GT-C3592 the attempt also desynchronises
                    // the connection already in use, so the messages.getHistory
                    // running alongside it dies with "invalid FakeTLS
                    // application record" and the chat renders empty.
                    //
                    // One decorative thumbnail is not worth breaking the client
                    // for, so a socket failure retires avatar loading for the
                    // rest of the session rather than repeating it per peer.
                    if (isSocketUnavailable(error)) { avatarsUnavailable = true; }

                    display.callSerially(new Runnable()
                    {
                        public void run()
                        {
                            if (generation == avatarGeneration)
                            {
                                avatarCache.fail(peer);
                                if (dialogList != null)
                                {
                                    dialogList.avatarsChanged();
                                }
                                Diag.warn("avatar " + peer.key() + ": "
                                        + shortMessage(error));
                                if (avatarsUnavailable)
                                {
                                    Diag.warn("avatars disabled for this session:"
                                            + " this handset refused a second"
                                            + " connection");
                                }
                                loadVisibleAvatars();
                            }
                        }
                    });
                }
            });
            if (!submitted) { avatarCache.fail(peer); }
            return;
        }
    }

    /**
     * Does this failure mean the platform would not give us another socket?
     *
     * Matched on the exception class rather than the message: MIDP reports it
     * as ConnectionNotFoundException, and the text varies by vendor.
     */
    private static boolean isSocketUnavailable(Throwable error)
    {
        if (error == null) { return false; }
        String name = Diag.className(error);
        return "ConnectionNotFoundException".equals(name)
                || "SecurityException".equals(name);
    }

    private long cacheAccountId()
    {
        Peer self = telegram == null ? null : telegram.peers().self();
        if (self != null) { return self.id; }
        if (store == null) { return 0; }
        try
        {
            String saved = store.loadString("cache.account."
                    + (Dc.isTest() ? "test" : "prod"));
            return saved == null ? 0 : Long.parseLong(saved);
        }
        catch (Throwable ignored) { return 0; }
    }

    private void cacheDialogs(Dialog[] value)
    {
        long accountId = cacheAccountId();
        if (accountId == 0 || conversationCache == null) { return; }
        if (store != null)
        {
            store.saveString("cache.account."
                    + (Dc.isTest() ? "test" : "prod"),
                    String.valueOf(accountId));
        }
        try { conversationCache.saveDialogs(accountId, Dc.isTest(), value); }
        catch (Throwable t)
        {
            Diag.warn("dialog cache save failed: " + shortMessage(t));
        }
    }

    private boolean showCachedDialogsOffline()
    {
        long accountId = cacheAccountId();
        if (accountId == 0 || conversationCache == null) { return false; }
        try
        {
            Dialog[] cached = conversationCache.loadDialogs(
                    accountId, Dc.isTest());
            if (cached == null || cached.length == 0) { return false; }
            dialogs = cached;
            showDialogList();
            dialogList.setStatus("cached/offline", "stopped");
            return true;
        }
        catch (Throwable t)
        {
            Diag.warn("offline dialog cache failed: " + shortMessage(t));
            return false;
        }
    }

    private void cacheHistory(Peer peer, Message[] value)
    {
        long accountId = cacheAccountId();
        if (accountId == 0 || conversationCache == null) { return; }
        try
        {
            conversationCache.saveHistory(
                    accountId, Dc.isTest(), peer, value);
        }
        catch (Throwable t)
        {
            Diag.warn("history cache save failed: " + shortMessage(t));
        }
    }

    /**
     * Capture the logical selection before dialogs are reordered or replaced.
     * Reusing the numeric index would select a different chat after promotion.
     */
    private Peer selectedDialogPeer()
    {
        return dialogList == null ? null : dialogList.selectedPeer();
    }

    private static Dialog[] filterDialogs(Dialog[] source, String filter)
    {
        return PageMerge.filter(source, filter);
    }

    private int findVisibleDialog(Peer peer)
    {
        for (int i = 0; i < visibleDialogs.length; i++)
        {
            if (visibleDialogs[i] != null
                    && samePeer(visibleDialogs[i].peer, peer)) { return i; }
        }
        return -1;
    }

    private void showDialogFilter()
    {
        filterBox = new TextBox("Filter chats", dialogFilter, 64,
                TextField.ANY);
        filterBox.addCommand(cmdApplyFilter);
        filterBox.addCommand(cmdClearFilter);
        filterBox.addCommand(cmdBack);
        filterBox.setCommandListener(this);
        pushScreen(filterBox);
    }

    private void applyDialogFilter()
    {
        dialogFilter = filterBox.getString().trim();
        if (navigation.current() == filterBox) { navigation.pop(); }
        showDialogList();
    }

    private void loadMoreDialogs()
    {
        if (dialogs.length >= MemoryBudget.maxDialogs())
        {
            showAlert("The in-memory dialog limit (" + MemoryBudget.maxDialogs()
                    + ") has been reached.", AlertType.INFO, dialogList);
            return;
        }
        Dialog offset = null;
        for (int i = dialogs.length - 1; i >= 0; i--)
        {
            if (dialogs[i] != null && !dialogs[i].pinned)
            {
                offset = dialogs[i];
                break;
            }
        }
        final Dialog pageOffset = offset;
        final Peer selected = selectedDialogPeer();
        dialogList.setStatus("loading...", updateLabel);
        worker.submit(new Worker.Task()
        {
            public String name() { return "messages.getDialogs/more"; }
            public Object run() throws Exception
            {
                return telegram.getDialogsAfter(pageOffset, MemoryBudget.dialogPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                Dialog[] page = (Dialog[]) result;
                int before = dialogs.length;
                dialogs = mergeDialogs(dialogs, page, MemoryBudget.maxDialogs());
                cacheDialogs(dialogs);
                showDialogList(selected);
                if (dialogs.length == before)
                {
                    showAlert("No more chats.", AlertType.INFO, dialogList);
                }
            }
            public void onFailure(Throwable error)
            {
                showAlertThen("Could not load more chats", error, dialogList);
            }
        });
    }

    private static Dialog[] mergeDialogs(Dialog[] first, Dialog[] second,
                                         int limit)
    {
        return PageMerge.dialogs(first, second, limit);
    }

    private void openSavedMessages()
    {
        Peer self = telegram.peers().self();
        if (self == null)
        {
            showAlert("Your account profile is not loaded yet.",
                    AlertType.WARNING, dialogList);
            return;
        }
        Peer saved = new Peer(Peer.USER, self.id);
        saved.accessHash = self.accessHash;
        saved.self = true;
        saved.title = "Saved Messages";
        saved.firstName = self.firstName;
        saved.lastName = self.lastName;
        saved.username = self.username;
        openDialog(saved);
    }

    private void openSelectedDialog()
    {
        Peer peer = selectedDialogPeer();
        if (peer != null) { openDialog(peer); }
    }

    /**
     * Open a conversation.
     *
     * Wrapped in a Throwable guard because this is the single most
     * memory-expensive transition in the client and the one a low-heap handset
     * was observed to die on. Between the first paint of a ChatScreen and the
     * history arriving, the VM has to find room for the emoji sprite sheet
     * (decoded, not the 16 KB on disk), a full word-wrap of the transcript, an
     * inflated TL response and the object graph parsed out of it.
     *
     * catch (Throwable) rather than catch (Exception) on purpose:
     * OutOfMemoryError is an Error, and it is the one this is here for.
     * Recovery is not guaranteed - the allocation that failed may be needed to
     * report the failure - but CrashLog.save is written to survive that, and an
     * entry with heapTotal/heapFree in it is the difference between "the phone
     * showed a system error" and a diagnosis.
     */
    private void openDialog(Peer peer)
    {
        try
        {
            // Reclaim before committing, not after failing. The estimate is the
            // shape of what follows: a ChatScreen, the emoji sheet on first
            // paint, a wrapped transcript and the inflated history response.
            MemoryPressure.reserve(CHAT_OPEN_BYTES);
            openPeer = peer;
            newestKnownId = 0;
            historyPageInFlight = false;
            historyExhausted = false;
            historyForwardStalled = false;
            telegram.setActivePeer(peer);
            chatScreen = createChatScreen(peer);
            chatScreen.setTitle(peer == null ? "Chat" : peer.title);
            thumbnailGeneration++;
            chatScreen.resetMessages(new Message[0]);
            chatScreen.setStatus("loading... / " + connectionLabel);
            pushScreen(chatScreen);
            loadOpenHistory(peer);
        }
        catch (Throwable t)
        {
            openChatFailed(t);
        }
    }

    /**
     * Report a failed chat open without taking the MIDlet down with it.
     *
     * Drops the half-built screen first: on an OutOfMemoryError the references
     * this method is about to abandon may be the only thing standing between
     * the collector and a second failure while writing the crash entry.
     */
    private void openChatFailed(Throwable t)
    {
        chatScreen = null;
        openHistory = new Message[0];
        cachedPhoto = null;
        cachedPhotoId = 0;

        Diag.error("chat open failed", t);
        CrashLog.save("chat-open", t);

        Runtime rt = Runtime.getRuntime();
        Alert alert = new Alert("Cannot open chat",
                shortMessage(t) + "\n\nheapFree=" + rt.freeMemory()
                        + " of " + rt.totalMemory()
                        + "\n\nRecorded in the crash log.",
                null, AlertType.ERROR);
        alert.setTimeout(Alert.FOREVER);
        display.setCurrent(alert, dialogList == null ? display.getCurrent() : dialogList);
    }

    // ------------------------------------------------------- memory pressure

    public int levels() { return 4; }

    /**
     * Give memory back, cheapest and largest first.
     *
     * Ordered by what each is actually worth, which is not the order intuition
     * suggests. The emoji sheet feels expensive and measures 49 KB; one decoded
     * full-screen photo is six times that and is pure cache.
     *
     * Called from a worker thread, so nothing here may touch state a live
     * screen has already laid out - trimming openHistory would leave a painted
     * ChatScreen indexing messages that no longer exist. That belongs in
     * {@link #openChatFailed}, which runs on the UI thread after the fact.
     */
    public void release(int level)
    {
        switch (level)
        {
            case 1:
                // Largest single sheddable object in the client, and the one
                // the user is least likely to be looking at right now.
                cachedPhoto = null;
                cachedPhotoId = 0;
                break;
            case 2:
                ChatScreen open = chatScreen;
                if (open != null) { open.clearThumbnails(); }
                break;
            case 3:
                // clear() drops the failure markers too, so the next dialog
                // list retries the avatars it had given up on.
                avatarCache.clear();
                break;
            case 4:
                // Last, and worth saying why: 49 KB on the one handset where it
                // has been measured. It reloads in about 50 ms on the next
                // paint, so it is cheap to drop and cheap to regret.
                EmojiText.release();
                break;
            default:
                break;
        }
    }

    private ChatScreen createChatScreen(Peer peer)
    {
        ChatScreen screen = new ChatScreen(currentTheme());
        screen.setPeer(peer);
        screen.setMediaPreviews(appSettings.mediaPreviews);
        screen.addCommand(cmdWrite);
        screen.addCommand(cmdBack);
        screen.addCommand(cmdRefresh);
        screen.addCommand(cmdOutbox);
        screen.addCommand(cmdReconnect);
        screen.addCommand(cmdLog);
        screen.addCommand(cmdReactions);
        screen.addCommand(cmdReply);
        screen.addCommand(cmdForward);
        screen.addCommand(cmdDeleteMessage);
        screen.addCommand(cmdProfile);
        screen.addCommand(cmdOlder);
        screen.addCommand(cmdMarkAllRead);
        screen.setCommandListener(this);
        screen.setActivationListener(new ChatScreen.ActivationListener()
        {
            public void onMessageActivated(int messageId)
            {
                Message message = findOpenMessage(messageId);
                if (message != null && message.media != null
                        && message.media.kind == Media.PHOTO)
                {
                    profilePhoto = false;
                    openPhoto(message);
                }
                else { showReactionPalette(messageId); }
            }
        });
        screen.setViewportListener(new ChatScreen.ViewportListener()
        {
            public void onChatViewportChanged() { maybeLoadHistory(); }
        });
        return screen;
    }

    private void loadOpenHistory(final Peer peer)
    {
        if (peer == null) { return; }
        boolean fallback = openHistory.length > 0;
        if (!fallback)
        {
            try
            {
                long accountId = cacheAccountId();
                Message[] cached = accountId == 0 ? null
                        : conversationCache.loadHistory(
                                accountId, Dc.isTest(), peer);
                if (cached != null && cached.length > 0)
                {
                    setOpenHistory(cached);
                    chatScreen.setMessages(openHistory);
                    appendPendingForOpenPeer();
                    chatScreen.setStatus("cached/loading");
                    fallback = true;
                }
            }
            catch (Throwable t)
            {
                Diag.warn("history cache load failed: " + shortMessage(t));
            }
        }
        chatScreen.setStatus(fallback ? "cached/refreshing"
                : ("loading... / " + connectionLabel));
        final boolean hasFallback = fallback;
        worker.submit(new Worker.Task()
        {
            public String name() { return "messages.getHistory"; }

            public Object run() throws Exception
            {
                // Here rather than at the inflate itself. Inflating happens on
                // the MtClient reader thread, where a collect delays every
                // pending RPC and can trip a read timeout; this is the same
                // allocation one level up, on a thread that can afford to pause.
                MemoryPressure.reserve(MemoryBudget.inflateOutputBytes() / 4);
                return telegram.getHistory(peer, MemoryBudget.historyPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(final Object result)
            {
                // callSerially, not straight in: openHistory is also written by
                // the update queue, which arrives on the UI thread. Two threads
                // doing read-modify-write on the same array reference is how a
                // message that lands mid-fetch disappears.
                display.callSerially(new Runnable()
                {
                    public void run()
                    {
                        if (!samePeer(openPeer, peer)) { return; }
                        try
                        {
                            setOpenHistory((Message[]) result);
                            cacheHistory(peer, openHistory);
                            applyKnownReadState(openHistory, peer);
                            // setMessages word-wraps the window and is where the
                            // heap peaks; the guard is here rather than around
                            // the fetch for that reason.
                            chatScreen.setMessages(openHistory);
                            scheduleInlineThumbnails(peer);
                            appendPendingForOpenPeer();
                            chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                            markRead();
                        }
                        catch (Throwable t)
                        {
                            openChatFailed(t);
                        }
                    }
                });
            }

            public void onFailure(Throwable error)
            {
                if (samePeer(openPeer, peer))
                {
                    if (hasFallback && openHistory.length > 0)
                    {
                        chatScreen.setStatus("cached/offline");
                        Diag.warn("using cached history: " + shortMessage(error));
                    }
                    else
                    {
                        chatScreen.setStatus("failed: " + shortMessage(error));
                    }
                }
            }
        });
    }

    /**
     * Fetch another page when the reader is getting close to the top of what is
     * loaded.
     *
     * The margin is in messages rather than pixels because lines beyond the
     * laid-out window have not been wrapped and their count is not known without
     * doing the work. It is wide on purpose: a {@code messages.getHistory} round
     * trip on GPRS is measured in seconds, and the request has to finish before
     * the reader arrives rather than while they watch.
     *
     * Called from the chat screen's viewport callback, so it runs on the lcdui
     * thread and must not block: everything past the guard is a worker submit.
     */
    private void maybeLoadHistory()
    {
        if (chatScreen == null || openPeer == null || openHistory.length == 0
                || historyPageInFlight || navigation.current() != chatScreen)
        {
            return;
        }
        int margin = MemoryBudget.historyPrefetchMargin();

        // Forward first. Both directions can be short at once - the retained
        // window is smaller than the distance a reader can cover - and being
        // unable to get back to the present is the worse of the two.
        if (!historyForwardStalled
                && chatScreen.messagesNewerThanViewport() < margin
                && newestOpenId() < newestKnownId)
        {
            loadNewerPage();
            return;
        }
        if (!historyExhausted
                && chatScreen.messagesOlderThanViewport() < margin)
        {
            loadOlderPage(false);
        }
    }

    /**
     * One page of older history.
     *
     * @param manual pressed by the user rather than provoked by scrolling. Only
     *               changes how loudly it reports itself: an automatic fetch
     *               that finds nothing has simply reached the start of the
     *               conversation, which is not news.
     */
    private void loadOlderPage(final boolean manual)
    {
        if (openPeer == null || chatScreen == null
                || openHistory.length == 0)
        {
            return;
        }
        if (historyPageInFlight)
        {
            if (manual) { chatScreen.setStatus("loading older messages..."); }
            return;
        }
        if (historyExhausted)
        {
            if (manual)
            {
                showAlert("No older messages.", AlertType.INFO, chatScreen);
            }
            return;
        }
        final Peer peer = openPeer;
        final int offsetId = openHistory[openHistory.length - 1].id;
        historyPageInFlight = true;
        chatScreen.setStatus("loading older messages...");
        boolean submitted = worker.submit(new Worker.Task()
        {
            public String name() { return "messages.getHistory/older"; }
            public Object run() throws Exception
            {
                MemoryPressure.reserve(MemoryBudget.inflateOutputBytes() / 4);
                return telegram.getHistoryBefore(peer, offsetId,
                        MemoryBudget.historyPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(final Object result)
            {
                display.callSerially(new Runnable()
                {
                    public void run()
                    {
                        historyPageInFlight = false;
                        if (!samePeer(openPeer, peer)) { return; }
                        Message[] page = (Message[]) result;
                        // Whether the page itself carried anything older than
                        // what was held. Deliberately not "did the retained
                        // array change": the retention window is a fixed size,
                        // so once it is full its length stops moving while its
                        // contents keep sliding backwards - and reading that as
                        // "no older messages" stopped paging five pages into a
                        // channel that had thousands. Deliberately not "did the
                        // retained oldest move" either, because a page fetched
                        // while the viewport is elsewhere can be windowed
                        // straight back out again without being news.
                        boolean older = carriesOlderThan(page, oldestOpenId());
                        mergeHistoryPage(page);
                        cacheHistory(peer, openHistory);
                        applyKnownReadState(openHistory, peer);
                        chatScreen.setMessages(openHistory);
                        scheduleInlineThumbnails(peer);
                        chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                        if (!older)
                        {
                            // Latched rather than retried: without this the
                            // viewport sits against the top of a fully loaded
                            // conversation and asks for the same empty page on
                            // every keypress.
                            historyExhausted = true;
                            if (manual)
                            {
                                showAlert("No older messages.", AlertType.INFO,
                                        chatScreen);
                            }
                        }
                    }
                });
            }
            public void onFailure(final Throwable error)
            {
                display.callSerially(new Runnable()
                {
                    public void run()
                    {
                        historyPageInFlight = false;
                        if (!samePeer(openPeer, peer)) { return; }
                        chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                        if (manual)
                        {
                            showAlertThen("Could not load older messages", error,
                                    chatScreen);
                        }
                        else
                        {
                            Diag.warn("older page failed: " + shortMessage(error));
                        }
                    }
                });
            }
        });
        if (!submitted)
        {
            // The worker drops rather than queues. Clearing the flag is the
            // whole retry: the next viewport event asks again, and by then
            // whatever was busy has usually finished.
            historyPageInFlight = false;
            chatScreen.setStatus(connectionLabel + "/" + updateLabel);
        }
    }

    /**
     * Merge a page into the retained history and slide the window onto it.
     *
     * A bounded merge would truncate the tail, which is the wrong end when
     * reading backwards: the oldest messages are the ones on screen. Merging
     * unbounded and then windowing around what the reader is looking at drops
     * the newest instead, which is the half nobody is looking at.
     */
    private void mergeHistoryPage(Message[] page)
    {
        int oldestBefore = oldestOpenId();
        Message[] merged = PageMerge.merge(openHistory, page);
        // Before windowing, not after: a message can arrive, be recorded as the
        // newest thing this conversation has, and then fall outside a window
        // anchored two hundred messages back. It is still what "read up to
        // here" has to mean.
        noteNewest(merged);
        int anchor = indexOfMessage(merged, chatScreen == null
                ? 0 : chatScreen.topVisibleMessageId());
        if (anchor < 0) { anchor = merged.length - 1; }
        openHistory = PageMerge.window(merged, anchor,
                MemoryBudget.maxHistory());
        // The window slid off the oldest end, so whatever was decided about the
        // start of the conversation was decided about a different oldest.
        if (oldestOpenId() > oldestBefore) { historyExhausted = false; }
    }

    /** Install a new retained history, keeping the high-water mark. */
    private void setOpenHistory(Message[] messages)
    {
        openHistory = messages == null ? new Message[0] : messages;
        noteNewest(openHistory);
    }

    /**
     * Remember the highest id seen. Cannot be recomputed from what is retained:
     * sliding the window off the newest end is exactly what this path does.
     */
    private void noteNewest(Message[] messages)
    {
        for (int i = 0; i < messages.length; i++)
        {
            Message m = messages[i];
            if (m != null && m.id > newestKnownId)
            {
                newestKnownId = m.id;
                historyForwardStalled = false;
            }
        }
    }

    /**
     * The page immediately newer than what is retained.
     *
     * Scrolling back far enough slides the newest messages out of the retention
     * window, and without this the reader arrives at the top of the retained
     * set and simply stops - stranded a few hundred messages behind the present
     * with no way forward but leaving the conversation and opening it again.
     */
    private void loadNewerPage()
    {
        if (openPeer == null || chatScreen == null
                || openHistory.length == 0)
        {
            return;
        }
        final Peer peer = openPeer;
        final int offsetId = newestOpenId();
        historyPageInFlight = true;
        chatScreen.setStatus("loading newer messages...");
        boolean submitted = worker.submit(new Worker.Task()
        {
            public String name() { return "messages.getHistory/newer"; }
            public Object run() throws Exception
            {
                MemoryPressure.reserve(MemoryBudget.inflateOutputBytes() / 4);
                return telegram.getHistoryAfter(peer, offsetId,
                        MemoryBudget.historyPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(final Object result)
            {
                display.callSerially(new Runnable()
                {
                    public void run()
                    {
                        historyPageInFlight = false;
                        if (!samePeer(openPeer, peer)) { return; }
                        Message[] page = (Message[]) result;
                        int before = newestOpenId();
                        mergeHistoryPage(page);
                        cacheHistory(peer, openHistory);
                        applyKnownReadState(openHistory, peer);
                        chatScreen.setMessages(openHistory);
                        scheduleInlineThumbnails(peer);
                        chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                        // Nothing newer came back: the mark is ahead of what
                        // the server will hand over, and asking again on every
                        // keypress would be a request per scroll step.
                        historyForwardStalled = newestOpenId() <= before;
                    }
                });
            }
            public void onFailure(final Throwable error)
            {
                display.callSerially(new Runnable()
                {
                    public void run()
                    {
                        historyPageInFlight = false;
                        if (!samePeer(openPeer, peer)) { return; }
                        chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                        Diag.warn("newer page failed: " + shortMessage(error));
                    }
                });
            }
        });
        if (!submitted)
        {
            historyPageInFlight = false;
            chatScreen.setStatus(connectionLabel + "/" + updateLabel);
        }
    }

    /** Id of the newest message retained, or 0. */
    private int newestOpenId()
    {
        for (int i = 0; i < openHistory.length; i++)
        {
            if (openHistory[i] != null) { return openHistory[i].id; }
        }
        return 0;
    }

    /** Id of the oldest message retained, or 0. */
    private int oldestOpenId()
    {
        for (int i = openHistory.length - 1; i >= 0; i--)
        {
            if (openHistory[i] != null) { return openHistory[i].id; }
        }
        return 0;
    }

    private static boolean carriesOlderThan(Message[] page, int id)
    {
        if (page == null) { return false; }
        for (int i = 0; i < page.length; i++)
        {
            if (page[i] != null && page[i].id < id) { return true; }
        }
        return false;
    }

    private static int indexOfMessage(Message[] messages, int id)
    {
        if (id == 0) { return -1; }
        for (int i = 0; i < messages.length; i++)
        {
            if (messages[i] != null && messages[i].id == id) { return i; }
        }
        return -1;
    }

    /**
     * Decode only bytes already present in message objects. This queue never
     * calls Telegram/openPhoto, so inline mode cannot generate file traffic.
     *
     * Candidates come from the viewport rather than from the head of the
     * transcript. Taking the newest twelve was right while the newest twelve
     * were the only ones anybody could see; once history scrolls, it means
     * reading back through a picture-heavy channel shows no pictures at all.
     */
    private void scheduleInlineThumbnails(final Peer peer)
    {
        final int generation = ++thumbnailGeneration;
        if (!appSettings.mediaPreviews || chatScreen == null)
        {
            return;
        }
        Message[] messages = chatScreen.visibleMessages();
        final Message[] candidates = new Message[Math.min(12, messages.length)];
        int count = 0;
        for (int i = 0; i < messages.length && count < candidates.length; i++)
        {
            Message message = messages[i];
            if (message != null && message.media != null
                    && message.media.kind == Media.PHOTO
                    && message.media.photo != null
                    && message.media.photo.stripped() != null
                    && !chatScreen.hasThumbnail(message.id))
            {
                candidates[count++] = message;
            }
        }
        if (count == 0) { return; }
        final int candidateCount = count;
        new Thread(new Runnable()
        {
            public void run()
            {
                for (int i = 0; i < candidateCount; i++)
                {
                    if (generation != thumbnailGeneration
                            || !samePeer(openPeer, peer)
                            || !appSettings.mediaPreviews)
                    {
                        return;
                    }
                    final Message message = candidates[i];
                    try
                    {
                        byte[] stripped = message.media.photo.stripped().bytes;
                        Image image = JpegDecoder.decode(new ByteArrayInputStream(
                                StrippedJpeg.restore(stripped)), null);
                        final Image thumbnail = ImageScaler.fitBox(image,
                                chatScreen.thumbnailWidth(),
                                chatScreen.thumbnailHeight());
                        display.callSerially(new Runnable()
                        {
                            public void run()
                            {
                                if (generation == thumbnailGeneration
                                        && samePeer(openPeer, peer)
                                        && appSettings.mediaPreviews)
                                {
                                    chatScreen.setThumbnail(message.id, thumbnail);
                                }
                            }
                        });
                    }
                    catch (Throwable error)
                    {
                        Diag.warn("stripped thumbnail " + message.id + ": "
                                + shortMessage(error));
                    }
                }
            }
        }).start();
    }

    /**
     * Best effort. Failing to mark as read is not worth interrupting the user,
     * so it is logged and dropped rather than surfaced.
     */
    private void markRead()
    {
        if (newestKnownId == 0 || openPeer == null)
        {
            return;
        }
        requestMarkRead(openPeer, newestKnownId);
    }

    // -------------------------------------------------------- live updates

    private void applyUpdateBatch(UpdateBatch batch)
    {
        if (batch == null) { return; }
        Peer selectedPeer = null;
        if (dialogList != null && display.getCurrent() == dialogList)
        {
            selectedPeer = selectedDialogPeer();
        }
        if (batch.syncState != null)
        {
            updateLabel = batch.syncState;
        }

        boolean refresh = batch.fullRefresh;
        for (int i = 0; i < batch.messages.length; i++)
        {
            if (!mergeMessage(batch.messages[i])) { refresh = true; }
        }
        for (int i = 0; i < batch.reads.length; i++)
        {
            if (!applyReadState(batch.reads[i])) { refresh = true; }
        }
        for (int i = 0; i < batch.reactions.length; i++)
        {
            if (!applyReactionUpdate(batch.reactions[i])) { refresh = true; }
        }

        if (dialogList != null && display.getCurrent() == dialogList)
        {
            showDialogList(selectedPeer);
        }
        if (chatScreen != null && display.getCurrent() == chatScreen)
        {
            chatScreen.setMessages(openHistory);
            scheduleInlineThumbnails(openPeer);
            appendPendingForOpenPeer();
            chatScreen.setStatus(connectionLabel + "/" + updateLabel);
        }
        cacheDialogs(dialogs);
        if (openPeer != null) { cacheHistory(openPeer, openHistory); }
        if (refresh) { scheduleSnapshotRefresh(); }
    }

    /** Merge one server message into the bounded dialog/history snapshots. */
    private boolean mergeMessage(Message message)
    {
        if (message == null || message.peer == null) { return false; }
        int dialogIndex = findDialog(message.peer);
        if (dialogIndex < 0) { return false; }

        Dialog dialog = dialogs[dialogIndex];
        message.peer = dialog.peer;
        if (message.outgoing && message.id <= dialog.readOutboxMaxId)
        {
            message.read = true;
        }
        dialog.topMessageId = message.id;
        dialog.lastMessage = message.summaryText();
        dialog.lastMessageOutgoing = message.outgoing;
        dialog.date = message.date;

        boolean opened = samePeer(openPeer, message.peer);
        if (!message.outgoing)
        {
            if (opened)
            {
                dialog.unreadCount = 0;
                dialog.readInboxMaxId = Math.max(dialog.readInboxMaxId, message.id);
                requestMarkRead(message.peer, message.id);
            }
            else
            {
                dialog.unreadCount++;
            }
        }
        if (opened) { mergeOpenHistory(message); }
        promoteDialog(dialogIndex);
        return true;
    }

    /**
     * Fold one live message into the open conversation.
     *
     * A message already present is replaced in place, which allocates nothing
     * and is the common case for a read or reaction update. A genuinely new one
     * goes through the same merge-and-window path as a fetched page: it used to
     * make room by truncating the oldest message, and once a reader can be
     * anywhere in the history that is the message they are looking at.
     */
    private void mergeOpenHistory(Message message)
    {
        for (int i = 0; i < openHistory.length; i++)
        {
            if (openHistory[i] != null && openHistory[i].id == message.id
                    && samePeer(openHistory[i].peer, message.peer))
            {
                openHistory[i] = message;
                return;
            }
        }
        mergeHistoryPage(new Message[] { message });
    }

    private boolean applyReadState(ReadState read)
    {
        if (read == null || read.peer == null) { return false; }
        int at = findDialog(read.peer);
        if (at < 0) { return false; }
        Dialog dialog = dialogs[at];
        if (read.inboxMaxId >= 0)
        {
            dialog.readInboxMaxId = Math.max(dialog.readInboxMaxId, read.inboxMaxId);
        }
        if (read.outboxMaxId >= 0)
        {
            dialog.readOutboxMaxId = Math.max(dialog.readOutboxMaxId, read.outboxMaxId);
        }
        if (read.unreadCount >= 0) { dialog.unreadCount = read.unreadCount; }
        if (samePeer(openPeer, read.peer))
        {
            applyKnownReadState(openHistory, read.peer);
        }
        return true;
    }

    private boolean applyReactionUpdate(ReactionUpdate update)
    {
        if (update == null || update.peer == null)
        {
            return false;
        }
        if (!samePeer(openPeer, update.peer)) { return true; }
        for (int i = 0; i < openHistory.length; i++)
        {
            Message message = openHistory[i];
            if (message != null && message.id == update.messageId)
            {
                message.reactions = update.reactions;
                return true;
            }
        }
        return false;
    }

    private void applyKnownReadState(Message[] messages, Peer peer)
    {
        int at = findDialog(peer);
        if (at < 0) { return; }
        int max = dialogs[at].readOutboxMaxId;
        for (int i = 0; i < messages.length; i++)
        {
            Message message = messages[i];
            if (message != null && message.outgoing && message.id <= max)
            {
                message.read = true;
            }
        }
    }

    private int findDialog(Peer peer)
    {
        for (int i = 0; i < dialogs.length; i++)
        {
            if (dialogs[i] != null && samePeer(dialogs[i].peer, peer)) { return i; }
        }
        return -1;
    }

    /** Keep pinned server order; promote changed unpinned dialogs below it. */
    private void promoteDialog(int index)
    {
        if (index < 0 || index >= dialogs.length || dialogs[index].pinned) { return; }
        int firstUnpinned = 0;
        while (firstUnpinned < dialogs.length && dialogs[firstUnpinned].pinned)
        {
            firstUnpinned++;
        }
        if (index <= firstUnpinned) { return; }
        Dialog changed = dialogs[index];
        System.arraycopy(dialogs, firstUnpinned, dialogs, firstUnpinned + 1,
                index - firstUnpinned);
        dialogs[firstUnpinned] = changed;
    }

    private static boolean samePeer(Peer a, Peer b)
    {
        return a != null && b != null && a.kind == b.kind && a.id == b.id;
    }

    /**
     * Coalesce read acknowledgements on a dedicated worker. This cannot use the
     * UI Worker: a history request may still be finishing when a pushed message
     * arrives, and dropping readHistory then would leave the remote state stale.
     */
    private void requestMarkRead(Peer peer, int maxId)
    {
        if (peer == null || maxId <= 0) { return; }
        synchronized (readLock)
        {
            Peer previous = pendingReadPeer;
            pendingReadPeer = peer;
            if (!samePeer(peer, previous))
            {
                pendingReadMaxId = maxId;
            }
            else
            {
                pendingReadMaxId = Math.max(pendingReadMaxId, maxId);
            }
            if (readDrainRunning) { return; }
            readDrainRunning = true;
        }
        new Thread(new Runnable()
        {
            public void run()
            {
                while (true)
                {
                    Peer peer;
                    int maxId;
                    synchronized (readLock)
                    {
                        peer = pendingReadPeer;
                        maxId = pendingReadMaxId;
                        pendingReadPeer = null;
                        pendingReadMaxId = 0;
                        if (peer == null)
                        {
                            readDrainRunning = false;
                            return;
                        }
                    }
                    try { telegram.markRead(peer, maxId); }
                    catch (Throwable t)
                    {
                        Diag.warn("mark-read failed: " + shortMessage(t));
                    }
                }
            }
        }).start();
    }

    private static final class UpdateSnapshot
    {
        Dialog[] dialogs;
        Peer peer;
        Message[] history;
    }

    private static final class AvatarLoad
    {
        final Peer peer;
        final long photoId;
        final Image image;

        AvatarLoad(Peer peer, long photoId, Image image)
        {
            this.peer = peer;
            this.photoId = photoId;
            this.image = image;
        }
    }

    private void scheduleSnapshotRefresh()
    {
        if (snapshotRefreshScheduled) { return; }
        snapshotRefreshScheduled = true;
        new Thread(new Runnable()
        {
            public void run()
            {
                while (worker.isBusy())
                {
                    try { Thread.sleep(250); }
                    catch (InterruptedException ignored) { }
                }
                final Peer target = openPeer;
                boolean submitted = worker.submit(new Worker.Task()
                {
                    public String name() { return "updates.snapshotRefresh"; }

                    public Object run() throws Exception
                    {
                        UpdateSnapshot snapshot = new UpdateSnapshot();
                        snapshot.peer = target;
                        snapshot.dialogs = telegram.getDialogs(Math.min(
                                MemoryBudget.maxDialogs(), Math.max(MemoryBudget.dialogPageSize(),
                                dialogs.length)));
                        if (target != null)
                        {
                            snapshot.history = telegram.getHistory(target,
                                    Math.min(MemoryBudget.maxHistory(), Math.max(
                                    MemoryBudget.historyPageSize(), openHistory.length)));
                        }
                        return snapshot;
                    }
                }, new Worker.Callback()
                {
                    public void onSuccess(final Object result)
                    {
                        display.callSerially(new Runnable()
                        {
                            public void run()
                            {
                                UpdateSnapshot snapshot = (UpdateSnapshot) result;
                                Peer selectedPeer = null;
                                if (dialogList != null
                                        && display.getCurrent() == dialogList)
                                {
                                    selectedPeer = selectedDialogPeer();
                                }
                                dialogs = snapshot.dialogs;
                                cacheDialogs(dialogs);
                                if (snapshot.history != null
                                        && samePeer(openPeer, snapshot.peer))
                                {
                                    // Merged, not assigned. This is the newest
                                    // page; assigning it would throw away every
                                    // older page a reader had scrolled back to
                                    // and drop them at the bottom again.
                                    mergeHistoryPage(snapshot.history);
                                    applyKnownReadState(openHistory, openPeer);
                                    cacheHistory(openPeer, openHistory);
                                }
                                snapshotRefreshScheduled = false;
                                if (dialogList != null
                                        && display.getCurrent() == dialogList)
                                {
                                    showDialogList(selectedPeer);
                                }
                                if (chatScreen != null
                                        && display.getCurrent() == chatScreen)
                                {
                                    chatScreen.setMessages(openHistory);
                                    scheduleInlineThumbnails(openPeer);
                                    appendPendingForOpenPeer();
                                    chatScreen.setStatus(connectionLabel + "/"
                                            + updateLabel);
                                }
                            }
                        });
                    }

                    public void onFailure(final Throwable error)
                    {
                        snapshotRefreshScheduled = false;
                        Diag.warn("update snapshot refresh failed: "
                                + shortMessage(error));
                    }
                });
                if (!submitted) { snapshotRefreshScheduled = false; }
            }
        }).start();
    }

    // ------------------------------------------------------------ sending

    private void showCompose()
    {
        if (composeBox == null)
        {
            composeBox = new TextBox("Message", "", 1000, TextField.ANY);
            composeBox.addCommand(cmdSend);
            composeBox.addCommand(cmdBack);
            composeBox.setCommandListener(this);
        }
        String draft = "";
        try { if (openPeer != null) { draft = draftStore.load(openPeer); } }
        catch (Throwable t) { Diag.error("draft load failed", t); }
        composeBox.setString(draft);
        composeBox.setTitle(replyTarget == null ? "Message"
                : ("Reply to #" + replyTarget.id));
        lastSavedDraft = draft;
        pushScreen(composeBox);
    }

    // ------------------------------------------------------- message actions

    private Message findOpenMessage(int id)
    {
        for (int i = 0; i < openHistory.length; i++)
        {
            if (openHistory[i] != null && openHistory[i].id == id)
            {
                return openHistory[i];
            }
        }
        return null;
    }

    private void beginReply()
    {
        Message message = chatScreen == null ? null
                : findOpenMessage(chatScreen.focusedMessageId());
        if (message == null || message.id <= 0) { return; }
        replyTarget = message;
        showCompose();
    }

    private void beginForward()
    {
        actionMessage = chatScreen == null ? null
                : findOpenMessage(chatScreen.focusedMessageId());
        actionPeer = openPeer;
        if (actionMessage == null || actionMessage.id <= 0) { return; }
        forwardList = new List("Forward to", List.IMPLICIT);
        Peer self = telegram.peers().self();
        Peer[] targets = new Peer[dialogs.length + (self == null ? 0 : 1)];
        int count = 0;
        if (self != null)
        {
            Peer saved = new Peer(Peer.USER, self.id);
            saved.accessHash = self.accessHash;
            saved.self = true;
            saved.title = "Saved Messages";
            targets[count++] = saved;
            forwardList.append(saved.title, null);
        }
        for (int i = 0; i < dialogs.length; i++)
        {
            if (self != null && samePeer(dialogs[i].peer, self)) { continue; }
            targets[count++] = dialogs[i].peer;
            forwardList.append(dialogs[i].title(), null);
        }
        forwardTargets = new Peer[count];
        System.arraycopy(targets, 0, forwardTargets, 0, count);
        if (count == 0) { forwardList.append("(no chats)", null); }
        forwardList.addCommand(cmdForwardHere);
        forwardList.addCommand(cmdBack);
        forwardList.setCommandListener(this);
        pushScreen(forwardList);
    }

    private void forwardToSelectedDialog()
    {
        int index = forwardList == null ? -1 : forwardList.getSelectedIndex();
        if (index < 0 || index >= forwardTargets.length
                || actionMessage == null || actionPeer == null)
        {
            return;
        }
        final Peer destination = forwardTargets[index];
        final Message message = actionMessage;
        final Peer source = actionPeer;
        showBusy("Forward", "Forwarding message...");
        worker.submit(new Worker.Task()
        {
            public String name() { return "messages.forwardMessages"; }
            public Object run() throws Exception
            {
                telegram.forwardMessage(source, message.id, destination);
                return null;
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                restoreScreen(navigation.pop());
                chatScreen.setStatus("forwarded / " + connectionLabel);
            }
            public void onFailure(Throwable error)
            {
                showAlertThen("Could not forward message", error, forwardList);
            }
        });
    }

    private void confirmDeleteMessage()
    {
        actionMessage = chatScreen == null ? null
                : findOpenMessage(chatScreen.focusedMessageId());
        actionPeer = openPeer;
        if (actionMessage == null || actionMessage.id <= 0) { return; }
        deleteConfirm = new Form("Delete message");
        String summary = actionMessage.summaryText();
        if (summary.length() > 80) { summary = summary.substring(0, 77) + "..."; }
        deleteConfirm.append(summary.length() == 0 ? "[message]" : summary);
        if (actionPeer.kind == Peer.CHANNEL)
        {
            deleteConfirm.append("\nThis removes the message from the channel "
                    + "when your account has permission.");
            deleteConfirm.addCommand(cmdDeleteChannel);
        }
        else
        {
            deleteConfirm.append("\nChoose where to delete it.");
            deleteConfirm.addCommand(cmdDeleteLocal);
            deleteConfirm.addCommand(cmdDeleteRevoke);
        }
        deleteConfirm.addCommand(cmdBack);
        deleteConfirm.setCommandListener(this);
        pushScreen(deleteConfirm);
    }

    private void performDelete(final boolean revoke)
    {
        if (actionMessage == null || actionPeer == null) { return; }
        final int messageId = actionMessage.id;
        final Peer peer = actionPeer;
        showBusy("Delete", "Deleting message...");
        worker.submit(new Worker.Task()
        {
            public String name() { return peer.kind == Peer.CHANNEL
                    ? "channels.deleteMessages" : "messages.deleteMessages"; }
            public Object run() throws Exception
            {
                telegram.deleteMessage(peer, messageId, revoke);
                return null;
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                removeOpenMessage(messageId);
                restoreScreen(navigation.pop());
                chatScreen.setMessages(openHistory);
                chatScreen.setStatus("deleted / " + connectionLabel);
                scheduleSnapshotRefresh();
            }
            public void onFailure(Throwable error)
            {
                showAlertThen("Could not delete message", error, deleteConfirm);
            }
        });
    }

    private void removeOpenMessage(int messageId)
    {
        Message[] next = new Message[openHistory.length];
        int count = 0;
        for (int i = 0; i < openHistory.length; i++)
        {
            if (openHistory[i] != null && openHistory[i].id != messageId)
            {
                next[count++] = openHistory[i];
            }
        }
        openHistory = new Message[count];
        System.arraycopy(next, 0, openHistory, 0, count);
    }

    private void markAllReadNow()
    {
        if (openPeer == null) { return; }
        int maxId = 0;
        int dialog = findDialog(openPeer);
        if (dialog >= 0) { maxId = dialogs[dialog].topMessageId; }
        if (maxId <= 0 && openHistory.length > 0) { maxId = openHistory[0].id; }
        if (maxId <= 0) { return; }
        requestMarkRead(openPeer, maxId);
        if (dialog >= 0)
        {
            dialogs[dialog].unreadCount = 0;
            dialogs[dialog].readInboxMaxId =
                    Math.max(dialogs[dialog].readInboxMaxId, maxId);
        }
        showAlert("All loaded messages are marked as read.",
                AlertType.INFO, chatScreen);
    }

    private void showContextProfile()
    {
        Peer target = null;
        if (openPeer != null && openPeer.kind == Peer.USER)
        {
            target = openPeer;
        }
        else if (chatScreen != null)
        {
            Message focused = findOpenMessage(chatScreen.focusedMessageId());
            if (focused != null && focused.sender != null
                    && focused.sender.kind == Peer.USER)
            {
                target = focused.sender;
            }
        }
        if (target == null)
        {
            showAlert("Select a message from a person to open their profile.",
                    AlertType.INFO, chatScreen);
            return;
        }
        showProfile(target, chatScreen);
    }

    private void showProfile(final Peer target, Displayable returnTo)
    {
        if (target == null)
        {
            showAlert("Profile is not available yet.", AlertType.WARNING,
                    returnTo == null ? dialogList : returnTo);
            return;
        }
        showBusy("Profile", "Loading profile...");
        worker.submit(new Worker.Task()
        {
            public String name() { return "users.getFullUser"; }
            public Object run() throws Exception
            {
                return telegram.getProfile(target);
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                currentProfile = (Profile) result;
                rebuildProfileScreen();
                pushScreen(profileScreen);
            }
            public void onFailure(Throwable error)
            {
                showAlertThen("Could not load profile", error,
                        navigation.current());
            }
        });
    }

    private void rebuildProfileScreen()
    {
        Peer peer = currentProfile == null ? null : currentProfile.peer;
        String title = peer == null || peer.title.length() == 0
                ? "Profile" : peer.title;
        profileScreen = new List(title, List.IMPLICIT);
        profileScreen.append("Name: " + title, null);
        if (peer != null && peer.username != null
                && peer.username.length() > 0)
        {
            profileScreen.append("@" + peer.username, null);
        }
        String about = currentProfile == null ? "" : currentProfile.about;
        profileScreen.append("BIO: " + (about.length() == 0 ? "(empty)" : about),
                null);
        profileAvatarIndex = -1;
        if (currentProfile != null && currentProfile.photo != null)
        {
            profileAvatarIndex = profileScreen.size();
            profileScreen.append("Current avatar", null);
            profileScreen.addCommand(cmdOpenAvatar);
        }
        else
        {
            profileScreen.append("Avatar: (none)", null);
        }
        if (peer != null && peer.self)
        {
            profileScreen.addCommand(cmdEditProfile);
        }
        profileScreen.addCommand(cmdBack);
        profileScreen.setCommandListener(this);
    }

    private void openCurrentAvatar()
    {
        if (currentProfile == null || currentProfile.photo == null
                || profileScreen == null
                || profileScreen.getSelectedIndex() != profileAvatarIndex)
        {
            return;
        }
        Message avatar = new Message();
        avatar.id = -1;
        avatar.media = new Media();
        avatar.media.kind = Media.PHOTO;
        avatar.media.label = "[avatar]";
        avatar.media.photo = currentProfile.photo;
        profilePhoto = true;
        openPhoto(avatar);
    }

    private void showProfileEditor()
    {
        if (currentProfile == null || currentProfile.peer == null
                || !currentProfile.peer.self)
        {
            return;
        }
        Peer peer = currentProfile.peer;
        editProfileForm = new Form("Edit profile");
        profileFirstName = new javax.microedition.lcdui.TextField(
                "First name", peer.firstName, 64, TextField.ANY);
        profileLastName = new javax.microedition.lcdui.TextField(
                "Last name", peer.lastName, 64, TextField.ANY);
        profileAbout = new javax.microedition.lcdui.TextField(
                "BIO", currentProfile.about, 255, TextField.ANY);
        editProfileForm.append(profileFirstName);
        editProfileForm.append(profileLastName);
        editProfileForm.append(profileAbout);
        editProfileForm.addCommand(cmdSaveProfile);
        editProfileForm.addCommand(cmdBack);
        editProfileForm.setCommandListener(this);
        pushScreen(editProfileForm);
    }

    private void saveProfile()
    {
        final String first = profileFirstName.getString().trim();
        final String last = profileLastName.getString().trim();
        final String about = profileAbout.getString().trim();
        if (first.length() == 0)
        {
            showAlert("First name is required.", AlertType.WARNING,
                    editProfileForm);
            return;
        }
        showBusy("Profile", "Saving profile...");
        worker.submit(new Worker.Task()
        {
            public String name() { return "account.updateProfile"; }
            public Object run() throws Exception
            {
                return telegram.updateProfile(first, last, about);
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                currentProfile = (Profile) result;
                rebuildProfileScreen();
                navigation.pop();
                replaceScreen(profileScreen);
            }
            public void onFailure(Throwable error)
            {
                showAlertThen("Could not update profile", error,
                        editProfileForm);
            }
        });
    }

    private void showReactionPalette(int messageId)
    {
        Message message = findOpenMessage(messageId);
        if (message == null) { return; }
        reactionMessageId = messageId;
        if (!samePeer(reactionOptionsPeer, openPeer))
        {
            loadReactionOptions(messageId);
            return;
        }
        showReactionPaletteReady(message);
    }

    private void loadReactionOptions(final int messageId)
    {
        if (reactionOptionsLoading || openPeer == null) { return; }
        final Peer peer = openPeer;
        reactionOptionsLoading = true;
        chatScreen.setStatus("loading reactions...");
        boolean submitted = worker.submit(new Worker.Task()
        {
            public String name() { return "available reactions"; }
            public Object run() throws Exception
            {
                return telegram.getAllowedReactions(peer);
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                reactionOptionsLoading = false;
                if (!samePeer(openPeer, peer)) { return; }
                reactionOptionsPeer = peer;
                reactionPalette = (String[]) result;
                reactionLabels = ReactionCatalog.labelsFor(reactionPalette);
                chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                Message message = findOpenMessage(messageId);
                if (message != null)
                {
                    reactionMessageId = messageId;
                    showReactionPaletteReady(message);
                }
            }

            public void onFailure(Throwable error)
            {
                reactionOptionsLoading = false;
                if (!samePeer(openPeer, peer)) { return; }
                reactionOptionsPeer = peer;
                reactionPalette = ReactionCatalog.EMOJI;
                reactionLabels = ReactionCatalog.LABELS;
                chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                Diag.warn("reaction policy fallback: " + shortMessage(error));
                Message message = findOpenMessage(messageId);
                if (message != null)
                {
                    reactionMessageId = messageId;
                    showReactionPaletteReady(message);
                }
            }
        });
        if (!submitted)
        {
            reactionOptionsLoading = false;
            reactionOptionsPeer = peer;
            reactionPalette = ReactionCatalog.EMOJI;
            reactionLabels = ReactionCatalog.LABELS;
            showReactionPaletteReady(findOpenMessage(messageId));
        }
    }

    private void showReactionPaletteReady(Message message)
    {
        if (message == null) { return; }
        if (reactionScreen == null)
        {
            reactionScreen = new ReactionScreen(currentTheme());
            reactionScreen.addCommand(cmdBack);
            reactionScreen.setCommandListener(this);
            reactionScreen.setActivationListener(
                    new ReactionScreen.ActivationListener()
            {
                public void onReactionSelected(int index)
                {
                    toggleReaction(index);
                }

                public void onRemoveAll()
                {
                    Message selected = findOpenMessage(reactionMessageId);
                    if (selected != null)
                    {
                        sendReactionSet(selected, new String[0]);
                    }
                }

                public void onViewReactions()
                {
                    showReactionActors();
                }

                public void onViewSource()
                {
                    openForwardSource();
                }
            });
        }
        reactionScreen.setReactions(reactionPalette, reactionLabels,
                ReactionSummary.chosenEmoji(message.reactions));
        String sourceLabel = null;
        if (message.forwarded != null
                && message.forwarded.canOpen(telegram.peers()))
        {
            Peer source = message.forwarded.source;
            sourceLabel = source.kind == Peer.CHANNEL
                    ? "View in channel" : "View in chat";
        }
        reactionScreen.setActions(hasReactions(message), sourceLabel);
        pushScreen(reactionScreen);
    }

    private static boolean hasReactions(Message message)
    {
        if (message == null || message.reactions == null) { return false; }
        for (int i = 0; i < message.reactions.length; i++)
        {
            if (message.reactions[i] != null
                    && message.reactions[i].count > 0)
            {
                return true;
            }
        }
        return false;
    }

    private void showReactionActors()
    {
        final Message message = findOpenMessage(reactionMessageId);
        final Peer peer = openPeer;
        if (message == null || peer == null) { return; }
        reactionActorsScreen = new TextScreen("Reactions",
                new String[] { "Loading..." }, currentTheme());
        reactionActorsScreen.withBack(cmdBack, this);
        pushScreen(reactionActorsScreen);
        worker.submit(new Worker.Task()
        {
            public String name() { return "messages.getMessageReactionsList"; }
            public Object run() throws Exception
            {
                return telegram.getMessageReactions(peer, message.id, 100);
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                ReactionActorsPage page = (ReactionActorsPage) result;
                int extra = page.totalCount > page.actors.length ? 1 : 0;
                String[] lines = new String[page.actors.length + extra];
                for (int i = 0; i < page.actors.length; i++)
                {
                    ReactionActor actor = page.actors[i];
                    String name = actor.peer == null
                            || actor.peer.title == null
                            || actor.peer.title.length() == 0
                            ? "Unknown peer" : actor.peer.title;
                    lines[i] = actor.emoji + "  " + name;
                }
                if (extra != 0)
                {
                    lines[lines.length - 1] = "Showing "
                            + page.actors.length + " of " + page.totalCount;
                }
                reactionActorsScreen.setLines(lines);
            }

            public void onFailure(Throwable error)
            {
                String detail = shortMessage(error);
                if (detail != null
                        && detail.indexOf("BROADCAST_FORBIDDEN") >= 0)
                {
                    showAlert("Reaction details are unavailable in this chat.",
                            AlertType.INFO, reactionScreen);
                }
                else
                {
                    showAlertThen("Cannot view reactions",
                            error, reactionScreen);
                }
            }
        });
    }

    private void openForwardSource()
    {
        Message message = findOpenMessage(reactionMessageId);
        if (message == null || message.forwarded == null) { return; }
        final ForwardInfo forward = message.forwarded;
        if (!forward.canOpen(telegram.peers())) { return; }
        final ChatScreen returnChat = chatScreen;
        restoreScreen(navigation.pop());
        returnChat.setStatus("opening forwarded message...");
        worker.submit(new Worker.Task()
        {
            public String name() { return "open forwarded source"; }
            public Object run() throws Exception
            {
                Peer source = forward.source;
                if (!telegram.peers().isAddressable(source))
                {
                    source = telegram.resolveUsername(source.username);
                }
                if (source == null || !telegram.peers().isAddressable(source))
                {
                    throw new java.io.IOException(
                            "source chat is not accessible");
                }
                ForwardOpen result = new ForwardOpen();
                result.peer = source;
                result.messageId = forward.messageId;
                result.messages = telegram.getHistoryAround(source,
                        forward.messageId, MemoryBudget.historyPageSize());
                return result;
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object value)
            {
                ForwardOpen result = (ForwardOpen) value;
                openPeer = result.peer;
                newestKnownId = 0;
                historyPageInFlight = false;
                historyExhausted = false;
                historyForwardStalled = false;
                telegram.setActivePeer(openPeer);
                openHistory = new Message[0];
                setOpenHistory(result.messages);
                applyKnownReadState(openHistory, openPeer);
                chatScreen = createChatScreen(openPeer);
                chatScreen.setTitle(openPeer.title == null
                        || openPeer.title.length() == 0
                        ? "Chat" : openPeer.title);
                chatScreen.resetMessages(openHistory);
                chatScreen.focusMessage(result.messageId);
                scheduleInlineThumbnails(openPeer);
                chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                pushScreen(chatScreen);
                markRead();
            }

            public void onFailure(Throwable error)
            {
                returnChat.setStatus(connectionLabel + "/" + updateLabel);
                showAlertThen("Cannot open source", error, returnChat);
            }
        });
    }

    private static final class ForwardOpen
    {
        Peer peer;
        int messageId;
        Message[] messages;
    }

    private void toggleReaction(int at)
    {
        if (at < 0 || at >= reactionPalette.length) { return; }
        Message message = findOpenMessage(reactionMessageId);
        if (message == null) { return; }
        String selected = reactionPalette[at];
        String[] current = ReactionSummary.chosenEmoji(message.reactions);
        boolean present = false;
        for (int i = 0; i < current.length; i++)
        {
            if (selected.equals(current[i])) { present = true; break; }
        }
        String[] next = new String[current.length + (present ? -1 : 1)];
        int w = 0;
        for (int i = 0; i < current.length; i++)
        {
            if (!selected.equals(current[i])) { next[w++] = current[i]; }
        }
        if (!present) { next[w] = selected; }
        sendReactionSet(message, next);
    }

    private void sendReactionSet(final Message message, final String[] reactions)
    {
        restoreScreen(navigation.pop());
        chatScreen.setStatus("reacting...");
        worker.submit(new Worker.Task()
        {
            public String name() { return "messages.sendReaction"; }
            public Object run() throws Exception
            {
                telegram.sendReactions(openPeer, message.id, reactions);
                return null;
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object ignored)
            {
                chatScreen.setStatus(connectionLabel + "/" + updateLabel);
            }

            public void onFailure(Throwable error)
            {
                chatScreen.setStatus("reaction failed");
                String detail = shortMessage(error);
                if (detail != null
                        && detail.indexOf("REACTION_INVALID") >= 0)
                {
                    // Admin/global policy may have changed after the palette
                    // was opened. Force a fresh policy read next time.
                    reactionOptionsPeer = null;
                    showAlert("This reaction is no longer available in this "
                            + "chat. Reopen Reactions to refresh the list.",
                            AlertType.INFO, chatScreen);
                }
                else
                {
                    showAlertThen("Reaction failed", error, chatScreen);
                }
            }
        });
    }

    private void openPhoto(final Message message)
    {
        if (message == null || message.media == null
                || message.media.photo == null)
        {
            showAlert("This photo has no downloadable reference",
                    AlertType.ERROR, navigation.current());
            return;
        }
        photoMessage = message;
        photoReferenceExpired = false;
        if (photoToken != null) { photoToken.cancel(); }
        photoToken = new DownloadToken();
        final DownloadToken token = photoToken;
        if (photoScreen == null)
        {
            photoScreen = new PhotoScreen(currentTheme());
            photoScreen.addCommand(cmdBack);
            photoScreen.addCommand(cmdRetryPhoto);
            photoScreen.addCommand(cmdZoomPhoto);
            photoScreen.setCommandListener(this);
        }
        photoScreen.setImage(null);
        if (cachedPhoto != null && cachedPhotoId == message.media.photo.id)
        {
            photoScreen.setImage(cachedPhoto);
            pushScreen(photoScreen);
            return;
        }
        photoScreen.setStatus("connecting to media dc...");
        pushScreen(photoScreen);
        token.setProgressListener(new DownloadToken.ProgressListener()
        {
            public void onProgress(final int downloaded, final int expected)
            {
                display.callSerially(new Runnable()
                {
                    public void run()
                    {
                        if (token != photoToken || token.isCancelled()) { return; }
                        String total = expected > 0 ? "/" + expected : "";
                        photoScreen.setStatus(downloaded + total + " bytes");
                    }
                });
            }
        });
        worker.submit(new Worker.Task()
        {
            public String name() { return "upload.getFile/photo"; }

            public Object run() throws Exception
            {
                PhotoInputStream in = null;
                try
                {
                    tg.api.PhotoSizeRef stripped = message.media.photo.stripped();
                    if (stripped != null && stripped.bytes != null)
                    {
                        final Image preview = JpegDecoder.decode(
                                new ByteArrayInputStream(
                                        StrippedJpeg.restore(stripped.bytes)),
                                token);
                        display.callSerially(new Runnable()
                        {
                            public void run()
                            {
                                if (token == photoToken && !token.isCancelled())
                                {
                                    photoScreen.setImage(preview);
                                    photoScreen.setStatus("loading full photo...");
                                }
                            }
                        });
                    }
                    int width = photoScreen.viewportWidth();
                    int height = photoScreen.viewportHeight();

                    // Refuse before downloading, not after decoding. This is
                    // the same size Telegram.openPhoto will select, so the
                    // estimate is of the decode that is actually about to
                    // happen. A shed runs first; if it still will not fit, the
                    // message names both numbers rather than blaming the photo.
                    tg.api.PhotoSizeRef chosen =
                            message.media.photo.choose(width, height);
                    long cost = MemoryBudget.photoDecodeCost(
                            chosen.width, chosen.height, chosen.size);
                    if (!MemoryPressure.reserve(cost))
                    {
                        throw new java.io.IOException("this photo needs about "
                                + (cost / 1024) + " KB to decode and only "
                                + (MemoryPressure.headroom() / 1024)
                                + " KB is available");
                    }

                    in = telegram.openPhoto(message.media.photo,
                            width, height, token);
                    Image decoded = JpegDecoder.decode(in, token);
                    if (token.isCancelled())
                    {
                        throw new java.io.IOException("photo download cancelled");
                    }
                    return ImageScaler.fit(decoded, width, height);
                }
                finally
                {
                    if (in != null) { in.close(); }
                }
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (token != photoToken) { return; }
                cachedPhotoId = message.media.photo.id;
                cachedPhoto = (Image) result;
                photoScreen.setImage(cachedPhoto);
            }

            public void onFailure(Throwable error)
            {
                if (token != photoToken || token.isCancelled()) { return; }
                String message = shortMessage(error);
                if (message != null && message.indexOf("FILE_REFERENCE") >= 0)
                {
                    photoReferenceExpired = true;
                    photoScreen.setStatus(profilePhoto
                            ? "reference expired; reopen the profile"
                            : "reference expired; Retry refreshes it");
                }
                else
                {
                    photoScreen.setStatus("failed: " + message);
                }
            }
        });
    }

    private void refreshPhotoReferenceAndOpen()
    {
        if (profilePhoto)
        {
            photoScreen.setStatus("go Back and reopen the profile avatar");
            return;
        }
        final Message previous = photoMessage;
        final Peer peer = openPeer;
        if (previous == null || peer == null) { return; }
        photoReferenceExpired = false;
        photoScreen.setStatus("refreshing photo reference...");
        worker.submit(new Worker.Task()
        {
            public String name() { return "refresh expired photo reference"; }
            public Object run() throws Exception
            {
                return telegram.getHistory(peer, MemoryBudget.historyPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (!samePeer(openPeer, peer)) { return; }
                mergeHistoryPage((Message[]) result);
                chatScreen.setMessages(openHistory);
                scheduleInlineThumbnails(peer);
                Message refreshed = findOpenMessage(previous.id);
                if (refreshed == null)
                {
                    photoScreen.setStatus("photo no longer in recent history");
                    return;
                }
                openPhoto(refreshed);
            }

            public void onFailure(Throwable error)
            {
                photoReferenceExpired = true;
                photoScreen.setStatus("refresh failed: " + shortMessage(error));
            }
        });
    }

    private void sendComposed()
    {
        final String text = composeBox.getString();
        if (text.trim().length() == 0)
        {
            restoreScreen(navigation.pop());
            return;
        }
        final Peer peer = openPeer;
        final int replyToMessageId = replyTarget == null
                ? 0 : replyTarget.id;

        worker.submit(new Worker.Task()
        {
            public String name() { return "outbox.enqueue"; }

            public Object run() throws Exception
            {
                return telegram.enqueueMessage(peer, text, replyToMessageId);
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                try { draftStore.save(peer, ""); }
                catch (Throwable t) { Diag.error("draft clear failed", t); }
                composeBox.setString("");
                composeBox.setTitle("Message");
                lastSavedDraft = "";
                replyTarget = null;
                restoreScreen(navigation.pop());
                chatScreen.appendLocal((replyToMessageId > 0
                        ? ("[reply to #" + replyToMessageId + "] ") : "")
                        + "[queued] " + text);
                chatScreen.scrollToEnd();
                chatScreen.setStatus(connectionLabel + "/" + updateLabel);
            }

            public void onFailure(Throwable error)
            {
                showAlertThen("Could not queue message", error, composeBox);
            }
        });
    }

    // ----------------------------------------------------- reliability UI

    private void updateConnectionUi(int state, int retrySeconds, String detail)
    {
        connectionLabel = Telegram.connectionStateName(state);
        if (state == Telegram.RETRYING)
        {
            connectionLabel += " " + retrySeconds + "s";
        }
        if (dialogList != null)
        {
            dialogList.setStatus(connectionLabel, updateLabel);
        }
        if (chatScreen != null)
        {
            chatScreen.setStatus(connectionLabel + "/" + updateLabel);
        }
        if (state == Telegram.ONLINE)
        {
            updateOutboxUi();
        }
    }

    private void showOutbox()
    {
        rebuildOutbox();
        pushScreen(outboxList);
    }

    private void rebuildOutbox()
    {
        try
        {
            outboxItems = telegram.outgoingMessages();
        }
        catch (Throwable t)
        {
            Diag.error("outbox list failed", t);
            outboxItems = new OutgoingMessage[0];
        }
        List list = new List("Outbox (" + outboxItems.length + ")", List.IMPLICIT);
        for (int i = 0; i < outboxItems.length; i++)
        {
            OutgoingMessage message = outboxItems[i];
            String text = message.text;
            if (text.length() > 40) { text = text.substring(0, 40) + "..."; }
            list.append("[" + message.stateName() + "] "
                    + message.peerTitle + ": " + text, null);
        }
        if (outboxItems.length == 0) { list.append("(empty)", null); }
        list.addCommand(cmdRetrySend);
        list.addCommand(cmdDeleteSend);
        list.addCommand(cmdBack);
        list.setCommandListener(this);
        outboxList = list;
    }

    private void updateOutboxUi()
    {
        if (outboxList != null && display.getCurrent() == outboxList)
        {
            rebuildOutbox();
            replaceScreen(outboxList);
        }
        if (chatScreen != null && display.getCurrent() == chatScreen)
        {
            chatScreen.setMessages(openHistory);
            appendPendingForOpenPeer();
            chatScreen.setStatus(connectionLabel + "/" + updateLabel);
        }
    }

    private void retrySelectedOutgoing()
    {
        int index = outboxList == null ? -1 : outboxList.getSelectedIndex();
        if (index < 0 || index >= outboxItems.length) { return; }
        try
        {
            telegram.retryOutgoing(outboxItems[index].localId);
            rebuildOutbox();
            replaceScreen(outboxList);
        }
        catch (Throwable t) { showAlertThen("Retry failed", t, outboxList); }
    }

    private void deleteSelectedOutgoing()
    {
        int index = outboxList == null ? -1 : outboxList.getSelectedIndex();
        if (index < 0 || index >= outboxItems.length) { return; }
        try
        {
            telegram.deleteOutgoing(outboxItems[index].localId);
            rebuildOutbox();
            replaceScreen(outboxList);
        }
        catch (Throwable t) { showAlertThen("Delete failed", t, outboxList); }
    }

    private void appendPendingForOpenPeer()
    {
        if (openPeer == null || chatScreen == null) { return; }
        try
        {
            OutgoingMessage[] messages = telegram.outgoingMessages();
            for (int i = 0; i < messages.length; i++)
            {
                if (messages[i].peerKind == openPeer.kind
                        && messages[i].peerId == openPeer.id)
                {
                    chatScreen.appendLocal("[" + messages[i].stateName()
                            + "] " + messages[i].text);
                }
            }
        }
        catch (Throwable t) { Diag.error("pending overlay failed", t); }
    }

    private void startDraftAutosave()
    {
        if (draftAutosaveRunning) { return; }
        draftAutosaveRunning = true;
        new Thread(new Runnable()
        {
            public void run()
            {
                while (draftAutosaveRunning)
                {
                    try { Thread.sleep(3000); }
                    catch (InterruptedException ignored) { }
                    if (draftAutosaveRunning && composeBox != null
                            && display.getCurrent() == composeBox)
                    {
                        saveDraftNow();
                    }
                }
            }
        }).start();
    }

    private void saveDraftNow()
    {
        if (draftStore == null || composeBox == null || openPeer == null) { return; }
        try
        {
            String text = composeBox.getString();
            if (!text.equals(lastSavedDraft))
            {
                draftStore.save(openPeer, text);
                lastSavedDraft = text;
            }
        }
        catch (Throwable t) { Diag.error("draft autosave failed", t); }
    }

    // ------------------------------------------------------------ helpers

    private void showBusy(String title, String text)
    {
        Form busy = new Form(title);
        busy.append(text);
        busy.addCommand(cmdLog);
        busy.addCommand(cmdExit);
        busy.setCommandListener(this);
        display.setCurrent(busy);
    }

    private void showLog()
    {
        TextScreen screen = new TextScreen("Log", Diag.snapshot(),
                currentTheme());
        screen.addCommand(cmdBack);
        screen.setCommandListener(this);
        screen.scrollToEnd();
        pushScreen(screen);
    }

    private void showDiagnostics()
    {
        TextScreen screen = new TextScreen("Connection diagnostics",
                                           diagnosticLines(), currentTheme());
        screen.addCommand(cmdBack);
        screen.addCommand(cmdReconnect);
        screen.addCommand(cmdTestDrop);
        screen.addCommand(cmdUpload);
        screen.setCommandListener(this);
        pushScreen(screen);
    }

    /**
     * Show what the last crash recorded.
     *
     * This build has always written {@link CrashLog} and never read it, so the
     * evidence has been accumulating on handsets with no way to get it out.
     * ProbeMidlet cannot help: MIDP scopes record stores to the MIDlet suite,
     * so probe.jar and tg.jar have separate "tgcrash" stores and each can only
     * see its own.
     *
     * Every entry carries heapTotal/heapFree as of the moment it was written,
     * which is the number that decides whether a crash was memory exhaustion.
     */
    private void showCrashLog()
    {
        String[] lines = crashLogLines();
        TextScreen screen = new TextScreen("Crash log", lines, currentTheme());
        screen.addCommand(cmdBack);
        screen.addCommand(cmdUpload);
        screen.addCommand(cmdClearCrash);
        screen.setCommandListener(this);
        pushScreen(screen);
    }

    private String[] crashLogLines()
    {
        String[] entries = CrashLog.load();
        if (entries.length == 0)
        {
            return new String[] { "no crashes recorded" };
        }

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

    // CLDC has no String.split() and a regex engine is not something this heap
    // can afford; ProbeMidlet carries the same two helpers for the same reason.
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

    /**
     * Ship the diagnostic ring, connection state, crash log and heap figures to
     * the development collector.
     *
     * This is the whole point of the collector for the messenger build: a
     * handset that dies on opening a chat cannot be read over the shoulder, and
     * retyping a crash tail off a 2011 screen is how evidence gets lost.
     */
    private void uploadDiagnostics()
    {
        String[] connection = diagnosticLines();
        String[] ring = Diag.snapshot();
        String[] crash = crashLogLines();

        String[] budget = MemoryBudget.lines();

        Runtime rt = Runtime.getRuntime();
        String[] lines = new String[connection.length + ring.length + crash.length
                                    + budget.length + 10];
        int at = 0;
        lines[at++] = "heapTotal=" + rt.totalMemory() + " heapFree=" + rt.freeMemory();
        lines[at++] = "";
        // Every other number in this report has to be read against the budget
        // profile the client was actually running, not the one it shipped with.
        lines[at++] = "-- memory budget --";
        System.arraycopy(budget, 0, lines, at, budget.length);
        at += budget.length;
        lines[at++] = "";
        lines[at++] = "-- connection --";
        System.arraycopy(connection, 0, lines, at, connection.length);
        at += connection.length;
        lines[at++] = "";
        lines[at++] = "-- crash log --";
        System.arraycopy(crash, 0, lines, at, crash.length);
        at += crash.length;
        lines[at++] = "";
        lines[at++] = "-- diagnostic ring --";
        System.arraycopy(ring, 0, lines, at, ring.length);
        at += ring.length;
        lines[at] = "";

        final TextScreen screen = new TextScreen("Upload", new String[] { "starting..." },
                                                 currentTheme());
        screen.addCommand(cmdBack);
        screen.setCommandListener(this);
        pushScreen(screen);

        ReportUpload.send("tg", "Diagnostics", lines, new ReportUpload.Progress()
        {
            public void lines(String[] text) { screen.setLines(text); }
        });
    }

    private String[] diagnosticLines()
    {
        String[] connection = connectionDiagnostics.lines();
        String[] pressure = MemoryPressure.lines();
        String[] lines = new String[connection.length + pressure.length + 10];
        System.arraycopy(connection, 0, lines, 0, connection.length);
        int at = connection.length;
        lines[at++] = "";
        // A client that sheds constantly has budgets that are wrong for this
        // handset, and that is better read off a report than guessed at.
        lines[at++] = "-- memory --";
        System.arraycopy(pressure, 0, lines, at, pressure.length);
        at += pressure.length;
        lines[at++] = "";
        lines[at++] = "-- updates --";
        lines[at++] = "state: " + telegram.updateSyncState();
        lines[at++] = "detail: " + telegram.updateSyncDetail();
        lines[at++] = "queued: " + telegram.queuedUpdates();
        UpdateState state = telegram.updateState();
        lines[at++] = state == null ? "pts/qts: -" :
                ("pts/qts: " + state.pts + "/" + state.qts);
        lines[at++] = state == null ? "date/seq: -" :
                ("date/seq: " + state.date + "/" + state.seq);
        lines[at] = state == null ? "channels: -" :
                ("channels: " + state.channelCount());
        return lines;
    }

    private void showSettings()
    {
        settingsScreen = new SettingsScreen(connectionConfig, appSettings);
        settingsScreen.addCommand(SettingsScreen.CMD_SAVE);
        settingsScreen.addCommand(cmdBack);
        settingsScreen.addCommand(cmdLog);
        settingsScreen.addCommand(cmdDiag);
        settingsScreen.addCommand(cmdLogOut);
        settingsScreen.addCommand(cmdLogOutEverywhere);
        settingsScreen.setCommandListener(this);
        pushScreen(settingsScreen);
    }

    private void saveSettings()
    {
        String before = connectionFingerprint();
        settingsScreen.apply(connectionConfig, appSettings);
        connectionConfig.save(store);
        appSettings.save(store);
        applyTheme();
        configureLogging();
        if (before.equals(connectionFingerprint()))
        {
            if (chatScreen != null)
            {
                chatScreen.setMediaPreviews(appSettings.mediaPreviews);
                chatScreen.setMessages(openHistory);
                scheduleInlineThumbnails(openPeer);
            }
            restoreScreen(navigation.pop());
        }
        else
        {
            telegram.close();
            showBusy("Connecting", "Applying "
                    + ConnectionConfig.name(connectionConfig.mode)
                    + " connection settings...");
            connectAndCheck();
        }
    }

    private String connectionFingerprint()
    {
        return connectionConfig.mode + "|" + connectionConfig.proxyHost + "|"
                + connectionConfig.proxyPort + "|" + connectionConfig.proxySecret;
    }

    private Theme currentTheme()
    {
        return Theme.byId(appSettings == null
                ? Theme.LIGHT : appSettings.themeId);
    }

    private void applyTheme()
    {
        Theme theme = currentTheme();
        if (dialogList != null) { dialogList.setTheme(theme); }
        if (reactionScreen != null) { reactionScreen.setTheme(theme); }
        if (reactionActorsScreen != null)
        {
            reactionActorsScreen.setTheme(theme);
        }
        if (photoScreen != null) { photoScreen.setTheme(theme); }
        for (int i = 0; i < navigation.depth(); i++)
        {
            Displayable screen = navigation.at(i);
            if (screen instanceof ChatScreen)
            {
                ((ChatScreen) screen).setTheme(theme);
            }
            else if (screen instanceof TextScreen)
            {
                ((TextScreen) screen).setTheme(theme);
            }
        }
    }

    private void configureLogging()
    {
        Diag.setMinimumLevel(appSettings == null ? Diag.LVL_INFO
                : appSettings.logLevel);
        stopRemoteLog();
        if (appSettings == null || !appSettings.remoteLog) { return; }

        // An untouched host setting means "wherever this build was told to
        // report". Typing an IP and a port on a numeric keypad after every
        // stop/start of an ephemeral-address VM is not a workflow, so the
        // build-time sink wins unless the tester has explicitly overridden it.
        if (appSettings.remoteLogIsDefault() && DevSink.CONFIGURED)
        {
            remoteLogSink = TcpLogSink.forCollector("tg");
        }
        else
        {
            remoteLogSink = new TcpLogSink(appSettings.remoteLogHost,
                    appSettings.remoteLogPort);
        }
        Diag.setSink(remoteLogSink);
        remoteLogSink.start();
    }

    private void stopRemoteLog()
    {
        Diag.setSink(null);
        if (remoteLogSink != null)
        {
            remoteLogSink.stop();
            remoteLogSink = null;
        }
    }

    private void showError(String title, Throwable t)
    {
        TextScreen screen = new TextScreen(title, new String[] {
            Diag.className(t),
            String.valueOf(t.getMessage()),
            "",
            "recorded in the crash log"
        }, currentTheme());
        screen.addCommand(cmdBack);
        screen.addCommand(cmdLog);
        screen.setCommandListener(this);
        pushScreen(screen);
    }

    /**
     * A failure the user can do something about - typically "no signal". The
     * log is one keypress away because on a handset it is the only diagnostic
     * anyone has.
     */
    private void showRetryableError(String title, Throwable t)
    {
        Form form = new Form(title);
        form.append(shortMessage(t));
        form.append("\n\nCheck the network connection and try again.");
        form.addCommand(cmdRefresh);
        form.addCommand(cmdDiag);
        form.addCommand(cmdSettings);
        form.addCommand(cmdLog);
        form.addCommand(cmdBack);
        form.addCommand(cmdExit);
        form.setCommandListener(this);
        pushScreen(form);
    }

    private void showAlert(String text, AlertType type, Displayable next)
    {
        Alert alert = new Alert("", text, null, type);
        alert.setTimeout(Alert.FOREVER);
        display.setCurrent(alert, next);
    }

    private void showAlertThen(String title, Throwable t, Displayable next)
    {
        Alert alert = new Alert(title, shortMessage(t), null, AlertType.ERROR);
        alert.setTimeout(Alert.FOREVER);
        display.setCurrent(alert, next);
    }

    private static String shortMessage(Throwable t)
    {
        if (t instanceof RpcError)
        {
            return t.getMessage();
        }
        String m = t.getMessage();
        return Diag.className(t) + (m == null ? "" : (": " + m));
    }
}
