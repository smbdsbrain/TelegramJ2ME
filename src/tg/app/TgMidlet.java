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
import tg.plat.RmsUpdateStateStore;
import tg.plat.TcpLogSink;
import tg.ui.ChatScreen;
import tg.ui.AvatarCache;
import tg.ui.DialogListScreen;
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
public class TgMidlet extends MIDlet implements CommandListener
{
    /** Dialogs fetched per refresh. A 320x240 screen shows about a dozen. */
    private static final int DIALOG_LIMIT = 40;

    /** Messages fetched per conversation. */
    private static final int HISTORY_LIMIT = 30;
    private static final int MAX_DIALOGS = 200;
    private static final int MAX_HISTORY = 120;

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
    private final ScreenStack navigation = new ScreenStack();
    private final AvatarCache avatarCache = new AvatarCache();

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
        showStartScreen();
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
            openHistory = context.messages();
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
            loadOlderHistory();
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
        form.addCommand(cmdConnect);
        form.addCommand(cmdSettings);
        form.addCommand(cmdDiag);
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
                return telegram.getDialogs(DIALOG_LIMIT);
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
        if (dialogs.length >= MAX_DIALOGS)
        {
            showAlert("The in-memory dialog limit (" + MAX_DIALOGS
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
                return telegram.getDialogsAfter(pageOffset, DIALOG_LIMIT);
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                Dialog[] page = (Dialog[]) result;
                int before = dialogs.length;
                dialogs = mergeDialogs(dialogs, page, MAX_DIALOGS);
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

    private void openDialog(Peer peer)
    {
        openPeer = peer;
        telegram.setActivePeer(peer);
        chatScreen = createChatScreen(peer);
        chatScreen.setTitle(peer == null ? "Chat" : peer.title);
        thumbnailGeneration++;
        chatScreen.resetMessages(new Message[0]);
        chatScreen.setStatus("loading... / " + connectionLabel);
        pushScreen(chatScreen);
        loadOpenHistory(peer);
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
                    openHistory = cached;
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
                return telegram.getHistory(peer, HISTORY_LIMIT);
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (!samePeer(openPeer, peer)) { return; }
                openHistory = (Message[]) result;
                cacheHistory(peer, openHistory);
                applyKnownReadState(openHistory, peer);
                chatScreen.setMessages(openHistory);
                scheduleInlineThumbnails(peer, openHistory);
                appendPendingForOpenPeer();
                chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                markRead();
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

    private void loadOlderHistory()
    {
        if (openPeer == null || openHistory.length == 0) { return; }
        if (openHistory.length >= MAX_HISTORY)
        {
            showAlert("The in-memory history limit (" + MAX_HISTORY
                    + ") has been reached.", AlertType.INFO, chatScreen);
            return;
        }
        final Peer peer = openPeer;
        final int offsetId = openHistory[openHistory.length - 1].id;
        chatScreen.setStatus("loading older messages...");
        worker.submit(new Worker.Task()
        {
            public String name() { return "messages.getHistory/older"; }
            public Object run() throws Exception
            {
                return telegram.getHistoryBefore(peer, offsetId, HISTORY_LIMIT);
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (!samePeer(openPeer, peer)) { return; }
                Message[] page = (Message[]) result;
                int before = openHistory.length;
                openHistory = mergeMessages(openHistory, page, MAX_HISTORY);
                cacheHistory(peer, openHistory);
                applyKnownReadState(openHistory, peer);
                chatScreen.setMessages(openHistory);
                scheduleInlineThumbnails(peer, openHistory);
                chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                if (before == openHistory.length)
                {
                    showAlert("No older messages.", AlertType.INFO, chatScreen);
                }
            }
            public void onFailure(Throwable error)
            {
                chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                showAlertThen("Could not load older messages", error, chatScreen);
            }
        });
    }

    private static Message[] mergeMessages(Message[] first, Message[] second,
                                           int limit)
    {
        return PageMerge.messages(first, second, limit);
    }

    /**
     * Decode only bytes already present in message objects. This queue never
     * calls Telegram/openPhoto, so inline mode cannot generate file traffic.
     */
    private void scheduleInlineThumbnails(final Peer peer, Message[] messages)
    {
        final int generation = ++thumbnailGeneration;
        if (!appSettings.mediaPreviews || chatScreen == null
                || messages == null)
        {
            return;
        }
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
        if (openHistory.length == 0 || openPeer == null)
        {
            return;
        }
        requestMarkRead(openPeer, openHistory[0].id);
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
            scheduleInlineThumbnails(openPeer, openHistory);
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
        int insert = 0;
        while (insert < openHistory.length)
        {
            Message existing = openHistory[insert];
            if (existing == null || existing.date < message.date
                    || (existing.date == message.date && existing.id < message.id))
            {
                break;
            }
            insert++;
        }
        int length = Math.min(MAX_HISTORY, openHistory.length + 1);
        Message[] merged = new Message[length];
        if (insert > 0)
        {
            System.arraycopy(openHistory, 0, merged, 0,
                    Math.min(insert, length));
        }
        if (insert < length)
        {
            merged[insert] = message;
            int tail = length - insert - 1;
            if (tail > 0)
            {
                System.arraycopy(openHistory, insert, merged, insert + 1, tail);
            }
        }
        openHistory = merged;
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
                                MAX_DIALOGS, Math.max(DIALOG_LIMIT,
                                dialogs.length)));
                        if (target != null)
                        {
                            snapshot.history = telegram.getHistory(target,
                                    Math.min(MAX_HISTORY, Math.max(
                                    HISTORY_LIMIT, openHistory.length)));
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
                                    openHistory = snapshot.history;
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
                                    scheduleInlineThumbnails(openPeer, openHistory);
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
                        forward.messageId, HISTORY_LIMIT);
                return result;
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object value)
            {
                ForwardOpen result = (ForwardOpen) value;
                openPeer = result.peer;
                telegram.setActivePeer(openPeer);
                openHistory = result.messages;
                applyKnownReadState(openHistory, openPeer);
                chatScreen = createChatScreen(openPeer);
                chatScreen.setTitle(openPeer.title == null
                        || openPeer.title.length() == 0
                        ? "Chat" : openPeer.title);
                chatScreen.resetMessages(openHistory);
                chatScreen.focusMessage(result.messageId);
                scheduleInlineThumbnails(openPeer, openHistory);
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
                return telegram.getHistory(peer, HISTORY_LIMIT);
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (!samePeer(openPeer, peer)) { return; }
                openHistory = (Message[]) result;
                chatScreen.setMessages(openHistory);
                scheduleInlineThumbnails(peer, openHistory);
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
        screen.setCommandListener(this);
        pushScreen(screen);
    }

    private String[] diagnosticLines()
    {
        String[] connection = connectionDiagnostics.lines();
        String[] lines = new String[connection.length + 8];
        System.arraycopy(connection, 0, lines, 0, connection.length);
        int at = connection.length;
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
                scheduleInlineThumbnails(openPeer, openHistory);
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
        if (appSettings != null && appSettings.remoteLog)
        {
            remoteLogSink = new TcpLogSink(appSettings.remoteLogHost,
                    appSettings.remoteLogPort);
            Diag.setSink(remoteLogSink);
            remoteLogSink.start();
        }
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
