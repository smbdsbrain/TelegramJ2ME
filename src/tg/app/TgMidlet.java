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

import tg.api.AuthCheck;
import tg.api.Cached;
import tg.api.Dialog;
import tg.api.DialogPage;
import tg.api.DiscussionInfo;
import tg.api.AppSettings;
import tg.api.ForumTopic;
import tg.api.ForumTopicPage;
import tg.api.ForwardInfo;
import tg.api.Message;
import tg.api.MessageEntity;
import tg.api.MessageSearchPage;
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
import tg.api.ThreadInfo;
import tg.api.TopicWindow;
import tg.api.UpdateBatch;
import tg.api.UpdateState;
import tg.api.UpdateSync;
import tg.api.WipeReport;
import tg.crypto.Rng;
import tg.diag.CrashLog;
import tg.diag.Diag;
import tg.io.DelayedWake;
import tg.mem.MemoryBudget;
import tg.mem.MemoryPressure;
import tg.mem.MemoryRelief;
import tg.mt.AuthKey;
import tg.mt.Dc;
import tg.mt.ConnectionConfig;
import tg.mt.ConnectionDiagnostics;
import tg.mt.RpcError;
import tg.plat.MidpLinkFactory;
import tg.plat.RmsAuthKeyStore;
import tg.plat.RmsCheck;
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
import tg.ui.TopicListScreen;
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

    /**
     * A topic list is a screen and one parsed page of flat rows - far lighter
     * than a chat open, which decodes the emoji sheet and wraps a transcript.
     */
    private static final int TOPIC_LIST_OPEN_BYTES = 64 * 1024;

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
    private final Command cmdRetryWipe =
            new Command("Retry cleanup", Command.SCREEN, 1);
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
    private final Command cmdSelectReaction =
            new Command("Select", Command.ITEM, 1);
    private final Command cmdReactionUp = new Command("Up", Command.SCREEN, 2);
    private final Command cmdReactionDown = new Command("Down", Command.SCREEN, 3);
    private final Command cmdRetryPhoto = new Command("Retry", Command.SCREEN, 1);
    private final Command cmdZoomPhoto = new Command("Zoom", Command.SCREEN, 1);
    private final Command cmdOlder = new Command("Older", Command.SCREEN, 4);
    private final Command cmdJumpLatest =
            new Command("Jump to latest", Command.SCREEN, 4);
    private final Command cmdFirstUnread =
            new Command("First unread", Command.SCREEN, 4);
    private final Command cmdMoreDialogs = new Command("More", Command.SCREEN, 4);
    private final Command cmdOpenTopic = new Command("Open", Command.ITEM, 1);
    private final Command cmdMoreTopics = new Command("More", Command.SCREEN, 4);
    private final Command cmdFilter =
            new Command("Filter loaded", Command.SCREEN, 3);
    private final Command cmdTopOfList =
            new Command("Top of list", Command.SCREEN, 2);
    private final Command cmdFindChat =
            new Command("Find chat", Command.SCREEN, 2);
    private final Command cmdFindMessages =
            new Command("Find messages", Command.SCREEN, 3);
    private final Command cmdSearchGo = new Command("Search", Command.SCREEN, 1);
    private final Command cmdOpenResult = new Command("Open", Command.ITEM, 1);
    private final Command cmdMessageSearchGo =
            new Command("Search", Command.SCREEN, 1);
    private final Command cmdOpenMessageResult =
            new Command("Open", Command.ITEM, 1);
    private final Command cmdNextMessageResults =
            new Command("Next page", Command.SCREEN, 2);
    private final Command cmdNewMessageSearch =
            new Command("New search", Command.SCREEN, 3);
    private final Command cmdForwardToResult =
            new Command("Forward here", Command.SCREEN, 1);
    private final Command cmdApplyFilter = new Command("Apply", Command.SCREEN, 1);
    private final Command cmdClearFilter = new Command("Clear", Command.SCREEN, 2);
    private final Command cmdSaved = new Command("Saved Messages", Command.SCREEN, 2);
    private final Command cmdMyProfile = new Command("My profile", Command.SCREEN, 3);
    private final Command cmdProfile = new Command("Profile", Command.SCREEN, 3);
    private final Command cmdReply = new Command("Reply", Command.SCREEN, 1);
    private final Command cmdEditMessage =
            new Command("Edit", Command.SCREEN, 2);
    private final Command cmdOpenComments =
            new Command("Comments", Command.SCREEN, 2);
    private final Command cmdViewFullText =
            new Command("View full text", Command.SCREEN, 2);
    private final Command cmdEntityActions =
            new Command("Links", Command.SCREEN, 2);
    private final Command cmdOpenEntity = new Command("Select", Command.ITEM, 1);
    private final Command cmdOpenExternal =
            new Command("Open externally", Command.SCREEN, 1);
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

    /*
     * Not built here either: both deliver their callbacks through the display,
     * and a MIDlet has no Display until startApp(). See ui below.
     */
    private Worker worker;
    private Worker avatarWorker;

    /**
     * Background maintenance, so it cannot refuse a user.
     *
     * Snapshot refresh and viewport history prefetch are not things anyone
     * explicitly asked for. Sharing the foreground worker meant that every
     * time either ran - which is most of the time on a live account - opening a
     * chat, reacting, searching or jumping was refused, and the user was told
     * to retry work they had already requested.
     *
     * A second Worker for the same reason avatarWorker is one: a decorative or
     * housekeeping request must never take the worker out from under a
     * keypress. Both deliver on the display thread, so the model is still
     * mutated by one thread; what overlaps is the waiting, and the connection
     * is multiplexed. No second socket is involved, so single-socket mode is
     * unaffected.
     */
    private Worker syncWorker;
    /**
     * The display thread, as something that can be handed to a background
     * producer. Everything that mutates the model, the navigation stack or an
     * lcdui object goes through here or is already on it.
     */
    private UiDispatcher ui;
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
    /** What the previous launch left in RMS; read once, before any write. */
    private String startupMarker;

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
    private ChatScreen editCommandScreen;
    private boolean editCommandVisible;
    private boolean commentsCommandVisible;
    private TextBox composeBox;
    private List outboxList;
    private ReactionScreen reactionScreen;
    private TextScreen reactionActorsScreen;
    private int reactionMessageId;
    private Peer reactionActorsPeer;
    private int reactionActorsMessageId;
    private String[] reactionPalette = ReactionCatalog.EMOJI;
    private String[] reactionLabels = ReactionCatalog.LABELS;
    private PhotoScreen photoScreen;
    private DownloadToken photoToken;
    private Message photoMessage;
    private boolean photoReferenceExpired;
    private long cachedPhotoId;
    private Image cachedPhoto;
    private int thumbnailGeneration;

    /** A batch of inline previews is decoding; another would only fight it. */
    private volatile boolean thumbnailsRunning;
    private OutgoingMessage[] outboxItems = new OutgoingMessage[0];

    private String phoneNumber;
    private String phoneCodeHash;

    private Dialog[] dialogs = new Dialog[0];
    private Peer openPeer;

    /**
     * The thread half of the open transcript, or null for the peer's own
     * history. Assigned only inside {@code bindOpenPeer}, beside the peer it
     * qualifies.
     */
    private ThreadInfo openThread;
    private Message[] openHistory = new Message[0];

    /**
     * How far the open conversation has been read, or null when none is open.
     *
     * {@code openHistory[0]} used to be the newest message by construction. It
     * is not any more: reading backwards slides the retained window off the
     * newest end, and marking read against whatever happens to be at the head of
     * the array would report a message the user scrolled past ten minutes ago.
     *
     * A value bound to its peer rather than a bare int, because the mark only
     * ever rises and {@link #restoreScreen} can change the open conversation
     * without passing through {@link #openDialog} - see {@link ReadMark}.
     */
    private ReadMark readMark;

    /** Dialogs the server says exist, or 0 before it has said. */
    private int dialogTotal;

    /**
     * Rows of the chat list above {@link #dialogs}.
     *
     * {@code dialogs} is a window onto the list, not the list: it holds a
     * contiguous run around what is being read, and this is where that run
     * starts. Only the header and the paging arithmetic use it.
     */
    private int dialogsAbove;

    /**
     * Whether the order of the retained rows may no longer be the server's.
     *
     * A refresh that arrives while the window has scrolled away from the top
     * cannot splice the newest page in - row 400 under row 0 reads as a
     * contiguous list that skips four hundred chats - so PageMerge.restate
     * brings the contents forward and leaves the order alone. That is the right
     * trade and it is still a lie unless the header says so.
     */
    private boolean dialogOrderStale;

    /**
     * Chats the reader marked read, until the server reports the same.
     *
     * Applied after every refresh, because a refresh replaces the row object
     * and would otherwise bring the badge back moments after it was cleared.
     */
    private final LocalReads localReads = new LocalReads();

    /**
     * Peer search: the box, the results and what the results are for.
     *
     * Separate from the dialog filter on purpose. Filter narrows the retained
     * window and cannot see past it; this asks Telegram and can return a chat
     * the reader has never scrolled to. Conflating the two is how "not found"
     * comes to mean two different things on one screen.
     */
    private TextBox searchBox;
    private List searchResults;
    private Peer[] searchPeers = new Peer[0];

    /** True when the search was opened to pick a forward target. */
    private boolean searchForForward;

    /** Bounded, replace-in-place in-chat search state. */
    private TextBox messageSearchBox;
    private List messageSearchResults;
    private Message[] messageSearchMessages = new Message[0];
    private Peer messageSearchPeer;
    private String messageSearchQuery = "";
    private int messageSearchNextOffset;
    private int messageSearchShownBefore;
    private boolean messageSearchExhausted;
    private int messageSearchGeneration;

    /**
     * The dialog immediately above the window, or null when the window starts
     * at the top of the list.
     *
     * This is the offset a request for the run above the window is made from,
     * and it is the whole reason dropping rows off the top is safe rather than
     * a wall in disguise.
     */
    private Dialog dialogAbove;

    // ------------------------------------------------- forum topic window

    /** The open forum's topic list, or null. Recreated per forum open. */
    private TopicListScreen topicScreen;

    /** The retained window of topic rows, newest ordering first. */
    private ForumTopic[] topics = new ForumTopic[0];

    /** Rows dropped above the window. */
    private int topicsAbove;

    /** The row immediately above the window, or null at the top. */
    private ForumTopic topicAbove;

    /** Restore points for runs given up; see the dialog twin. */
    private ForumTopic[] topicAboveStack = new ForumTopic[0];
    private int topicAboveDepth;
    private boolean topicTopLost;
    private boolean topicsExhausted;
    private boolean topicPageInFlight;
    private int topicTotal;

    /** Forum whose topic load was refused and is waiting for syncWorker. */
    private Peer pendingTopicsRefreshPeer;

    /**
     * Previous values of {@link #dialogAbove}, oldest first.
     *
     * One entry per run dropped off the top, each the offset that brings that
     * run back. {@code messages.getDialogs} pages downwards only, so without
     * these a row scrolled past could never be reached again except by
     * restarting from the top of the list.
     *
     * Bounded because it is unbounded otherwise: it grows by one entry per
     * page scrolled, and an account nobody has met yet must not be able to
     * turn a scroll into a heap of offsets. At the cap this is a few tens of
     * kilobytes, reached only after thousands of rows.
     */
    private Dialog[] dialogAboveStack = new Dialog[0];
    private int dialogAboveDepth;

    /** Runs the restore stack forgot; scrolling cannot pass this point. */
    private boolean dialogTopLost;

    /** A page of dialogs is on the wire; a second request would only be dropped. */
    private boolean dialogPageInFlight;

    /** The list has reached its end, by the server's word or by ours. */
    private boolean dialogsExhausted;

    /** An older page is on the wire; a second request would only be dropped. */
    private boolean historyPageInFlight;

    /**
     * Bumped by an explicit history navigation.
     *
     * A scroll-driven page that was already on the wire when the reader pressed
     * Jump to latest is not wrong, it is just about where they no longer are.
     * Its content must not be merged into the window the jump installed - that
     * is a page requested against paging offsets the new window does not share.
     * The latch is still cleared by whoever set it, so paging cannot stick.
     */
    private int historyNavigation;

    /** The last page came back empty: this is the start of the conversation. */
    private boolean historyExhausted;

    /**
     * A forward fetch returned nothing newer.
     *
     * Separate from clamping {@link #readMark}, which would look like the same
     * thing and would quietly move the read mark backwards. This only stops
     * asking; it clears the moment anything newer actually turns up.
     */
    private boolean historyForwardStalled;

    /**
     * The open composer session, or null when no compose screen is up.
     *
     * Volatile because three threads touch it: the display thread opens and
     * closes it, the outbox callback closes it from the worker thread, and the
     * draft autosave thread reads it every three seconds. A stale read there
     * would write one chat's text into another chat's draft, which is the
     * defect {@link ComposerState} exists to end.
     */
    private volatile ComposerState composer;
    private List forwardList;
    private Peer[] forwardTargets = new Peer[0];
    private Message actionMessage;
    private Peer actionPeer;
    private Form deleteConfirm;
    private TextBox fullTextBox;
    private List entityList;
    private Message entityMessage;
    private MessageEntity[] entityItems = new MessageEntity[0];
    private Form entityConfirm;
    private ExternalAction.Target pendingEntityTarget;
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
    /** Incoming live messages below the viewport of the current chat. */
    private int unseenLiveMessages;
    private volatile boolean draftAutosaveRunning;

    /** What the store already holds for {@link #composer}; same three threads. */
    private volatile String lastSavedDraft = "";
    private volatile boolean snapshotRefreshScheduled;

    /** Initial/cached dialog refresh waiting for syncWorker. */
    private boolean pendingDialogsRefresh;

    /** Chat whose initial/cached history refresh is waiting for syncWorker. */
    private Peer pendingHistoryRefreshPeer;

    /** One bounded retry when another maintenance request owns syncWorker. */
    private final DelayedWake initialRefreshRetry = new DelayedWake(
            "initial-refresh", new DelayedWake.Wake()
    {
        public void onWake()
        {
            ui.post(new Runnable()
            {
                public void run()
                {
                    boolean dialogs = pendingDialogsRefresh;
                    pendingDialogsRefresh = false;
                    Peer want = pendingHistoryRefreshPeer;
                    pendingHistoryRefreshPeer = null;
                    if (want != null && chatScreen != null
                            && samePeer(openPeer, want))
                    {
                        loadOpenHistory(want);
                    }
                    Peer forum = pendingTopicsRefreshPeer;
                    pendingTopicsRefreshPeer = null;
                    if (forum != null && topicScreen != null
                            && samePeer(topicScreen.peer(), forum))
                    {
                        loadTopics(forum);
                    }
                    if (dialogs && accountActive) { loadDialogs(); }
                }
            });
        }
    });

    /** A refused maintenance refresh waits for history prefetch to release. */
    private final DelayedWake snapshotRefreshRetry = new DelayedWake(
            "snapshot-refresh", new DelayedWake.Wake()
    {
        public void onWake()
        {
            ui.post(new Runnable()
            {
                public void run()
                {
                    // finishLoggedOut drops dialogList and cancels this wake.
                    // Re-check anyway: cancel may race a callback past its
                    // deadline, which is part of DelayedWake's contract.
                    if (dialogList != null) { scheduleSnapshotRefresh(); }
                }
            });
        }
    });

    /**
     * A user-opened reaction-detail screen waits visibly for the maintenance
     * lane instead of failing with "Finishing ... first".  There is only one
     * such screen and DelayedWake keeps only one waiter, so repeated taps do
     * not create sleeping threads on the handset's small heap.
     */
    private final DelayedWake reactionActorsRetry = new DelayedWake(
            "reaction-actors", new DelayedWake.Wake()
    {
        public void onWake()
        {
            ui.post(new Runnable()
            {
                public void run() { retryReactionActors(); }
            });
        }
    });

    /**
     * How old the cached data on screen was when it was read.
     *
     * Held rather than recomputed: the status line is written again on every
     * connection change, and re-reading the record to answer "how old" would be
     * an RMS open per repaint. The age it names is therefore the age at load,
     * which for a screen that is refreshed the moment a connection appears is
     * the honest number anyway.
     */
    private String cachedDialogLabel = "cached";
    private String cachedHistoryLabel = "cached";

    /**
     * Which account and which chat every asynchronous request was made for.
     *
     * A request captures this at submit time and its callback asks whether the
     * capture still holds before touching anything. See {@link AsyncScope}: the
     * durable half of a task is unaffected either way, and only the screen half
     * is dropped.
     */
    private final AsyncScope scope = new AsyncScope();

    /**
     * False from the moment a logout starts until the next sign-in.
     *
     * Narrower than the session generation, and kept beside it: this closes the
     * window between pressing Log out and the erasure actually starting, during
     * which the account is still signed in but its caches must stop being
     * written. Three writers - the dialog cache, the history cache and the
     * avatar worker - ask {@link #cacheAccountId} before every write, and
     * answering 0 from that moment turns each into a no-op.
     *
     * The session generation is what says a result belongs to a <em>different</em>
     * account; this says the current one is on its way out.
     */
    private volatile boolean accountActive = true;

    /** What the last logout managed to erase; null until one has run. */
    private WipeReport lastWipe;

    /**
     * Set once this handset has refused a second concurrent socket.
     *
     * Not persisted: it is a property of the device plus the network in front
     * of it, and a different network may behave differently.
     */
    private volatile boolean avatarsUnavailable;

    /**
     * Whether avatar loading is currently held back for want of heap.
     *
     * Unlike {@link #avatarsUnavailable} this is not a decision, it is the last
     * answer: it exists only so the client says "avatars paused" once rather
     * than on every scroll step, and it clears itself as soon as there is room
     * again.
     */
    private volatile boolean avatarsPaused;

    /**
     * Headroom at which an avatar decode has actually been seen to fail, and
     * the level this client will not try again below.
     *
     * The estimate can be admitted and still not fit.
     * {@link MemoryPressure#headroom} is built on {@code freeMemory()}, which
     * says how much heap is unused and not whether the VM will hand over one
     * contiguous piece of it; measured at 1410 KB of ballast, every check passed
     * and every decode threw {@code OutOfMemoryError: Java heap space}.
     *
     * So the VM's answer is taken as the better measurement. An error here is
     * the most reliable evidence available that there is no room - better than
     * any estimate - and it is remembered, so the thirteen failures that
     * followed the first one become thirteen placeholders instead. It only ever
     * rises, and only from an observation.
     */
    private volatile long avatarHeapFloor;

    /** Headroom the avatar worker was admitted at, for {@link #avatarHeapFloor}. */
    private volatile long avatarAdmittedAt;

    /**
     * Heap measurement state.
     *
     * {@code heapMeasured} is written by the probe thread and read by the UI
     * thread, hence volatile. {@code heapProbeRunning} is only ever touched on
     * the UI thread - set before the probe starts, cleared in the dispatched
     * runnable that follows it.
     */
    private volatile boolean heapMeasured;
    private boolean heapProbeRunning;

    /**
     * Read acknowledgements waiting for their round trip.
     *
     * One entry per conversation rather than one slot in total: the drain is on
     * the network for the length of a {@code readHistory}, and a chat read
     * during that used to be overwritten by the next one - see {@link ReadQueue}.
     */
    private final ReadQueue readQueue = new ReadQueue();

    /**
     * Where a drained acknowledgement goes.
     *
     * Failing to mark as read is not worth interrupting the reader, so it is
     * logged and dropped here rather than surfaced or retried.
     */
    private final ReadQueue.Sink readSink = new ReadQueue.Sink()
    {
        public void markRead(Peer peer, int thread, int maxId)
        {
            try { telegram.markRead(peer, thread, maxId); }
            catch (Throwable t)
            {
                Diag.warn("mark-read failed: " + shortMessage(t));
            }
        }
    };

    // -------------------------------------------------------- MIDlet life

    protected void startApp()
    {
        if (display != null)
        {
            if (telegram != null) { telegram.resume(); }
            return;                            // returning from pause
        }
        display = Display.getDisplay(this);
        ui = new DisplayDispatcher(display);
        worker = new Worker(ui);
        avatarWorker = new Worker(ui);
        syncWorker = new Worker(ui);

        Diag.info("client " + BuildInfo.VERSION + " build " + BuildInfo.BUILD
                  + " env " + BuildInfo.ENV);
        Diag.mem("startup");

        store = new RmsAuthKeyStore();

        // Read before anything writes, so it describes the previous launch
        // rather than this one. "none - this is the first launch" appearing on
        // every launch is a store that does not survive exit, which presents to
        // a user as an app that forgot their login.
        startupMarker = RmsCheck.checkPersistenceMarker();
        Diag.info("rms " + startupMarker);

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
        // The three stores the shell owns rather than the client. Registered
        // here so there is one list of what belongs to an account, instead of
        // a second cleanup path in the logout callback that could only log its
        // failures.
        telegram.accountWipe().add("drafts", draftStore);
        telegram.accountWipe().add("avatars", avatarDiskCache);
        telegram.accountWipe().add("chat cache", conversationCache);
        // The three producers that are on none of this client's own threads:
        // the reconnect loop, the outbox drain and the update queue each raise
        // these from wherever they happen to be. Posted, not applied, for the
        // reason UiDispatcher exists - each one is a multi-step transition.
        telegram.setConnectionListener(new Telegram.ConnectionListener()
        {
            public void onConnectionState(final int state, final int retrySeconds,
                                          final String detail)
            {
                ui.post(new Runnable()
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
                ui.post(new Runnable()
                {
                    public void run() { updateOutboxUi(); }
                });
            }
        });
        telegram.setUpdateListener(new Telegram.UpdateListener()
        {
            public void onUpdates(final UpdateBatch batch)
            {
                ui.post(new Runnable()
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
                ui.post(new Runnable()
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
        pendingDialogsRefresh = false;
        pendingHistoryRefreshPeer = null;
        pendingTopicsRefreshPeer = null;
        initialRefreshRetry.cancel();
        snapshotRefreshRetry.cancel();
        reactionActorsRetry.cancel();
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
            // Landing on the chat list is the navigation reset: there is no
            // conversation any more, so there is nothing a composer could still
            // belong to. resetRoot(dialogList) arrives here too, which is why
            // this is the one hook rather than a check at every caller. Leaving
            // rather than closing, so a reset does not drop what was typed.
            leaveComposer();
            bindOpenPeer(null, null);
            telegram.setActivePeer(null);
            // Everything above the root was popped, so a topic list held here
            // is unreachable and its window with it.
            topicScreen = null;
        }
        else if (topicScreen != null && screen == topicScreen)
        {
            // Landing on the topic list closes the topic the way landing on
            // the chat list closes the chat. The forum stays the active peer,
            // so its channel difference keeps feeding the visible rows.
            leaveComposer();
            bindOpenPeer(null, null);
            telegram.setActivePeer(topicScreen.peer());
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
            bindOpenPeer(context.peer(), context.thread());
            // Before setOpenHistory, which raises the mark: the stack can hold
            // two chats at once - opening a forwarded message's source pushes
            // one over the other - and coming back down must not carry the
            // upper conversation's high-water mark into the lower one.
            rebindReadMark(openPeer, openThreadId());
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
            if (!connectAndCheck())
            {
                showRefused("Not connected", "Try Connect again in a moment.");
            }
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
            else if (topicScreen != null && d == topicScreen)
            {
                refreshTopics();
            }
            else
            {
                loadDialogs();
            }
        }
        else if (c == cmdOpenTopic)
        {
            openSelectedTopic();
        }
        else if (c == cmdOpenComments)
        {
            openComments();
        }
        else if (c == cmdMoreTopics)
        {
            loadMoreTopics(true);
        }
        else if (c == cmdWrite)
        {
            // Explicitly a non-reply session. Write used to inherit whatever
            // reply state an earlier composer had left behind.
            openComposer(ComposerState.write(openPeer, openThreadId()));
        }
        else if (c == cmdJumpLatest)
        {
            jumpToLatest();
        }
        else if (c == cmdFirstUnread)
        {
            jumpToFirstUnread();
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
            loadMoreDialogs(true);
        }
        else if (c == cmdTopOfList)
        {
            goToTopOfList();
        }
        else if (c == cmdSelectReaction && reactionScreen != null)
        {
            reactionScreen.activateSelected();
        }
        else if (c == cmdReactionUp && reactionScreen != null)
        {
            reactionScreen.moveSelection(-1);
        }
        else if (c == cmdReactionDown && reactionScreen != null)
        {
            reactionScreen.moveSelection(1);
        }
        else if (c == cmdFindChat)
        {
            showSearchBox(d == forwardList);
        }
        else if (c == cmdFindMessages && d == chatScreen)
        {
            showMessageSearchBox();
        }
        else if (c == cmdSearchGo)
        {
            runPeerSearch();
        }
        else if (c == cmdMessageSearchGo && d == messageSearchBox)
        {
            runMessageSearch(false);
        }
        else if (c == cmdNextMessageResults && d == messageSearchResults)
        {
            runMessageSearch(true);
        }
        else if (c == cmdNewMessageSearch && d == messageSearchResults)
        {
            messageSearchGeneration++;
            messageSearchBox.setString("");
            replaceScreen(messageSearchBox);
        }
        else if (c == cmdOpenMessageResult && d == messageSearchResults)
        {
            openMessageSearchResult();
        }
        else if (c == cmdOpenResult && d == searchResults)
        {
            openSearchResult();
        }
        else if (c == cmdForwardToResult && d == searchResults)
        {
            Peer destination = selectedSearchPeer();
            if (destination != null) { forwardMessageTo(destination); }
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
        else if (c == cmdEditMessage && d == chatScreen)
        {
            beginEdit();
        }
        else if (c == cmdViewFullText && d == chatScreen)
        {
            showFullMessageText();
        }
        else if (c == cmdEntityActions
                && (d == chatScreen || d == fullTextBox))
        {
            showEntityPicker();
        }
        else if (c == cmdOpenEntity && d == entityList)
        {
            selectEntityAction();
        }
        else if (c == cmdOpenExternal && d == entityConfirm)
        {
            performExternalAction();
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
        else if (c == cmdRetryWipe)
        {
            retryWipe();
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
            leaveComposer();
        }
        if (from == photoScreen)
        {
            if (photoToken != null) { photoToken.cancel(); }
        }
        if (from == reactionActorsScreen)
        {
            reactionActorsRetry.cancel();
            reactionActorsScreen = null;
            reactionActorsPeer = null;
            reactionActorsMessageId = 0;
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
        String seedingAdvice = AuthKey.seedingAdvice(storedSeeding());
        if (seedingAdvice != null)
        {
            // A recommendation on the screen the user already looks at on every
            // launch, not a dialog they have to dismiss. It cannot block
            // anything or repeat itself: it is a line of a form that is built
            // once, and Diagnostics carries the same status permanently.
            form.append("\n\n" + seedingAdvice);
        }
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

    /**
     * @return false when the worker was busy and nothing was started. The two
     *         callers recover differently, so the answer is handed to them
     *         rather than dealt with here.
     */
    private boolean connectAndCheck()
    {
        final AsyncScope.Token asked = scope.capture();
        boolean submitted = worker.submit(new Worker.Task()
        {
            public String name() { return "connect"; }

            public Object run() throws Exception
            {
                telegram.connect();
                return telegram.verifyAuthorization();
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                // A logout, or a different account signing in, while a connect
                // was on the wire. Applying this would rebuild the previous
                // account's list over whatever is on screen now.
                if (!asked.sameSession()) { dropStale("connect"); return; }
                AuthCheck check = (AuthCheck) result;
                if (check.isYes())
                {
                    loadDialogs();
                }
                else if (check.isNo())
                {
                    showPhoneBox();
                }
                else
                {
                    // The session was not refused, it was never checked. Asking
                    // for a phone number here would log the user out of an
                    // account they are still signed in to, and throw away a
                    // stored auth_key that costs two 2048-bit modPows to
                    // replace. Offer what an ordinary connection failure
                    // offers: cached dialogs, or Retry.
                    if (!showCachedDialogsOffline())
                    {
                        showRetryableError("Could not check the session",
                                           check.error);
                    }
                    else
                    {
                        Diag.warn("startup using cached dialogs: "
                                + check.detail);
                    }
                }
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameSession()) { dropStale("connect"); return; }
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
        return submitted;
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

        // The login flow is a session in its own right: a logout landing while
        // one of these is on the wire, or the user going back to the number,
        // must not leave a code box for an account they are no longer signing
        // in to on top of the stack.
        final AsyncScope.Token asked = scope.capture();
        boolean submitted = worker.submit(new Worker.Task()
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
                if (!asked.sameSession()) { dropStale("auth.sendCode"); return; }
                phoneCodeHash = (String) result;
                showCodeBox();
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameSession()) { dropStale("auth.sendCode"); return; }
                showRetryableError("Could not request a code", error);
            }
        });
        // Back to the number they typed, which is untouched: nothing was sent,
        // so nothing about the login flow has moved.
        if (!submitted)
        {
            showRefused("No code requested", "Press Next again in a moment.");
        }
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

        final AsyncScope.Token asked = scope.capture();
        boolean submitted = worker.submit(new Worker.Task()
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
                if (!asked.sameSession()) { dropStale("auth.signIn"); return; }
                Peer me = (Peer) result;
                Diag.info("signed in as " + (me == null ? "?" : me.title));
                loadDialogs();
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameSession()) { dropStale("auth.signIn"); return; }
                if (error instanceof RpcError && ((RpcError) error).isPasswordNeeded())
                {
                    requestPasswordHint();
                    return;
                }
                showAlertThen("Sign-in failed", error, codeBox);
            }
        });
        // The code is still in the box and still valid - it was never sent.
        if (!submitted)
        {
            showRefused("Not signed in", "Press Sign in again in a moment.",
                    codeBox);
        }
    }

    private void resendCode()
    {
        showBusy("Sign in", "Requesting another code...");
        final AsyncScope.Token asked = scope.capture();
        boolean submitted = worker.submit(new Worker.Task()
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
                if (!asked.sameSession()) { dropStale("auth.resendCode"); return; }
                phoneCodeHash = (String) result;
                showCodeBox();
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameSession()) { dropStale("auth.resendCode"); return; }
                showAlertThen("Could not resend code", error, codeBox);
            }
        });
        // No second code was asked for, so the first one is still the live one.
        if (!submitted)
        {
            showRefused("No code resent", "Try Resend code again in a moment.",
                    codeBox);
        }
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
        // Not guarded - this is itself an authorization change. Going back to
        // the number abandons the login in progress, and a sendCode or signIn
        // still in flight for the old one must not put its code box back.
        scope.newSession();
        boolean submitted = worker.submit(new Worker.Task()
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
        if (!submitted)
        {
            // No alert. What the user asked for is the local half - get me back
            // to the number - and that happens either way; it is what the
            // failure branch above already does for the same reason. Only the
            // courtesy cancellation of the old code is lost, and it expires.
            Diag.warn("old login code not cancelled: worker busy with "
                    + worker.busyWith());
            showPhoneBox();
        }
    }

    private void requestPasswordHint()
    {
        showBusy("Two-step verification", "Loading password parameters...");
        final AsyncScope.Token asked = scope.capture();
        boolean submitted = worker.submit(new Worker.Task()
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
                if (!asked.sameSession())
                {
                    dropStale("account.getPassword");
                    return;
                }
                showPasswordBox((String) result);
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameSession())
                {
                    dropStale("account.getPassword");
                    return;
                }
                showAlertThen("Could not load 2FA", error, codeBox);
            }
        });
        if (!submitted)
        {
            // Reached from the sign-in failure callback, so the code box is
            // where the user was and pressing Sign in there comes straight back
            // here with the same SESSION_PASSWORD_NEEDED.
            showRefused("2FA not loaded", "Press Sign in again in a moment.",
                    codeBox);
        }
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
        showBusy("Two-step verification",
                 "Checking the password locally.\n\n"
                 + "This may take several minutes on older hardware. "
                 + "Please keep the app open.");
        final AsyncScope.Token asked = scope.capture();
        boolean submitted = worker.submit(new Worker.Task()
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
                if (!asked.sameSession())
                {
                    dropStale("auth.checkPassword");
                    return;
                }
                Peer me = (Peer) result;
                Diag.info("2FA signed in as " + (me == null ? "?" : me.title));
                loadDialogs();
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameSession())
                {
                    dropStale("auth.checkPassword");
                    return;
                }
                showAlertThen("2FA sign-in failed", error, passwordBox);
            }
        });
        if (submitted)
        {
            // Cleared once the check owns a copy, not before. It used to be
            // cleared on the way in, so a refusal - which touches nothing else -
            // still cost the user a password they may have spent a minute
            // typing on a numeric keypad.
            passwordBox.setString("");
        }
        else
        {
            showRefused("Password not checked",
                    "Press Check password again in a moment.", passwordBox);
        }
    }

    private void logOut()
    {
        showBusy("Log out", "Logging out...");
        // Before the task starts, not inside it. Work already in flight belongs
        // to the account being erased, and this is what stops it writing - see
        // accountActive.
        accountActive = false;
        boolean started = worker.submit(new Worker.Task()
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
                finishLoggedOut(true, null);
            }

            public void onFailure(Throwable error)
            {
                // Telegram.logOut erases locally in a finally, so a failure
                // here is always "the server did not confirm", never "nothing
                // happened". The two are reported apart because they have
                // different answers: one is worth retrying with Telegram, the
                // other means the account may still be on this phone.
                finishLoggedOut(false, error);
            }
        });
        if (!started) { logOutRefused("Log out"); }
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
        accountActive = false;
        boolean started = worker.submit(new Worker.Task()
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
                finishLoggedOut(true, null);
            }

            public void onFailure(Throwable error)
            {
                // auth.resetAuthorizations can fail before the ordinary logout
                // it wraps is reached, in which case nothing local was erased
                // at all. finishLoggedOut runs the erasure itself when that has
                // happened, so this branch cannot be the one that leaves an
                // account on the handset.
                finishLoggedOut(false, error);
            }
        });
        if (!started) { logOutRefused("Log out everywhere"); }
    }

    /**
     * Nothing was logged out, so nothing may stay closed.
     *
     * {@code Worker} runs one operation at a time and refuses while it is busy.
     * Reopening the caches matters more here than the message does: the account
     * is still signed in, and leaving {@code accountActive} false would stop it
     * caching for the rest of the session with nothing to show for it.
     */
    private void logOutRefused(String title)
    {
        accountActive = true;
        showRetryableError(title, new IllegalStateException(
                "still finishing " + worker.busyWith()));
    }

    /**
     * Land on the login screen, having first dropped everything of the account.
     *
     * The store clears that used to live here have moved into
     * {@link tg.api.AccountWipe}, which runs inside {@code Telegram.logOut} and
     * reports what it could not erase. What is left here is the half a wipe
     * cannot reach: screens, arrays and text fields that are still holding the
     * previous account in this MIDlet's heap.
     *
     * It is a long list on purpose. Everything below was live at some point in
     * a signed-in session, and a field left set is a message, a name or a phone
     * number visible to whoever signs in next.
     *
     * @param serverConfirmed whether Telegram acknowledged the logout
     * @param error           why it did not, or null
     */
    private void finishLoggedOut(boolean serverConfirmed, Throwable error)
    {
        // Before anything is torn down. Every request still in flight was made
        // for the account that is about to stop existing on this phone, and
        // this is the line that stops any of them rebuilding a screen or an
        // array out from under the phone box.
        scope.newSession();
        // The screens next, so the draft autosave thread - which fires every
        // three seconds while a compose box is on screen - cannot write one
        // more draft into the store that is about to be, or has just been,
        // emptied.
        draftAutosaveRunning = false;
        // Closed, never left: leaveComposer() would save one last draft into
        // the store the wipe is about to empty. The state itself is account
        // data - the retained Peer carries a contact's name.
        closeComposer();
        composeBox = null;
        bindOpenPeer(null, null);
        if (photoToken != null) { photoToken.cancel(); }
        // A refresh waiting for the worker has nothing left to refresh, and its
        // submission would land on the phone box.
        pendingDialogsRefresh = false;
        pendingHistoryRefreshPeer = null;
        pendingTopicsRefreshPeer = null;
        topicScreen = null;
        initialRefreshRetry.cancel();
        snapshotRefreshScheduled = false;
        snapshotRefreshRetry.cancel();
        reactionActorsRetry.cancel();
        localReads.clear();
        readQueue.clear();

        dialogs = new Dialog[0];
        visibleDialogs = new Dialog[0];
        dialogTotal = 0;
        resetDialogWindow();
        dialogPageInFlight = false;
        dialogList = null;
        dialogFilter = "";
        filterBox = null;

        chatScreen = null;
        openHistory = new Message[0];
        readMark = null;
        historyPageInFlight = false;
        historyExhausted = false;
        historyForwardStalled = false;

        searchBox = null;
        searchResults = null;
        searchPeers = new Peer[0];
        outboxList = null;
        outboxItems = new OutgoingMessage[0];
        forwardList = null;
        forwardTargets = new Peer[0];
        actionMessage = null;
        actionPeer = null;
        deleteConfirm = null;

        reactionScreen = null;
        reactionActorsScreen = null;
        reactionMessageId = 0;
        reactionActorsPeer = null;
        reactionActorsMessageId = 0;
        reactionPalette = ReactionCatalog.EMOJI;
        reactionLabels = ReactionCatalog.LABELS;

        photoScreen = null;
        photoToken = null;
        photoMessage = null;
        photoReferenceExpired = false;
        cachedPhotoId = 0;
        cachedPhoto = null;
        thumbnailGeneration++;

        profileScreen = null;
        editProfileForm = null;
        profileFirstName = null;
        profileLastName = null;
        profileAbout = null;
        currentProfile = null;
        profileAvatarIndex = -1;
        profilePhoto = false;

        avatarCache.clear();

        // Recreated on the way in rather than reused: phoneBox is built once
        // and keeps whatever was typed into it, which after a logout is the
        // previous account's number sitting on the login screen.
        phoneNumber = null;
        phoneCodeHash = null;
        phoneBox = null;
        codeBox = null;
        passwordBox = null;
        settingsScreen = null;

        // Only now is there an answer to give. A wipe that never ran - the
        // "log out everywhere" path can fail before reaching the local half -
        // is run here, so no route out of a session skips it.
        WipeReport report = telegram.lastWipeReport();
        if (report == null) { report = telegram.accountWipe().run(); }
        lastWipe = report;

        showPhoneBox();
        if (!report.complete)
        {
            showWipeIncomplete(report, serverConfirmed, error);
        }
        else if (!serverConfirmed)
        {
            showAlert("This phone no longer holds the account, but Telegram "
                      + "did not confirm the logout:\n" + shortMessage(error)
                      + "\n\nThe session may still be listed on your other "
                      + "devices.", AlertType.WARNING, phoneBox);
        }
    }

    /**
     * The account may still be on this handset, and saying so is the point.
     *
     * Separated from an ordinary error screen because the failure is not the
     * user's connection and retrying the logout would not address it: the
     * server side may well have succeeded. What can be retried is the erasure,
     * so that is the command this offers.
     */
    private void showWipeIncomplete(WipeReport report, boolean serverConfirmed,
                                    Throwable error)
    {
        Form form = new Form("Local data was not erased");
        form.append("Some of this account could not be deleted from this "
                    + "phone:\n" + report.failed
                    + "\n\nUntil it is, signing in as someone else can show "
                    + "the previous account's chats.");
        if (!serverConfirmed)
        {
            form.append("\n\nTelegram also did not confirm the logout: "
                        + shortMessage(error));
        }
        form.addCommand(cmdRetryWipe);
        form.addCommand(cmdDiag);
        form.addCommand(cmdLog);
        form.addCommand(cmdBack);
        form.addCommand(cmdExit);
        form.setCommandListener(this);
        pushScreen(form);
    }

    /** Try the local erasure again, on the worker: it is RMS-bound work. */
    private void retryWipe()
    {
        showBusy("Erasing", "Deleting local account data...");
        final AsyncScope.Token asked = scope.capture();
        boolean started = worker.submit(new Worker.Task()
        {
            public String name() { return "account wipe retry"; }

            public Object run() throws Exception
            {
                return telegram.accountWipe().run();
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                // The erasure itself already happened, whatever the screen has
                // done since - lastWipe records it either way. Only the report
                // and the navigation are dropped.
                WipeReport report = (WipeReport) result;
                lastWipe = report;
                if (!asked.sameSession())
                {
                    dropStale("account wipe retry");
                    return;
                }
                showPhoneBox();
                if (!report.complete)
                {
                    showWipeIncomplete(report, true, null);
                }
                else
                {
                    showAlert("This phone no longer holds the account.",
                              AlertType.INFO, phoneBox);
                }
            }

            public void onFailure(Throwable retryError)
            {
                showPhoneBox();
                showAlert("The erasure could not be retried:\n"
                          + shortMessage(retryError),
                          AlertType.ERROR, phoneBox);
            }
        });
        if (!started)
        {
            showPhoneBox();
            showAlert("Another operation is still running. Try the cleanup "
                      + "again in a moment.", AlertType.WARNING, phoneBox);
        }
    }

    // ------------------------------------------------------------ dialogs

    /**
     * The list from the top: sign-in, reconnect, or Refresh pressed.
     *
     * A reset rather than a page, so it assigns and clears the latch. A reader
     * who had scrolled a long way loses that position, which is the honest
     * meaning of Refresh; the scroll path below never comes through here.
     */
    private void loadDialogs()
    {
        // Every route to this method has an authorized account behind it -
        // sign-in, 2FA, a verified stored session, or Refresh from the list
        // itself - so this is where the caches are allowed to fill again after
        // a logout closed them. See accountActive.
        //
        // The generation moves only on the edge. Refresh comes through here too
        // and does not change who is signed in; bumping on every call would
        // discard the page the user is waiting for every time they pressed it.
        if (!accountActive)
        {
            accountActive = true;
            scope.newSession();
        }
        final Peer selectedPeer = selectedDialogPeer();
        avatarCache.clearFailures();
        resetDialogWindow();
        boolean fallback = dialogs.length > 0;
        if (!fallback)
        {
            try
            {
                long accountId = cacheAccountId();
                Cached cached = accountId == 0 ? null
                        : conversationCache.loadDialogs(
                                accountId, Dc.isTest());
                if (cached != null && cached.dialogs().length > 0)
                {
                    dialogs = cached.dialogs();
                    cachedDialogLabel = cachedLabel(cached);
                    showDialogList(selectedPeer);
                    dialogList.setStatus(cachedDialogLabel + ", refreshing",
                            updateLabel);
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
        final AsyncScope.Token asked = scope.capture();
        // Cached rows are already interactive. Their refresh must not refuse a
        // peer search or chat open selected from them, so it shares the
        // read/maintenance lane with the other automatic pages.
        boolean submitted = syncWorker.submit(new Worker.Task()
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
                // The one that mattered most: without this a page requested
                // before a logout repopulates the list and resets the
                // navigation root on top of the phone box, under the next
                // account's name.
                if (!asked.sameSession())
                {
                    dropStale("messages.getDialogs");
                    return;
                }
                DialogPage page = (DialogPage) result;
                // A first page is row zero, whatever the window was showing
                // before: this is the path Refresh and reconnect take.
                resetDialogWindow();
                dialogs = page.dialogs;
                dialogTotal = page.total;
                dialogsExhausted = page.complete;
                cacheDialogs(dialogs);
                showDialogList(selectedPeer);
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getDialogs");
                    return;
                }
                if (hasFallback && dialogs.length > 0)
                {
                    showDialogList(selectedPeer);
                    dialogList.setStatus(cachedDialogLabel + ", offline",
                            updateLabel);
                    Diag.warn("using cached dialogs: " + shortMessage(error));
                }
                else { showRetryableError("Could not load chats", error); }
            }
        });
        if (submitted)
        {
            pendingDialogsRefresh = false;
            cancelInitialRefreshRetryIfIdle();
        }
        else
        {
            pendingDialogsRefresh = true;
            initialRefreshRetry.schedule(400L);
            if (hasFallback && dialogList != null)
            {
                dialogList.setStatus("refresh waiting", updateLabel);
            }
            else
            {
                showBusy("Chats", "Waiting to load chats...");
            }
        }
    }

    private void cancelInitialRefreshRetryIfIdle()
    {
        if (!pendingDialogsRefresh && pendingHistoryRefreshPeer == null
                && pendingTopicsRefreshPeer == null)
        {
            initialRefreshRetry.cancel();
        }
    }

    private void showDialogList()
    {
        showDialogList(null);
    }

    private void showDialogList(Peer selectedPeer)
    {
        // Before the filter and before the screen sees any of it: a refresh
        // has just replaced these row objects, and the badges the reader
        // cleared live outside them on purpose. See LocalReads.
        for (int i = 0; i < dialogs.length; i++) { localReads.apply(dialogs[i]); }
        visibleDialogs = filterDialogs(dialogs, dialogFilter);
        if (dialogList == null)
        {
            dialogList = new DialogListScreen(currentTheme());
            dialogList.addCommand(cmdOpen);
            dialogList.addCommand(cmdRefresh);
            dialogList.addCommand(cmdMoreDialogs);
            dialogList.addCommand(cmdTopOfList);
            dialogList.addCommand(cmdFindChat);
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
                    maybeLoadDialogs();
                }
            });
        }
        dialogList.removeCommand(cmdClearFilter);
        if (dialogFilter.length() > 0) { dialogList.addCommand(cmdClearFilter); }
        dialogList.setTheme(currentTheme());
        dialogList.setStatus(connectionLabel, updateLabel);
        // "No matches" on its own reads as "you are not in a chat by that
        // name", and the filter only ever saw the loaded window. Until PR-016
        // there is no way to ask Telegram, so the wording has to carry it.
        dialogList.setEmptyText(dialogFilter.length() == 0
                ? "(no chats)"
                : "(no match in the " + dialogs.length + " loaded chats)");
        dialogList.setWindowLabel(dialogWindowLabel());
        // The server's total when it gave one, so the header reads "912/1690"
        // rather than counting the list against itself and always agreeing.
        // A filter hides rows without moving them, so the window start it is
        // given is only meaningful unfiltered.
        dialogList.setDialogs(visibleDialogs,
                dialogFilter.length() == 0 ? dialogsAbove : 0,
                Math.max(dialogTotal, dialogsAbove + dialogs.length),
                selectedPeer);
        if (navigation.root() != dialogList) { resetRoot(dialogList); }
        else { restoreScreen(dialogList); }
        loadVisibleAvatars();
        maybeLoadDialogs();
    }

    /**
     * "601-720/1690", or what can honestly be said instead.
     *
     * Bounded by construction: four numbers, whatever the list is a window
     * onto. Under a filter it counts matches against what is loaded rather than
     * against the server total, because the filter never saw the rest.
     */
    private String dialogWindowLabel()
    {
        if (dialogFilter.length() > 0)
        {
            return visibleDialogs.length + " of " + dialogs.length + " loaded";
        }
        if (dialogs.length == 0) { return "0"; }
        int first = dialogsAbove + 1;
        int last = dialogsAbove + dialogs.length;
        int total = Math.max(dialogTotal, last);
        String range = first + "-" + last + "/" + total;
        return dialogOrderStale ? (range + " *") : range;
    }

    /**
     * Back to the newest window, in one action.
     *
     * The restore stack walks back one page at a time and is bounded, so a
     * reader a long way down has no way to return except by scrolling through
     * everything they scrolled past. This is that way: the same reset a Refresh
     * performs, named for what the user wants rather than for what it does.
     */
    private void goToTopOfList()
    {
        if (dialogList == null) { return; }
        dialogFilter = "";
        dialogOrderStale = false;
        loadDialogs();
    }

    /**
     * Ask Telegram for a chat, rather than filtering the ones already here.
     *
     * @param forForward true when a forward target is being picked, which
     *                   changes what selecting a result does and nothing else
     */
    private void showSearchBox(boolean forForward)
    {
        searchForForward = forForward;
        if (searchBox == null)
        {
            searchBox = new TextBox("Find a chat on Telegram", "", 64,
                    TextField.ANY);
            searchBox.addCommand(cmdSearchGo);
            searchBox.addCommand(cmdBack);
            searchBox.setCommandListener(this);
        }
        else
        {
            searchBox.setString("");
        }
        searchBox.setTitle(forForward ? "Find a chat to forward to"
                : "Find a chat on Telegram");
        pushScreen(searchBox);
    }

    private void runPeerSearch()
    {
        if (searchBox == null) { return; }
        final String query = searchBox.getString().trim();
        if (query.length() < 2)
        {
            showAlert("Type at least two characters.", AlertType.WARNING,
                    searchBox);
            return;
        }
        final boolean forForward = searchForForward;
        showBusy("Search", "Searching Telegram for \"" + query + "\"...");
        final AsyncScope.Token asked = scope.capture();
        boolean submitted = worker.submit(new Worker.Task()
        {
            public String name() { return "contacts.search"; }
            public Object run() throws Exception
            {
                return telegram.searchPeers(query, MemoryBudget.peerSearchLimit());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (!asked.sameSession())
                {
                    dropStale("contacts.search");
                    return;
                }
                showSearchResults(query, (Peer[]) result, forForward);
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameSession() || searchBox == null)
                {
                    dropStale("contacts.search");
                    return;
                }
                showAlertThen("Search failed", error, searchBox);
            }
        });
        if (!submitted)
        {
            // The query is still in the box and nothing was asked for, so
            // pressing Search again is the whole retry.
            showRefused("Not searched", "Press Search again in a moment.",
                    searchBox);
        }
    }

    private void showSearchResults(String query, Peer[] found, boolean forForward)
    {
        searchPeers = found == null ? new Peer[0] : found;
        searchForForward = forForward;
        searchResults = new List("Results for " + query, List.IMPLICIT);
        for (int i = 0; i < searchPeers.length; i++)
        {
            searchResults.append(peerLabel(searchPeers[i]), null);
        }
        if (searchPeers.length == 0)
        {
            // Distinct from the Filter wording on purpose: this one really did
            // ask Telegram, so "no chat by that name" is a claim it can make.
            searchResults.append("(Telegram found no chat by that name)", null);
        }
        searchResults.addCommand(forForward ? cmdForwardToResult : cmdOpenResult);
        searchResults.addCommand(cmdBack);
        searchResults.setCommandListener(this);
        // Replaces the query box rather than stacking on it: Back from the
        // results belongs on the screen the search was started from, and a
        // reader who wants a different query presses Find chat again.
        //
        // replace() overwrites the top of the stack, which is already the query
        // box - so there is nothing to pop first. Popping first overwrote the
        // screen *underneath* it instead, and when the search was started from
        // the chat list that screen was the root: the results became the root,
        // and Back on a root screen is how this MIDlet exits. It looked exactly
        // like a crash on the handset and left nothing in the crash log,
        // because nothing had thrown.
        replaceScreen(searchResults);
    }

    /** @return the selected result, or null when the row is the empty notice */
    private Peer selectedSearchPeer()
    {
        if (searchResults == null) { return null; }
        int index = searchResults.getSelectedIndex();
        if (index < 0 || index >= searchPeers.length) { return null; }
        return searchPeers[index];
    }

    private void openSearchResult()
    {
        Peer peer = selectedSearchPeer();
        if (peer == null) { return; }
        // Back onto the chat list first: a chat opened from a search belongs in
        // the same place as one opened from the list, not on top of a results
        // screen the reader would have to walk back through.
        restoreScreen(navigation.pop());
        openDialog(peer);
    }

    private static String peerLabel(Peer peer)
    {
        if (peer == null) { return "(unknown)"; }
        String title = peer.title == null || peer.title.length() == 0
                ? "(no name)" : peer.title;
        if (peer.username != null && peer.username.length() > 0)
        {
            return title + "  @" + peer.username;
        }
        return title;
    }

    private void showMessageSearchBox()
    {
        if (openPeer == null) { return; }
        messageSearchPeer = openPeer;
        messageSearchGeneration++;
        messageSearchQuery = "";
        messageSearchNextOffset = 0;
        messageSearchShownBefore = 0;
        messageSearchExhausted = false;
        messageSearchMessages = new Message[0];
        if (messageSearchBox == null)
        {
            messageSearchBox = new TextBox("Find messages", "", 64,
                    TextField.ANY);
            messageSearchBox.addCommand(cmdMessageSearchGo);
            messageSearchBox.addCommand(cmdBack);
            messageSearchBox.setCommandListener(this);
        }
        else
        {
            messageSearchBox.setString("");
        }
        pushScreen(messageSearchBox);
    }

    private void runMessageSearch(final boolean nextPage)
    {
        if (messageSearchPeer == null || !samePeer(openPeer, messageSearchPeer))
        {
            return;
        }
        final String query = nextPage ? messageSearchQuery
                : messageSearchBox.getString().trim();
        if (query.length() < 2)
        {
            showAlert("Type at least two characters.", AlertType.WARNING,
                    messageSearchBox);
            return;
        }
        if (query.length() > 64)
        {
            showAlert("Search is limited to 64 characters.",
                    AlertType.WARNING, messageSearchBox);
            return;
        }
        if (nextPage && messageSearchExhausted) { return; }
        final Peer peer = messageSearchPeer;
        final int offset = nextPage ? messageSearchNextOffset : 0;
        final int before = nextPage ? messageSearchShownBefore : 0;
        final int generation = ++messageSearchGeneration;
        messageSearchQuery = query;
        showBusy("Message search", "Searching this chat...");
        final AsyncScope.Token asked = scope.capture(peer, openThreadId());
        boolean submitted = worker.submit(new Worker.Task()
        {
            public String name() { return "messages.search"; }
            public Object run() throws Exception
            {
                // The captured thread keeps an in-topic Find inside the topic
                // instead of searching the whole supergroup.
                return telegram.searchMessages(peer, asked.thread(), query,
                        offset, 0, MemoryBudget.messageSearchLimit());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (!asked.sameChat(openPeer, openThreadId())
                        || generation != messageSearchGeneration
                        || !query.equals(messageSearchQuery))
                {
                    dropStale("messages.search");
                    return;
                }
                showMessageSearchResults((MessageSearchPage) result, before);
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameChat(openPeer, openThreadId())
                        || generation != messageSearchGeneration)
                {
                    dropStale("messages.search");
                    return;
                }
                Displayable back = nextPage && messageSearchResults != null
                        ? (Displayable) messageSearchResults
                        : (Displayable) messageSearchBox;
                showAlertThen("Message search failed", error, back);
            }
        });
        if (!submitted)
        {
            showRefused("Not searched", "Press Search again in a moment.",
                    nextPage ? (Displayable) messageSearchResults
                            : (Displayable) messageSearchBox);
        }
    }

    private void showMessageSearchResults(MessageSearchPage page, int before)
    {
        messageSearchMessages = page == null || page.messages == null
                ? new Message[0] : page.messages;
        messageSearchNextOffset = page == null ? 0 : page.nextOffsetId;
        messageSearchShownBefore = before + messageSearchMessages.length;
        messageSearchExhausted = page == null || page.exhausted;
        int first = messageSearchMessages.length == 0 ? 0 : before + 1;
        int last = before + messageSearchMessages.length;
        String total = page == null ? "0" : ((page.totalExact ? "" : "about ")
                + page.totalCount);
        messageSearchResults = new List("Messages " + first + "-" + last
                + " of " + total, List.IMPLICIT);
        for (int i = 0; i < messageSearchMessages.length; i++)
        {
            Message message = messageSearchMessages[i];
            String label = message.senderName();
            if (label.length() > 0) { label += ": "; }
            String summary = message.summaryText();
            if (summary.length() > 72)
            {
                summary = summary.substring(0, 69) + "...";
            }
            messageSearchResults.append(label + summary, null);
        }
        if (messageSearchMessages.length == 0)
        {
            messageSearchResults.append("(no matching messages)", null);
        }
        messageSearchResults.addCommand(cmdOpenMessageResult);
        if (!messageSearchExhausted)
        {
            messageSearchResults.addCommand(cmdNextMessageResults);
        }
        messageSearchResults.addCommand(cmdNewMessageSearch);
        messageSearchResults.addCommand(cmdBack);
        messageSearchResults.setCommandListener(this);
        replaceScreen(messageSearchResults);
    }

    private void openMessageSearchResult()
    {
        if (messageSearchResults == null || messageSearchPeer == null) { return; }
        int index = messageSearchResults.getSelectedIndex();
        if (index < 0 || index >= messageSearchMessages.length) { return; }
        final int messageId = messageSearchMessages[index].id;
        final Peer peer = messageSearchPeer;
        final ChatScreen returnChat = chatScreen;
        restoreScreen(navigation.pop());
        returnChat.setStatus("opening search result...");
        final ThreadInfo thread = openThread;
        final AsyncScope.Token asked = scope.capture(peer, openThreadId());
        boolean submitted = worker.submit(new Worker.Task()
        {
            public String name() { return "open message search result"; }
            public Object run() throws Exception
            {
                return telegram.getHistoryAround(peer, asked.thread(),
                        messageId, MemoryBudget.historyPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("open message search result");
                    return;
                }
                bindOpenPeer(peer, thread);
                rebindReadMark(openPeer, openThreadId());
                historyPageInFlight = false;
                historyExhausted = false;
                historyForwardStalled = false;
                telegram.setActivePeer(openPeer);
                openHistory = new Message[0];
                setOpenHistory((Message[]) result);
                applyKnownReadState(openHistory, openPeer);
                returnChat.resetMessages(openHistory);
                returnChat.focusMessage(messageId);
                returnChat.setStatus(connectionLabel + "/" + updateLabel);
                scheduleInlineThumbnails(openPeer);
                markRead();
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("open message search result");
                    return;
                }
                returnChat.setStatus(connectionLabel + "/" + updateLabel);
                showAlertThen("Cannot open result", error, returnChat);
            }
        });
        if (!submitted)
        {
            returnChat.setStatus(connectionLabel + "/" + updateLabel);
            showRefused("Result not opened", "Try Open again in a moment.",
                    returnChat);
        }
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
        // Asked before the request, not after the decode. An avatar that will
        // not fit costs a placeholder here; attempted, it costs a round trip, a
        // decode and a caught OutOfMemoryError, and the list would pay that once
        // per visible row.
        //
        // fits() rather than reserve(): this runs on the lcdui thread, where a
        // collect stalls the display. The worker asks the expensive question a
        // moment later, with the real size of the real image in hand.
        //
        // And the estimate is not the only bar. avatarHeapFloor is what the VM
        // has already demonstrated about this heap, which beats any arithmetic
        // done from freeMemory().
        long room = MemoryPressure.headroom();
        if (!MemoryPressure.fits(MemoryBudget.avatarDecodeCost())
                || room <= avatarHeapFloor)
        {
            if (!avatarsPaused)
            {
                avatarsPaused = true;
                Diag.warn("avatars paused: one needs about "
                        + (MemoryBudget.avatarDecodeCost() / 1024)
                        + "k, headroom is " + (room / 1024)
                        + "k, one failed at " + (avatarHeapFloor / 1024) + "k");
            }
            return;
        }
        if (avatarsPaused)
        {
            avatarsPaused = false;
            Diag.info("avatars resumed");
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
            // What this used to bump its own counter for. avatarGeneration
            // moved on exactly one event - a logout - which is what the session
            // generation is, so the two are now one.
            final AsyncScope.Token asked = scope.capture(peer, 0);
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
                    // The exact question, now that the image is here: its own
                    // header says how many pixels it is about to become.
                    // Bounded at level 1 - an avatar must not clear the avatar
                    // cache to make room for an avatar. See MemoryPressure.
                    int size = JpegDecoder.dimensions(bytes);
                    long cost = MemoryBudget.photoDecodeCost(size >>> 16,
                            size & 0xffff, bytes.length);
                    if (!MemoryPressure.reserve(cost, 1))
                    {
                        throw new NoRoom("avatar needs " + (cost / 1024)
                                + "k, headroom "
                                + (MemoryPressure.headroom() / 1024) + "k");
                    }
                    // Remembered so that, if the decode throws anyway, the
                    // failure can tell the list how much room turned out not to
                    // be enough. One avatar worker at a time, so no race.
                    avatarAdmittedAt = MemoryPressure.headroom();
                    Image decoded = JpegDecoder.decode(bytes, null);
                    Image scaled = ImageScaler.fitBox(decoded, target, target);
                    // Re-asked, not reused: the id was read before the download,
                    // and a logout can have happened during it. This worker is
                    // a separate Worker from the one running auth.logOut, so
                    // the two genuinely overlap.
                    if (downloaded && accountId != 0 && accountActive)
                    {
                        avatarDiskCache.save(accountId, Dc.isTest(), peer, bytes);
                    }
                    return new AvatarLoad(peer, photoId, scaled);
                }
            }, new Worker.Callback()
            {
                public void onSuccess(final Object result)
                {
                    AvatarLoad loaded = (AvatarLoad) result;
                    if (asked.sameSession()
                            && loaded.peer.avatar != null
                            && loaded.peer.avatar.photoId == loaded.photoId)
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

                    // An OutOfMemoryError out of the decode is a better
                    // measurement than the estimate that let it through, so it
                    // becomes the floor. Without this the list simply moves to
                    // the next row and fails there too, which is the storm this
                    // whole change exists to end.
                    if (error instanceof OutOfMemoryError)
                    {
                        // Not the headroom at the failure - the headroom at the
                        // failure plus what the work needed. headroom() said
                        // there was room for this decode and there was not, so
                        // it was overstating by at least the size of the decode;
                        // asking only for "more than last time" lets the first
                        // collect clear the bar and the next row fail the same
                        // way. Measured: at 1410 KB of ballast that flapped
                        // between paused and resumed nine times in fifteen
                        // seconds, which is nine wasted decodes.
                        long floor = avatarAdmittedAt
                                + MemoryBudget.avatarDecodeCost();
                        if (floor > avatarHeapFloor) { avatarHeapFloor = floor; }
                    }
                    final boolean noRoom = error instanceof NoRoom
                            || error instanceof OutOfMemoryError;

                    if (asked.sameSession())
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
                        // Chained on every other failure, because the next row
                        // may well succeed. Not on a memory refusal: that would
                        // walk the whole visible list refusing one row at a
                        // time. The next scroll asks again, by which time the
                        // collect this already ran may have changed the answer.
                        if (!noRoom) { loadVisibleAvatars(); }
                    }
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

    /** "cached 18 min old" for the status line; see {@link Cached}. */
    private static String cachedLabel(Cached cached)
    {
        return cached == null ? "cached"
                : cached.ageLabel(System.currentTimeMillis());
    }

    private long cacheAccountId()
    {
        long id = resolveAccountId();
        // Pushed rather than passed. The durable stores stamp every record they
        // write with the account it belongs to, and this is the one place in
        // the client that knows what that is - so it is also the place that
        // keeps them current, including through a logout, where it answers 0
        // and the stores go back to accepting anyone's rows. See
        // RecordEnvelope: 0 matches, it does not exclude.
        if (outgoingStore != null) { outgoingStore.bindAccount(id); }
        if (draftStore != null) { draftStore.bindAccount(id); }
        return id;
    }

    private long resolveAccountId()
    {
        // The one gate every cache write passes through. See accountActive.
        if (!accountActive) { return 0; }
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
            Cached cached = conversationCache.loadDialogs(
                    accountId, Dc.isTest());
            if (cached == null || cached.dialogs().length == 0) { return false; }
            resetDialogWindow();
            dialogs = cached.dialogs();
            // The cache is the whole list as far as this session can tell, and
            // there is no connection to ask for more over. Latched so scrolling
            // to the bottom of it does not fire a request per keypress at a
            // server we cannot reach; loadDialogs clears it on reconnect.
            dialogsExhausted = true;
            showDialogList();
            // Says how old, not just that it is cached. A reader who cannot
            // tell four-second-old from four-day-old text has no way to know
            // whether the last message in a chat is the last message in it.
            dialogList.setStatus(cachedLabel(cached) + ", offline", "stopped");
            return true;
        }
        catch (Throwable t)
        {
            Diag.warn("offline dialog cache failed: " + shortMessage(t));
            return false;
        }
    }

    private void cacheHistory(Peer peer, int thread, Message[] value)
    {
        long accountId = cacheAccountId();
        if (accountId == 0 || conversationCache == null) { return; }
        try
        {
            conversationCache.saveHistory(
                    accountId, Dc.isTest(), peer, thread, value);
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
        filterBox = new TextBox("Filter loaded chats", dialogFilter, 64,
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

    /** Restore points held before the oldest is forgotten. */
    private static final int MAX_DIALOG_RESTORE_POINTS = 128;

    /** Rows of a page the window does not already hold. */
    private int countNewDialogs(Dialog[] page)
    {
        if (page == null) { return 0; }
        int fresh = 0;
        for (int i = 0; i < page.length; i++)
        {
            if (page[i] != null && page[i].peer != null
                    && findDialog(page[i].peer) < 0)
            {
                fresh++;
            }
        }
        return fresh;
    }

    /**
     * Reset the window to the start of the list.
     *
     * Everything about where the window sits goes at once: a fresh first page
     * is row zero by definition, and a restore stack describing some other
     * list is worse than no stack at all.
     */
    private void resetDialogWindow()
    {
        dialogsAbove = 0;
        dialogOrderStale = false;
        dialogAbove = null;
        dialogAboveStack = new Dialog[0];
        dialogAboveDepth = 0;
        dialogTopLost = false;
        dialogsExhausted = false;
    }

    /**
     * Extend the window downwards, dropping as much off the top as it gains.
     *
     * The bounded merge cannot be used here: it truncates the tail, which is
     * the page that was just fetched. Merging unbounded and then dropping from
     * the front keeps what the reader is moving towards and gives up what they
     * have already gone past - and records, for each run given up, the one
     * dialog that brings it back.
     */
    private void appendDialogPage(Dialog[] page)
    {
        Dialog[] merged = PageMerge.dialogs(dialogs, page, Integer.MAX_VALUE);
        int cap = MemoryBudget.maxDialogs();
        int drop = merged.length - cap;
        if (drop > 0)
        {
            pushDialogRestorePoint(merged[drop - 1]);
            dialogsAbove += drop;
            merged = PageMerge.keepLast(merged, cap);
        }
        dialogs = merged;
    }

    /**
     * Extend the window upwards with a restored run, giving up the bottom.
     *
     * No restore point is recorded for what falls off the bottom: paging back
     * down needs no stack, because the last retained row is itself the offset
     * the next request is made from.
     */
    private void prependDialogPage(Dialog[] page, Dialog restoredFrom)
    {
        int before = dialogs.length;
        Dialog[] merged = PageMerge.dialogs(page, dialogs, Integer.MAX_VALUE);
        int gained = merged.length - before;
        dialogs = PageMerge.keepFirst(merged, MemoryBudget.maxDialogs());
        dialogsAbove -= gained;
        if (dialogsAbove < 0) { dialogsAbove = 0; }
        dialogAbove = restoredFrom;
        // Room reappeared below, so whatever was decided about the end of the
        // list was decided about a window that no longer exists.
        if (dialogs.length < merged.length) { dialogsExhausted = false; }
    }

    private void pushDialogRestorePoint(Dialog lastDropped)
    {
        if (dialogAboveDepth >= dialogAboveStack.length)
        {
            int grown = dialogAboveStack.length == 0
                    ? 8 : dialogAboveStack.length * 2;
            if (grown > MAX_DIALOG_RESTORE_POINTS)
            {
                grown = MAX_DIALOG_RESTORE_POINTS;
            }
            if (grown > dialogAboveStack.length)
            {
                Dialog[] bigger = new Dialog[grown];
                System.arraycopy(dialogAboveStack, 0, bigger, 0,
                        dialogAboveDepth);
                dialogAboveStack = bigger;
            }
            else
            {
                // Full. Forget the oldest, which is the way back to the top of
                // the list - so say so, and stop offering a scroll that would
                // land somewhere arbitrary. Refresh still returns to the top.
                System.arraycopy(dialogAboveStack, 1, dialogAboveStack, 0,
                        dialogAboveDepth - 1);
                dialogAboveDepth--;
                dialogTopLost = true;
            }
        }
        dialogAboveStack[dialogAboveDepth++] = dialogAbove;
        dialogAbove = lastDropped;
    }

    /** Whether there is a run above the window that can still be fetched. */
    private boolean canRestoreDialogs()
    {
        return dialogAbove != null && dialogAboveDepth > 0;
    }

    /**
     * Fetch another page when the reader is getting close to the bottom of what
     * is loaded.
     *
     * The chat-list twin of {@link #maybeLoadHistory}, and the same shape: an
     * in-flight guard, an exhausted latch, and a {@code worker.submit} that
     * returning false clears the flag so the next viewport event retries.
     *
     * One thing is different from the transcript: the margin is measured
     * against the <i>unfiltered</i> window, because a filter narrows what is
     * displayed and the bottom of three matches is not the bottom of anything.
     *
     * The other looks the same and is not. Both directions are fetched, but
     * {@code messages.getDialogs} pages downwards only, so going back up is not
     * a mirror of going down - it is a request made from the dialog that was
     * sitting above the window when the run was given up. That is what
     * {@link #dialogAboveStack} is for, and it is what makes dropping rows off
     * the top something other than a wall at the other end.
     *
     * Called from the dialog list's viewport callback, so it runs on the lcdui
     * thread and must not block: everything past the guard is a worker submit.
     */
    private void maybeLoadDialogs()
    {
        if (dialogList == null || dialogs.length == 0 || dialogPageInFlight
                || navigation.current() != dialogList)
        {
            return;
        }
        // Deliberately not gated on avatarWorker.isBusy(). It is busy for most
        // of any scroll - that is what it is for - so waiting on it would
        // starve the fetch exactly when the reader reaches an edge. The two are
        // separate workers over one multiplexed connection. Maintenance-lane
        // contention is harmless: submit() returns false, clears the latch and
        // the next viewport event retries.
        int margin = MemoryBudget.dialogPrefetchMargin();

        // Upwards first. A reader coming back up is retracing a path they have
        // already taken and expects it to still be there; running out of window
        // in that direction is the more surprising of the two.
        if (PageMerge.above(dialogs, dialogList.firstVisiblePeer()) < margin
                && !canRestoreDialogs() && dialogsAbove > 0)
        {
            // The stack is bounded, so a reader who went a long way down can
            // reach the top of the window with nothing left to walk back
            // through. Saying so, with the way out, beats a list that simply
            // stops moving.
            dialogList.setStatus("top of loaded - press Top of list",
                    updateLabel);
        }
        else if (canRestoreDialogs()
                && PageMerge.above(dialogs, dialogList.firstVisiblePeer()) < margin)
        {
            restoreDialogsAbove();
            return;
        }
        if (!dialogsExhausted
                && PageMerge.below(dialogs, dialogList.lastVisiblePeer()) < margin)
        {
            loadMoreDialogs(false);
        }
    }

    /**
     * Bring back the run of chats immediately above the window.
     *
     * Exactly one request, however far down the list the reader has gone,
     * because the offset it is made from was recorded when the run was
     * dropped rather than recomputed by paging from the top.
     */
    private void restoreDialogsAbove()
    {
        if (dialogList == null || dialogPageInFlight || !canRestoreDialogs())
        {
            return;
        }
        final Dialog from = dialogAboveStack[dialogAboveDepth - 1];
        final Peer selected = selectedDialogPeer();
        dialogPageInFlight = true;
        dialogList.setStatus("loading...", updateLabel);
        final AsyncScope.Token asked = scope.capture();
        boolean submitted = syncWorker.submit(new Worker.Task()
        {
            public String name() { return "messages.getDialogs/back"; }
            public Object run() throws Exception
            {
                // A null offset is the top of the list, which is where the
                // first run ever dropped came from.
                return from == null
                        ? telegram.getDialogs(MemoryBudget.dialogPageSize())
                        : telegram.getDialogsAfter(from,
                                MemoryBudget.dialogPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(final Object result)
            {
                // The latch belongs to the session, so a stale callback must
                // not clear it - and within a session it is always cleared by
                // whoever set it, which is what keeps paging from sticking.
                if (!asked.sameSession())
                {
                    dropStale("messages.getDialogs/back");
                    return;
                }
                dialogPageInFlight = false;
                if (!canRestoreDialogs()) { return; }
                DialogPage page = (DialogPage) result;
                if (page.size() == 0)
                {
                    // The run is gone from the server's list. Drop the restore
                    // point rather than asking for it again on every keypress.
                    dialogAboveDepth--;
                    dialogAbove = from;
                    return;
                }
                dialogAboveDepth--;
                if (page.total > dialogTotal) { dialogTotal = page.total; }
                prependDialogPage(page.dialogs, from);
                showDialogList(selected);
            }
            public void onFailure(final Throwable error)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getDialogs/back");
                    return;
                }
                dialogPageInFlight = false;
                if (dialogList != null)
                {
                    dialogList.setStatus(connectionLabel, updateLabel);
                }
                Diag.warn("dialog page back failed: " + shortMessage(error));
            }
        });
        if (!submitted)
        {
            dialogPageInFlight = false;
            dialogList.setStatus(connectionLabel, updateLabel);
        }
    }

    /**
     * One page of further chats.
     *
     * @param manual pressed by the user rather than provoked by scrolling. Only
     *               changes how loudly it reports itself: an automatic fetch
     *               that finds nothing has simply reached the end of the list,
     *               which is not news.
     */
    private void loadMoreDialogs(final boolean manual)
    {
        if (dialogList == null) { return; }
        if (dialogPageInFlight)
        {
            if (manual) { dialogList.setStatus("loading...", updateLabel); }
            return;
        }
        if (dialogsExhausted)
        {
            if (manual)
            {
                showAlert("No more chats.", AlertType.INFO, dialogList);
            }
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
        if (offset == null)
        {
            // Nothing but pinned chats: getDialogs is paged by the last
            // unpinned row's (date, id, peer) and there is no such row to
            // offset from. Asking again would re-fetch the same first page for
            // ever.
            dialogsExhausted = true;
            if (manual) { showAlert("No more chats.", AlertType.INFO, dialogList); }
            return;
        }
        final Dialog pageOffset = offset;
        final Peer selected = selectedDialogPeer();
        dialogPageInFlight = true;
        dialogList.setStatus("loading...", updateLabel);
        final AsyncScope.Token asked = scope.capture();
        Worker pageWorker = manual ? worker : syncWorker;
        boolean submitted = pageWorker.submit(new Worker.Task()
        {
            public String name() { return "messages.getDialogs/more"; }
            public Object run() throws Exception
            {
                return telegram.getDialogsAfter(pageOffset,
                        MemoryBudget.dialogPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(final Object result)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getDialogs/more");
                    return;
                }
                dialogPageInFlight = false;
                DialogPage page = (DialogPage) result;
                // Whether the page carried anything the window did not already
                // hold. Deliberately not "did the window grow": the window is a
                // fixed size, so once it is full its length stops moving while
                // its contents keep sliding down - and reading that as "no more
                // chats" would stop the list dead at exactly the point this
                // change exists to get past.
                int fresh = countNewDialogs(page.dialogs);
                if (page.total > dialogTotal) { dialogTotal = page.total; }
                appendDialogPage(page.dialogs);
                cacheDialogs(dialogs);
                showDialogList(selected);
                // Latched rather than retried: without this the viewport sits
                // against the end of a fully loaded list and asks for the same
                // empty page on every keypress.
                if (fresh == 0 || page.complete
                        || (dialogTotal > 0
                            && dialogsAbove + dialogs.length >= dialogTotal))
                {
                    dialogsExhausted = true;
                    if (manual && fresh == 0)
                    {
                        showAlert("No more chats.", AlertType.INFO, dialogList);
                    }
                }
            }
            public void onFailure(final Throwable error)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getDialogs/more");
                    return;
                }
                dialogPageInFlight = false;
                if (dialogList != null)
                {
                    dialogList.setStatus(connectionLabel, updateLabel);
                }
                if (manual)
                {
                    showAlertThen("Could not load more chats", error, dialogList);
                }
                else
                {
                    Diag.warn("dialog page failed: " + shortMessage(error));
                }
            }
        });
        if (!submitted)
        {
            // The worker drops rather than queues. Clearing the flag is the
            // whole retry: the next viewport event asks again, and by then
            // whatever was busy has usually finished.
            dialogPageInFlight = false;
            dialogList.setStatus(connectionLabel, updateLabel);
        }
    }

    // --------------------------------------------------------- forum topics

    /** Restore points held before the oldest is forgotten. */
    private static final int MAX_TOPIC_RESTORE_POINTS = 32;

    /**
     * Open a forum as its topic list.
     *
     * The screen between the chat list and the transcript: a topic row opens
     * an ordinary {@code ChatScreen} bound to {@code (forum, topic)}.
     */
    private void openForumTopics(Peer peer)
    {
        try
        {
            MemoryPressure.reserve(TOPIC_LIST_OPEN_BYTES);
            resetTopicWindow();
            topics = new ForumTopic[0];
            topicTotal = 0;
            topicScreen = createTopicListScreen(peer);
            telegram.setActivePeer(peer);
            pushScreen(topicScreen);
            loadTopics(peer);
        }
        catch (Throwable t)
        {
            openChatFailed(t);
        }
    }

    private TopicListScreen createTopicListScreen(Peer peer)
    {
        TopicListScreen screen = new TopicListScreen(currentTheme(), peer);
        screen.setStatus("loading topics...", updateLabel);
        screen.addCommand(cmdOpenTopic);
        screen.addCommand(cmdRefresh);
        screen.addCommand(cmdMoreTopics);
        screen.addCommand(cmdOutbox);
        screen.addCommand(cmdLog);
        screen.addCommand(cmdBack);
        screen.setCommandListener(this);
        screen.setActivationListener(new TopicListScreen.ActivationListener()
        {
            public void onTopicActivated(ForumTopic topic)
            {
                openTopic(topic);
            }
        });
        screen.setViewportListener(new TopicListScreen.ViewportListener()
        {
            public void onTopicViewportChanged()
            {
                maybeLoadTopics();
            }
        });
        return screen;
    }

    private void openSelectedTopic()
    {
        ForumTopic topic = topicScreen == null
                ? null : topicScreen.selectedTopic();
        if (topic != null) { openTopic(topic); }
    }

    /** Open one topic's transcript; the same shape as a flat chat open. */
    private void openTopic(ForumTopic row)
    {
        if (row == null || topicScreen == null) { return; }
        Peer peer = topicScreen.peer();
        try
        {
            MemoryPressure.reserve(CHAT_OPEN_BYTES);
            bindOpenPeer(peer, new ThreadInfo(row.id, row.closed,
                    row.readInboxMaxId, row.unreadCount, row.title));
            rebindReadMark(peer, row.id);
            historyPageInFlight = false;
            historyExhausted = false;
            historyForwardStalled = false;
            telegram.setActivePeer(peer);
            chatScreen = createChatScreen(peer);
            chatScreen.setThread(openThread);
            chatScreen.setTitle(row.title);
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

    /** Reset the topic window to the start of the list; see the dialog twin. */
    private void resetTopicWindow()
    {
        topicsAbove = 0;
        topicAbove = null;
        topicAboveStack = new ForumTopic[0];
        topicAboveDepth = 0;
        topicTopLost = false;
        topicsExhausted = false;
        topicPageInFlight = false;
    }

    /** Install the current window into the screen, local reads re-applied. */
    private void showTopicList(int selectedTopicId)
    {
        if (topicScreen == null) { return; }
        for (int i = 0; i < topics.length; i++)
        {
            localReads.applyTopic(topics[i], topicScreen.peer());
        }
        topicScreen.setTopics(topics, topicsAbove,
                Math.max(topicTotal, topicsAbove + topics.length),
                selectedTopicId);
        topicScreen.setStatus(connectionLabel, updateLabel);
    }

    /** The first page of a forum's topics, on the maintenance lane. */
    private void loadTopics(final Peer peer)
    {
        final TopicListScreen screen = topicScreen;
        if (screen == null || topicPageInFlight) { return; }
        topicPageInFlight = true;
        screen.setStatus("loading topics...", updateLabel);
        final AsyncScope.Token asked = scope.capture();
        boolean submitted = syncWorker.submit(new Worker.Task()
        {
            public String name() { return "messages.getForumTopics"; }
            public Object run() throws Exception
            {
                MemoryPressure.reserve(MemoryBudget.inflateOutputBytes() / 4);
                return telegram.getForumTopics(peer, null,
                        MemoryBudget.topicPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getForumTopics");
                    return;
                }
                topicPageInFlight = false;
                if (topicScreen != screen)
                {
                    dropStale("messages.getForumTopics");
                    return;
                }
                ForumTopicPage page = (ForumTopicPage) result;
                topics = page.topics;
                topicTotal = page.total;
                topicsExhausted = topics.length >= topicTotal;
                showTopicList(0);
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getForumTopics");
                    return;
                }
                topicPageInFlight = false;
                if (topicScreen != screen)
                {
                    dropStale("messages.getForumTopics");
                    return;
                }
                String detail = shortMessage(error);
                if (detail != null && detail.indexOf("FORUM") >= 0
                        && navigation.current() == screen)
                {
                    // The forum flag was stale - the group stopped being a
                    // forum while the row aged in a cache. Its transcript is
                    // what the server still answers, so show that instead.
                    topicScreen = null;
                    navigation.pop();
                    openChat(peer);
                    return;
                }
                if (topics.length == 0) { screen.setEmptyText("(offline)"); }
                screen.setStatus("failed: " + detail, updateLabel);
            }
        });
        if (!submitted)
        {
            // Never turn maintenance-lane contention into an alert; see the
            // history twin. One bounded waiter retries it.
            topicPageInFlight = false;
            pendingTopicsRefreshPeer = peer;
            initialRefreshRetry.schedule(400L);
            screen.setStatus("waiting for topics...", updateLabel);
        }
    }

    /** Reset to the top and fetch page one again. */
    private void refreshTopics()
    {
        if (topicScreen == null) { return; }
        resetTopicWindow();
        topics = new ForumTopic[0];
        topicTotal = 0;
        loadTopics(topicScreen.peer());
    }

    /**
     * Fetch or restore when the reader nears an edge of the topic window.
     *
     * The dialog list's shape one screen down: downward pages are asked for
     * by the last retained row, upward runs come back through the restore
     * stack recorded when they were dropped. Runs on the lcdui thread and
     * must not block.
     */
    private void maybeLoadTopics()
    {
        if (topicScreen == null || topics.length == 0 || topicPageInFlight
                || navigation.current() != topicScreen)
        {
            return;
        }
        int margin = MemoryBudget.topicPrefetchMargin();
        int above = topicScreen.firstVisibleIndex();
        if (above >= 0 && above < margin)
        {
            if (canRestoreTopics())
            {
                restoreTopicsAbove();
                return;
            }
            if (topicsAbove > 0)
            {
                topicScreen.setStatus("top of loaded - press Refresh",
                        updateLabel);
            }
        }
        if (!topicsExhausted && topics.length - 1
                - topicScreen.lastVisibleIndex() < margin)
        {
            loadMoreTopics(false);
        }
    }

    /**
     * One page of further topics.
     *
     * @param manual pressed rather than provoked by scrolling; only changes
     *               how loudly an empty page reports itself
     */
    private void loadMoreTopics(final boolean manual)
    {
        final TopicListScreen screen = topicScreen;
        if (screen == null) { return; }
        if (topicPageInFlight)
        {
            if (manual) { screen.setStatus("loading...", updateLabel); }
            return;
        }
        if (topicsExhausted)
        {
            if (manual)
            {
                showAlert("No more topics.", AlertType.INFO, screen);
            }
            return;
        }
        ForumTopic offset = null;
        for (int i = topics.length - 1; i >= 0; i--)
        {
            if (topics[i] != null) { offset = topics[i]; break; }
        }
        if (offset == null) { return; }
        final ForumTopic pageOffset = offset;
        final int selected = selectedTopicId();
        topicPageInFlight = true;
        screen.setStatus("loading...", updateLabel);
        final AsyncScope.Token asked = scope.capture();
        Worker pageWorker = manual ? worker : syncWorker;
        boolean submitted = pageWorker.submit(new Worker.Task()
        {
            public String name() { return "messages.getForumTopics/more"; }
            public Object run() throws Exception
            {
                return telegram.getForumTopics(screen.peer(), pageOffset,
                        MemoryBudget.topicPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getForumTopics/more");
                    return;
                }
                topicPageInFlight = false;
                if (topicScreen != screen)
                {
                    dropStale("messages.getForumTopics/more");
                    return;
                }
                ForumTopicPage page = (ForumTopicPage) result;
                int fresh = TopicWindow.countNew(topics, page.topics);
                if (page.total > topicTotal) { topicTotal = page.total; }
                appendTopicPage(page.topics);
                showTopicList(selected);
                // Latched rather than retried; see the dialog twin.
                if (fresh == 0 || (topicTotal > 0
                        && topicsAbove + topics.length >= topicTotal))
                {
                    topicsExhausted = true;
                    if (manual && fresh == 0)
                    {
                        showAlert("No more topics.", AlertType.INFO, screen);
                    }
                }
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getForumTopics/more");
                    return;
                }
                topicPageInFlight = false;
                if (topicScreen == screen)
                {
                    screen.setStatus(connectionLabel, updateLabel);
                }
                if (manual)
                {
                    showAlertThen("Could not load more topics", error, screen);
                }
                else
                {
                    Diag.warn("topic page failed: " + shortMessage(error));
                }
            }
        });
        if (!submitted)
        {
            // The worker drops rather than queues; the next viewport event
            // retries, which is the whole recovery.
            topicPageInFlight = false;
            screen.setStatus(connectionLabel, updateLabel);
        }
    }

    /** Bring back the run of topics immediately above the window. */
    private void restoreTopicsAbove()
    {
        final TopicListScreen screen = topicScreen;
        if (screen == null || topicPageInFlight || !canRestoreTopics())
        {
            return;
        }
        final ForumTopic from = topicAboveStack[topicAboveDepth - 1];
        final int selected = selectedTopicId();
        topicPageInFlight = true;
        screen.setStatus("loading...", updateLabel);
        final AsyncScope.Token asked = scope.capture();
        boolean submitted = syncWorker.submit(new Worker.Task()
        {
            public String name() { return "messages.getForumTopics/back"; }
            public Object run() throws Exception
            {
                // A null offset is the top of the list, which is where the
                // first run ever dropped came from.
                return telegram.getForumTopics(screen.peer(), from,
                        MemoryBudget.topicPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getForumTopics/back");
                    return;
                }
                topicPageInFlight = false;
                if (topicScreen != screen || !canRestoreTopics()) { return; }
                ForumTopicPage page = (ForumTopicPage) result;
                if (page.topics.length == 0)
                {
                    // The run is gone from the server's list. Drop the restore
                    // point rather than asking again on every keypress.
                    topicAboveDepth--;
                    topicAbove = from;
                    return;
                }
                topicAboveDepth--;
                if (page.total > topicTotal) { topicTotal = page.total; }
                prependTopicPage(page.topics, from);
                showTopicList(selected);
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getForumTopics/back");
                    return;
                }
                topicPageInFlight = false;
                if (topicScreen == screen)
                {
                    screen.setStatus(connectionLabel, updateLabel);
                }
                Diag.warn("topic page back failed: " + shortMessage(error));
            }
        });
        if (!submitted)
        {
            topicPageInFlight = false;
            screen.setStatus(connectionLabel, updateLabel);
        }
    }

    /** Extend the window downwards, dropping as much off the top as it gains. */
    private void appendTopicPage(ForumTopic[] page)
    {
        ForumTopic[] merged = TopicWindow.merge(topics, page);
        int cap = MemoryBudget.maxTopics();
        int drop = merged.length - cap;
        if (drop > 0)
        {
            pushTopicRestorePoint(merged[drop - 1]);
            topicsAbove += drop;
            merged = TopicWindow.keepLast(merged, cap);
        }
        topics = merged;
    }

    /** Extend the window upwards with a restored run, giving up the bottom. */
    private void prependTopicPage(ForumTopic[] page, ForumTopic restoredFrom)
    {
        int before = topics.length;
        ForumTopic[] merged = TopicWindow.merge(page, topics);
        int gained = merged.length - before;
        topics = TopicWindow.keepFirst(merged, MemoryBudget.maxTopics());
        topicsAbove -= gained;
        if (topicsAbove < 0) { topicsAbove = 0; }
        topicAbove = restoredFrom;
        // Room reappeared below, so whatever was decided about the end of the
        // list was decided about a window that no longer exists.
        if (topics.length < merged.length) { topicsExhausted = false; }
    }

    private void pushTopicRestorePoint(ForumTopic lastDropped)
    {
        if (topicAboveDepth >= topicAboveStack.length)
        {
            int grown = topicAboveStack.length == 0
                    ? 8 : topicAboveStack.length * 2;
            if (grown > MAX_TOPIC_RESTORE_POINTS)
            {
                grown = MAX_TOPIC_RESTORE_POINTS;
            }
            if (grown > topicAboveStack.length)
            {
                ForumTopic[] bigger = new ForumTopic[grown];
                System.arraycopy(topicAboveStack, 0, bigger, 0,
                        topicAboveDepth);
                topicAboveStack = bigger;
            }
            else
            {
                // Full. Forget the oldest, which is the way back to the top -
                // so say so and stop offering a scroll that would land
                // somewhere arbitrary. Refresh still returns there.
                System.arraycopy(topicAboveStack, 1, topicAboveStack, 0,
                        topicAboveDepth - 1);
                topicAboveDepth--;
                topicTopLost = true;
            }
        }
        topicAboveStack[topicAboveDepth++] = topicAbove;
        topicAbove = lastDropped;
    }

    /** Whether there is a run above the window that can still be fetched. */
    private boolean canRestoreTopics()
    {
        return topicAboveDepth > 0;
    }

    private int selectedTopicId()
    {
        ForumTopic selected = topicScreen == null
                ? null : topicScreen.selectedTopic();
        return selected == null ? 0 : selected.id;
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
        if (peer != null && peer.kind == Peer.CHANNEL && peer.forum)
        {
            openForumTopics(peer);
            return;
        }
        openChat(peer);
    }

    /** Open one flat transcript; forums route through the topic list. */
    private void openChat(Peer peer)
    {
        try
        {
            // Reclaim before committing, not after failing. The estimate is the
            // shape of what follows: a ChatScreen, the emoji sheet on first
            // paint, a wrapped transcript and the inflated history response.
            MemoryPressure.reserve(CHAT_OPEN_BYTES);
            bindOpenPeer(peer, null);
            rebindReadMark(peer, 0);
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
                // And with them the remembered failure level. That number is an
                // observation about a heap that no longer exists - a hundred and
                // fifty kilobytes of decoded avatars have just gone back - so
                // keeping it would leave the list refusing to try on the
                // strength of a measurement taken before the room was made.
                avatarHeapFloor = 0;
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
        final ChatScreen screen = new ChatScreen(currentTheme());
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
        screen.addCommand(cmdViewFullText);
        screen.addCommand(cmdEntityActions);
        screen.addCommand(cmdForward);
        screen.addCommand(cmdDeleteMessage);
        screen.addCommand(cmdProfile);
        screen.addCommand(cmdOlder);
        screen.addCommand(cmdJumpLatest);
        screen.addCommand(cmdFirstUnread);
        screen.addCommand(cmdFindMessages);
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
            public void onChatViewportChanged()
            {
                if (screen == chatScreen && screen.isAtEnd()
                        && unseenLiveMessages > 0)
                {
                    unseenLiveMessages = 0;
                    screen.setNewMessageCount(0);
                }
                updateFocusCommands(screen);
                maybeLoadHistory();
                scheduleVisibleThumbnails();
            }
        });
        return screen;
    }

    /**
     * Keep per-message commands out of the menu unless the focused row can
     * actually use them: Edit for an own editable text, Comments for a
     * channel post the server offers a thread on.
     */
    private void updateFocusCommands(ChatScreen screen)
    {
        if (screen == null) { return; }
        if (editCommandScreen != screen)
        {
            editCommandScreen = screen;
            editCommandVisible = false;
            commentsCommandVisible = false;
            screen.removeCommand(cmdEditMessage);
            screen.removeCommand(cmdOpenComments);
        }
        Message selected = null;
        int id = screen.focusedMessageId();
        Message[] shown = screen.messages();
        for (int i = 0; i < shown.length; i++)
        {
            if (shown[i] != null && shown[i].id == id)
            {
                selected = shown[i];
                break;
            }
        }
        boolean edit = selected != null && selected.canEditText();
        if (edit != editCommandVisible)
        {
            if (edit) { screen.addCommand(cmdEditMessage); }
            else { screen.removeCommand(cmdEditMessage); }
            editCommandVisible = edit;
        }
        boolean comments = selected != null && selected.hasComments
                && selected.id > 0;
        if (comments != commentsCommandVisible)
        {
            if (comments) { screen.addCommand(cmdOpenComments); }
            else { screen.removeCommand(cmdOpenComments); }
            commentsCommandVisible = comments;
        }
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
                Cached cached = accountId == 0 ? null
                        : conversationCache.loadHistory(
                                accountId, Dc.isTest(), peer, openThreadId());
                if (cached != null && cached.messages().length > 0)
                {
                    setOpenHistory(cached.messages());
                    chatScreen.setMessages(openHistory);
                    appendPendingForOpenPeer();
                    cachedHistoryLabel = cachedLabel(cached);
                    chatScreen.setStatus(cachedHistoryLabel + ", refreshing");
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
        final AsyncScope.Token asked = scope.capture(peer, openThreadId());
        // Once cached rows are painted this is a refresh, not a reason to
        // reject a reaction selected from those rows. Even without a cache the
        // request only populates the already-open screen, so it belongs on the
        // read/maintenance lane and can overlap a foreground keypress.
        boolean submitted = syncWorker.submit(new Worker.Task()
        {
            public String name() { return "messages.getHistory"; }

            public Object run() throws Exception
            {
                // Here rather than at the inflate itself. Inflating happens on
                // the MtClient reader thread, where a collect delays every
                // pending RPC and can trip a read timeout; this is the same
                // allocation one level up, on a thread that can afford to pause.
                MemoryPressure.reserve(MemoryBudget.inflateOutputBytes() / 4);
                return telegram.getHistory(peer, asked.thread(),
                        MemoryBudget.historyPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(final Object result)
            {
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("messages.getHistory");
                    return;
                }
                try
                {
                    setOpenHistory((Message[]) result);
                    cacheHistory(peer, asked.thread(), openHistory);
                    applyKnownReadState(openHistory, peer);
                    // setMessages word-wraps the window and is where the heap
                    // peaks; the guard is here rather than around the fetch for
                    // that reason.
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

            public void onFailure(Throwable error)
            {
                if (asked.sameChat(openPeer, openThreadId()))
                {
                    if (hasFallback && openHistory.length > 0)
                    {
                        chatScreen.setStatus(cachedHistoryLabel + ", offline");
                        Diag.warn("using cached history: " + shortMessage(error));
                    }
                    else
                    {
                        chatScreen.setStatus("failed: " + shortMessage(error));
                    }
                }
            }
        });
        if (submitted)
        {
            if (samePeer(pendingHistoryRefreshPeer, peer))
            {
                pendingHistoryRefreshPeer = null;
            }
            cancelInitialRefreshRetryIfIdle();
        }
        else
        {
            // Never turn maintenance-lane contention into a foreground alert:
            // that alert was precisely what made a reaction appear to fail.
            // Retain only the current chat and come back through one bounded
            // waiter; a later open overwrites it and the generation check above
            // discards the old one.
            pendingHistoryRefreshPeer = peer;
            initialRefreshRetry.schedule(400L);
            chatScreen.setStatus(hasFallback
                    ? (cachedHistoryLabel + ", refresh waiting")
                    : "waiting for history...");
        }
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
                && newestOpenId() < knownNewestId())
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
        final AsyncScope.Token asked = scope.capture(peer, openThreadId());
        final int atRequest = historyNavigation;
        // Viewport paging is speculative background work. Letting it occupy
        // the user's worker made a reaction (and even the next chat open)
        // bounce with "Finishing messages.getHistory/older first" immediately
        // after a chat was painted. A manual Older command is still a
        // foreground action; scrolling prefetch shares the housekeeping lane.
        Worker pageWorker = manual ? worker : syncWorker;
        boolean submitted = pageWorker.submit(new Worker.Task()
        {
            public String name() { return "messages.getHistory/older"; }
            public Object run() throws Exception
            {
                MemoryPressure.reserve(MemoryBudget.inflateOutputBytes() / 4);
                return telegram.getHistoryBefore(peer, asked.thread(),
                        offsetId, MemoryBudget.historyPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(final Object result)
            {
                // The latch is session-scoped, the content is chat-scoped. A
                // result from a previous account must not clear a latch the
                // current one is holding; a result for a chat the reader has
                // left has to release it, because nothing else will.
                if (!asked.sameSession())
                {
                    dropStale("messages.getHistory/older");
                    return;
                }
                historyPageInFlight = false;
                // Superseded by an explicit navigation - Jump to latest, or
                // First unread - that the reader asked for while this page was
                // on the wire. The page is not wrong, it is about where they no
                // longer are, and merging it would fold a page requested
                // against the old paging offsets into the new window. The latch
                // above is still cleared, because this path owns it.
                if (atRequest != historyNavigation)
                {
                    dropStale("messages.getHistory/older");
                    return;
                }
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("messages.getHistory/older");
                    return;
                }
                Message[] page = (Message[]) result;
                // Whether the page itself carried anything older than what was
                // held. Deliberately not "did the retained array change": the
                // retention window is a fixed size, so once it is full its
                // length stops moving while its contents keep sliding backwards
                // - and reading that as "no older messages" stopped paging five
                // pages into a channel that had thousands. Deliberately not
                // "did the retained oldest move" either, because a page fetched
                // while the viewport is elsewhere can be windowed straight back
                // out again without being news.
                boolean older = carriesOlderThan(page, oldestOpenId());
                mergeHistoryPage(page);
                cacheHistory(peer, asked.thread(), openHistory);
                applyKnownReadState(openHistory, peer);
                chatScreen.setMessages(openHistory);
                scheduleInlineThumbnails(peer);
                chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                if (!older)
                {
                    // Latched rather than retried: without this the viewport
                    // sits against the top of a fully loaded conversation and
                    // asks for the same empty page on every keypress.
                    historyExhausted = true;
                    if (manual)
                    {
                        showAlert("No older messages.", AlertType.INFO,
                                chatScreen);
                    }
                }
            }
            public void onFailure(final Throwable error)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getHistory/older");
                    return;
                }
                historyPageInFlight = false;
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("messages.getHistory/older");
                    return;
                }
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
        if (readMark != null && readMark.note(messages))
        {
            historyForwardStalled = false;
        }
    }

    /**
     * Bind the read mark to {@code peer}, discarding another conversation's.
     *
     * Every path that changes the open chat comes through here, including the
     * navigation one: {@link #restoreScreen} adopts whichever ChatScreen is
     * topmost on the stack, and the mark rises but never falls, so without this
     * a Back out of a forwarded message's source would leave that chat's
     * high-water mark pointing at the conversation underneath it.
     *
     * Reopening the same conversation keeps the mark. Every id in it is a real
     * server message in that chat, so it stays defensible, and dropping it would
     * only re-fetch what was already known.
     */
    private void rebindReadMark(Peer peer, int thread)
    {
        if (readMark != null && readMark.ownedBy(peer, thread)) { return; }
        readMark = ReadMark.forPeer(peer, thread);
        historyForwardStalled = false;
    }

    /**
     * Back to the newest page of this conversation, in one action.
     *
     * The window slides, so a reader who has paged a long way back has no route
     * forward except paging the same distance again - and if the retained
     * window has fallen off the newest end entirely, the forward page is
     * "stalled" and stops asking. This replaces the window rather than
     * extending it, which is the honest thing: what is on screen afterwards is
     * the newest page, not the newest page spliced onto wherever the reader
     * was.
     */
    private void jumpToLatest()
    {
        if (openPeer == null || chatScreen == null) { return; }
        final Peer peer = openPeer;
        // Deliberately not gated on historyPageInFlight. That latch belongs to
        // the scroll-driven paging, which on this heap prefetches every seven
        // messages - so on GPRS, where a page takes seconds, it is set most of
        // the time a reader is moving. Refusing an explicit action because a
        // background prefetch is running is what produced "already loading..."
        // on every press of Jump to latest, several presses running.
        //
        // The Worker is still serial, so this cannot overtake the request that
        // is on the wire. What it can do is take precedence over its *result*,
        // and be retried the moment the worker frees up.
        historyNavigation++;
        chatScreen.setStatus("loading the latest...");
        final AsyncScope.Token asked = scope.capture(peer, openThreadId());
        boolean submitted = worker.submit(new Worker.Task()
        {
            public String name() { return "messages.getHistory/latest"; }
            public Object run() throws Exception
            {
                MemoryPressure.reserve(MemoryBudget.inflateOutputBytes() / 4);
                return telegram.getHistory(peer, asked.thread(),
                        MemoryBudget.historyPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getHistory/latest");
                    return;
                }
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("messages.getHistory/latest");
                    return;
                }
                Message[] page = (Message[]) result;
                // Assigned, not merged. Merging would keep the old window and
                // leave the reader wherever they were, which is the opposite of
                // what they asked for; the pages they had can be fetched again
                // by scrolling back.
                setOpenHistory(page);
                cacheHistory(peer, asked.thread(), openHistory);
                applyKnownReadState(openHistory, peer);
                chatScreen.setMessages(openHistory);
                scheduleInlineThumbnails(peer);
                appendPendingForOpenPeer();
                chatScreen.scrollToEnd();
                unseenLiveMessages = 0;
                chatScreen.setNewMessageCount(0);
                // Both latches reset: this is the newest page, so there is
                // nothing newer to be stalled about, and older paging starts
                // again from here.
                historyForwardStalled = false;
                historyExhausted = false;
                chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                markRead();
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getHistory/latest");
                    return;
                }
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("messages.getHistory/latest");
                    return;
                }
                chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                showAlertThen("Could not load the latest messages", error,
                        chatScreen);
            }
        });
        if (!submitted)
        {
            // Refused because the prefetch has the worker. Queued rather than
            // handed back to the user as "press it again": they already did,
            // several times, and the answer was the same each time.
            pendingJumpPeer = peer;
            historyJumpRetry.schedule(HISTORY_JUMP_RETRY_MS);
            chatScreen.setStatus("waiting for the current page...");
        }
    }

    /** How long to wait before re-offering a jump the worker refused. */
    private static final long HISTORY_JUMP_RETRY_MS = 400L;

    /** The chat a queued Jump to latest was asked for, or null. */
    private Peer pendingJumpPeer;

    /**
     * One waiter for a Jump to latest the worker was too busy to take.
     *
     * The same shape as the snapshot refresh: try, and on a refusal come back
     * rather than dropping the action. Bounded to one waiter by DelayedWake,
     * and it re-checks the chat on the way in, because the reader can leave
     * while it waits.
     */
    private final DelayedWake historyJumpRetry = new DelayedWake("jump-latest",
            new DelayedWake.Wake()
    {
        public void onWake()
        {
            ui.post(new Runnable()
            {
                public void run()
                {
                    Peer want = pendingJumpPeer;
                    pendingJumpPeer = null;
                    if (want != null && samePeer(openPeer, want))
                    {
                        jumpToLatest();
                    }
                }
            });
        }
    });

    /**
     * The oldest incoming message this reader has not read, if there is one.
     *
     * Deliberately not {@code readInboxMaxId + 1}: the marker is a high-water
     * id and ids are not contiguous, so the id one past it usually does not
     * exist. A bounded page around the marker is fetched and
     * {@link UnreadPick} chooses out of what actually came back.
     */
    private void jumpToFirstUnread()
    {
        if (openPeer == null || chatScreen == null) { return; }
        final Peer peer = openPeer;
        // A thread carries its own cursor and badge; the dialog row aggregates
        // the whole peer and would send a topic reader to the wrong place.
        int at = openThread == null ? findDialog(peer) : -1;
        final int readMaxId = openThread != null
                ? openThread.readInboxMaxId
                : (at < 0 ? 0 : dialogs[at].readInboxMaxId);
        int unread = openThread != null
                ? openThread.unreadCount
                : (at < 0 ? 0 : dialogs[at].unreadCount);

        if (readMaxId <= 0)
        {
            chatScreen.setStatus("no read position known for this chat");
            return;
        }
        if (unread <= 0)
        {
            // The server says there is nothing, and it is the authority on
            // this. Answered locally rather than with a round trip.
            chatScreen.setStatus("no unread messages");
            return;
        }

        // Already held? Then no request is needed at all.
        int local = UnreadPick.firstUnread(openHistory, readMaxId);
        if (local != UnreadPick.NONE)
        {
            chatScreen.focusMessage(local);
            chatScreen.setStatus("first unread");
            return;
        }

        // Same reasoning as Jump to latest: an explicit action is not refused
        // because a background prefetch holds the latch.
        historyNavigation++;
        chatScreen.setStatus("finding the first unread...");
        final AsyncScope.Token asked = scope.capture(peer, openThreadId());
        boolean submitted = worker.submit(new Worker.Task()
        {
            public String name() { return "messages.getHistory/unread"; }
            public Object run() throws Exception
            {
                MemoryPressure.reserve(MemoryBudget.inflateOutputBytes() / 4);
                // Around the marker rather than after it: the marker itself may
                // have been deleted, and a page centred on it contains the
                // boundary in both directions so the earliest still-available
                // unread is in it.
                return telegram.getHistoryAround(peer, asked.thread(),
                        readMaxId, MemoryBudget.historyPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getHistory/unread");
                    return;
                }
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("messages.getHistory/unread");
                    return;
                }
                Message[] page = (Message[]) result;
                mergeHistoryPage(page);
                cacheHistory(peer, asked.thread(), openHistory);
                applyKnownReadState(openHistory, peer);
                chatScreen.setMessages(openHistory);
                scheduleInlineThumbnails(peer);

                int target = UnreadPick.firstUnread(openHistory, readMaxId);
                if (target == UnreadPick.NONE)
                {
                    chatScreen.setStatus(
                            UnreadPick.pageReachesMarker(page, readMaxId)
                                    ? "no unread messages in this part"
                                    : "could not locate the first unread");
                    return;
                }
                chatScreen.focusMessage(target);
                // "Earliest available" rather than "the first": if the message
                // at the boundary was deleted, this is the closest one that
                // still exists, and saying so is cheaper than pretending.
                chatScreen.setStatus("earliest unread available");
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getHistory/unread");
                    return;
                }
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("messages.getHistory/unread");
                    return;
                }
                chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                showAlertThen("Could not find the first unread", error,
                        chatScreen);
            }
        });
        if (!submitted)
        {
            chatScreen.setStatus(connectionLabel + "/" + updateLabel);
            showRefused("Not searched", "Press First unread again in a moment.",
                    chatScreen);
        }
    }

    /** How far the open conversation may be marked read, or 0. */
    private int knownNewestId()
    {
        return ReadMark.newestKnownIdFor(readMark, openPeer, openThreadId());
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
        final AsyncScope.Token asked = scope.capture(peer, openThreadId());
        final int atRequest = historyNavigation;
        boolean submitted = syncWorker.submit(new Worker.Task()
        {
            public String name() { return "messages.getHistory/newer"; }
            public Object run() throws Exception
            {
                MemoryPressure.reserve(MemoryBudget.inflateOutputBytes() / 4);
                return telegram.getHistoryAfter(peer, asked.thread(),
                        offsetId, MemoryBudget.historyPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(final Object result)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getHistory/newer");
                    return;
                }
                historyPageInFlight = false;
                // Superseded by an explicit navigation - Jump to latest, or
                // First unread - that the reader asked for while this page was
                // on the wire. The page is not wrong, it is about where they no
                // longer are, and merging it would fold a page requested
                // against the old paging offsets into the new window. The latch
                // above is still cleared, because this path owns it.
                if (atRequest != historyNavigation)
                {
                    dropStale("messages.getHistory/newer");
                    return;
                }
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("messages.getHistory/newer");
                    return;
                }
                Message[] page = (Message[]) result;
                int before = newestOpenId();
                mergeHistoryPage(page);
                cacheHistory(peer, asked.thread(), openHistory);
                applyKnownReadState(openHistory, peer);
                chatScreen.setMessages(openHistory);
                scheduleInlineThumbnails(peer);
                chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                // Nothing newer came back: the mark is ahead of what the server
                // will hand over, and asking again on every keypress would be a
                // request per scroll step.
                historyForwardStalled = newestOpenId() <= before;
            }
            public void onFailure(final Throwable error)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.getHistory/newer");
                    return;
                }
                historyPageInFlight = false;
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("messages.getHistory/newer");
                    return;
                }
                chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                Diag.warn("newer page failed: " + shortMessage(error));
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
    /**
     * Decode previews for whatever is on screen now, unless a batch is already
     * doing that.
     *
     * Scrolling has to be a trigger. Every other caller is a history landing,
     * and while the newest page was the only page anybody could see that was
     * the same thing; once a reader can scroll to messages that arrived pages
     * ago, those messages never get their previews decoded at all. Measured
     * before this existed: nine photos on screen, none of them decoded.
     *
     * Guarded by a running flag rather than by the generation counter, because
     * restarting on every keypress would cancel each batch a keypress after
     * starting it and nothing would ever finish.
     */
    private void scheduleVisibleThumbnails()
    {
        if (thumbnailsRunning || openPeer == null) { return; }
        scheduleInlineThumbnails(openPeer);
    }

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
        thumbnailsRunning = true;
        new Thread(new Runnable()
        {
            public void run()
            {
                try { decode(); }
                finally { thumbnailsRunning = false; }
            }

            private void decode()
            {
                for (int i = 0; i < candidateCount; i++)
                {
                    if (generation != thumbnailGeneration
                            || !samePeer(openPeer, peer)
                            || !appSettings.mediaPreviews)
                    {
                        // Worth a line. Silent cancellation is what a stuck
                        // inline preview looks like from the outside, and there
                        // was previously no way to tell it apart from a decode
                        // that failed or a message that carried no thumbnail.
                        Diag.info("thumbnails cancelled after " + i + " of "
                                + candidateCount);
                        return;
                    }
                    final Message message = candidates[i];
                    try
                    {
                        byte[] stripped = message.media.photo.stripped().bytes;

                        // Twelve of these are queued per chat open, and before
                        // this they were simply attempted: on a small heap that
                        // is a dozen decodes, a dozen OutOfMemoryErrors and a
                        // dozen caught Errors, one after another, for pictures
                        // nobody asked for. Refusing costs a placeholder.
                        //
                        // Bounded at level 1 - a thumbnail must not clear the
                        // thumbnails it is filling - and it stops the batch
                        // rather than skipping one: the next candidate needs the
                        // same memory this one could not get.
                        long cost = StrippedJpeg.decodeCost(stripped);
                        if (!MemoryPressure.reserve(cost, 1))
                        {
                            Diag.info("thumbnails stopped at " + i + " of "
                                    + candidateCount + ": needs " + (cost / 1024)
                                    + "k, headroom "
                                    + (MemoryPressure.headroom() / 1024) + "k");
                            return;
                        }
                        Image image = JpegDecoder.decode(new ByteArrayInputStream(
                                StrippedJpeg.restore(stripped)), null);
                        final Image thumbnail = ImageScaler.fitBox(image,
                                chatScreen.thumbnailWidth(),
                                chatScreen.thumbnailHeight());
                        ui.post(new Runnable()
                        {
                            public void run()
                            {
                                if (generation == thumbnailGeneration
                                        && samePeer(openPeer, peer)
                                        && appSettings.mediaPreviews)
                                {
                                    chatScreen.setThumbnail(message.id, thumbnail);
                                    Diag.info("thumbnail ok " + message.id);
                                }
                                else
                                {
                                    Diag.info("thumbnail dropped " + message.id);
                                }
                            }
                        });
                    }
                    catch (Throwable error)
                    {
                        Diag.warn("stripped thumbnail " + message.id + ": "
                                + shortMessage(error));
                        // A decode that failed for its own reasons - a payload
                        // this decoder does not support - says nothing about the
                        // next one, so the batch carries on. An
                        // OutOfMemoryError says everything about the next one:
                        // the admission check let this through and the VM
                        // disagreed, and eleven more attempts would each cost a
                        // decode and another caught Error to learn the same
                        // thing.
                        if (error instanceof OutOfMemoryError)
                        {
                            Diag.info("thumbnails stopped at " + i + " of "
                                    + candidateCount + ": the decode ran out of"
                                    + " memory at " + (MemoryPressure.headroom()
                                            / 1024) + "k headroom");
                            return;
                        }
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
        // requestMarkRead already refuses a missing chat and a non-id, which is
        // what knownNewestId() answers when no conversation is open or the mark
        // belongs to another one.
        requestMarkRead(openPeer, openThreadId(), knownNewestId());
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
            if (UpdateSync.DEGRADED.equals(batch.syncState)
                    && batch.retrySeconds >= 0)
            {
                updateLabel += " " + batch.retrySeconds + "s";
            }
        }

        boolean refresh = batch.fullRefresh;
        boolean following = chatScreen != null
                && display.getCurrent() == chatScreen && chatScreen.isAtEnd();
        int incomingForOpen = 0;
        for (int i = 0; i < batch.messages.length; i++)
        {
            Message incoming = batch.messages[i];
            if (incoming != null && !incoming.outgoing
                    && belongsToOpenThread(incoming)
                    && !hasOpenMessage(incoming.id))
            {
                incomingForOpen++;
            }
            if (!mergeMessage(batch.messages[i])) { refresh = true; }
            mergeTopicRow(batch.messages[i]);
        }
        for (int i = 0; i < batch.edits.length; i++)
        {
            applyEditedMessage(batch.edits[i]);
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
        if (topicScreen != null && display.getCurrent() == topicScreen)
        {
            showTopicList(selectedTopicId());
        }
        if (chatScreen != null && display.getCurrent() == chatScreen)
        {
            if (following) { unseenLiveMessages = 0; }
            else { unseenLiveMessages += incomingForOpen; }
            chatScreen.setMessages(openHistory);
            chatScreen.setNewMessageCount(unseenLiveMessages);
            scheduleInlineThumbnails(openPeer);
            appendPendingForOpenPeer();
            chatScreen.setStatus(connectionLabel + "/" + updateLabel);
        }
        cacheDialogs(dialogs);
        if (openPeer != null)
        {
            cacheHistory(openPeer, openThreadId(), openHistory);
        }
        if (refresh) { scheduleSnapshotRefresh(); }
    }

    /**
     * Whether a live message belongs in the open transcript.
     *
     * The peer alone stopped being the answer when a transcript became
     * (peer, thread): a forum's topics share one peer, and a message for
     * topic 401 merged into topic 400's screen is a message in a conversation
     * it was never sent to. Acknowledgement follows the same answer - a
     * transcript the reader is not looking at must not be marked read.
     */
    private boolean belongsToOpenThread(Message message)
    {
        if (message == null || !samePeer(openPeer, message.peer))
        {
            return false;
        }
        if (openThread == null)
        {
            // No thread open. A forum has no flat transcript in this client -
            // its peer being "open" means its topic list is - so only a
            // non-forum peer matches here.
            return !openPeer.forum;
        }
        return openPeer.forum
                ? message.threadRootIn(true) == openThread.rootId
                : message.inThread(openThread.rootId);
    }

    /**
     * Fold one live message into its topic-list row, when a forum's list is
     * held.
     *
     * A row outside the retained window is deliberately dropped - it comes
     * back on the next refresh, the same honesty the dialog window keeps. A
     * promotion is refused while the window has scrolled, for the reason
     * {@code promoteDialog} refuses one: the row's new place is not somewhere
     * this window can put it.
     */
    private void mergeTopicRow(Message message)
    {
        if (topicScreen == null || message == null || message.peer == null
                || !samePeer(topicScreen.peer(), message.peer))
        {
            return;
        }
        int root = message.threadRootIn(true);
        int at = TopicWindow.indexOf(topics, root);
        if (at < 0) { return; }
        ForumTopic row = topics[at];
        if (message.id > row.topMessageId)
        {
            row.topMessageId = message.id;
            row.lastMessage = Dialog.clipPreview(message.summaryText());
            row.lastDate = message.date;
            row.lastOutgoing = message.outgoing;
        }
        if (!message.outgoing)
        {
            if (openThread != null && openThread.rootId == root
                    && samePeer(openPeer, message.peer))
            {
                // The reader is inside this topic; the transcript path is
                // acknowledging it.
                row.unreadCount = 0;
                row.readInboxMaxId = Math.max(row.readInboxMaxId, message.id);
            }
            else
            {
                row.unreadCount++;
            }
        }
        promoteTopicRow(at);
    }

    /** Move a live row above the unpinned run, when the window is at the top. */
    private void promoteTopicRow(int index)
    {
        if (topicsAbove > 0) { return; }
        if (index < 0 || index >= topics.length || topics[index] == null
                || topics[index].pinned)
        {
            return;
        }
        int insert = 0;
        while (insert < topics.length && topics[insert] != null
                && topics[insert].pinned)
        {
            insert++;
        }
        if (index <= insert) { return; }
        ForumTopic moved = topics[index];
        System.arraycopy(topics, insert, topics, insert + 1, index - insert);
        topics[insert] = moved;
    }

    /** Merge one server message into the bounded dialog/history snapshots. */
    private boolean mergeMessage(Message message)
    {
        if (message == null || message.peer == null) { return false; }
        int dialogIndex = findDialog(message.peer);
        if (dialogIndex < 0)
        {
            // The chat list is a window, so a missing row means the reader has
            // scrolled past this conversation - not that they are not in it.
            // Everything below needs the row and is skipped; the transcript and
            // the acknowledgement do not, and skipping those left an open chat
            // silently unread for as long as it stayed open.
            if (belongsToOpenThread(message))
            {
                // The open peer rather than the update's, for the same reason
                // the retained row is preferred below: it is the one carrying an
                // access_hash, and the wire needs one.
                message.peer = openPeer;
                mergeOpenHistory(message);
                if (!message.outgoing)
                {
                    requestMarkRead(openPeer, openThreadId(), message.id);
                }
            }
            // Still false: the row itself is genuinely missing and a refresh is
            // the only thing that can bring it back.
            return false;
        }

        Dialog dialog = dialogs[dialogIndex];
        message.peer = dialog.peer;
        if (message.outgoing && message.id <= dialog.readOutboxMaxId)
        {
            message.read = true;
        }
        dialog.topMessageId = message.id;
        dialog.lastMessage = Dialog.clipPreview(message.summaryText());
        dialog.lastMessageOutgoing = message.outgoing;
        dialog.date = message.date;

        boolean opened = belongsToOpenThread(message);
        if (!message.outgoing)
        {
            if (opened)
            {
                dialog.unreadCount = 0;
                dialog.readInboxMaxId = Math.max(dialog.readInboxMaxId, message.id);
                requestMarkRead(message.peer, openThreadId(), message.id);
            }
            else
            {
                // Another topic of an open forum lands here too: its message
                // grows the aggregate row and is not acknowledged, because the
                // reader is not looking at it.
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

    /** Replace an edited message without treating it as new chronology. */
    private void applyEditedMessage(Message edited)
    {
        if (edited == null || edited.peer == null || edited.id <= 0) { return; }
        int dialogAt = findDialog(edited.peer);
        if (dialogAt >= 0)
        {
            edited.peer = dialogs[dialogAt].peer;
            if (dialogs[dialogAt].topMessageId == edited.id)
            {
                dialogs[dialogAt].lastMessage = Dialog.clipPreview(
                        edited.summaryText());
                dialogs[dialogAt].lastMessageOutgoing = edited.outgoing;
            }
        }
        if (samePeer(openPeer, edited.peer))
        {
            for (int i = 0; i < openHistory.length; i++)
            {
                if (openHistory[i] != null && openHistory[i].id == edited.id)
                {
                    openHistory[i] = edited;
                    return;
                }
            }
            return;
        }

        long accountId = cacheAccountId();
        if (accountId == 0 || conversationCache == null) { return; }
        try
        {
            // The record the edit belongs to: a forum message's topic, or the
            // peer's own transcript. A comment thread's record is not derivable
            // from one message and is simply refetched.
            int thread = edited.peer.forum ? edited.threadRootIn(true) : 0;
            Cached cached = conversationCache.loadHistory(
                    accountId, Dc.isTest(), edited.peer, thread);
            if (cached == null) { return; }
            Message[] messages = cached.messages();
            for (int i = 0; i < messages.length; i++)
            {
                if (messages[i] != null && messages[i].id == edited.id)
                {
                    messages[i] = edited;
                    conversationCache.saveHistory(accountId, Dc.isTest(),
                            edited.peer, thread, messages);
                    return;
                }
            }
        }
        catch (Throwable t)
        {
            Diag.warn("edited message cache update failed: " + shortMessage(t));
        }
    }

    private boolean applyReadState(ReadState read)
    {
        if (read == null || read.peer == null) { return false; }
        if (read.threadRootId > 0)
        {
            // A thread cursor names no dialog row, so a missing row is not a
            // reason to refresh the snapshot; the aggregate row gets its own
            // updateReadChannelInbox when the server moves it.
            applyThreadRead(read);
            return true;
        }
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
        // The server assigns absolutely here, so a count it has not yet
        // adjusted for our acknowledgement would undo it. LocalReads drops
        // itself as soon as the server's cursor reaches what we sent.
        localReads.apply(dialog);
        if (samePeer(openPeer, read.peer))
        {
            applyKnownReadState(openHistory, read.peer);
        }
        return true;
    }

    /**
     * A per-thread read cursor from another device, onto the topic row it
     * names. The update carries no unread count, so the badge is zeroed only
     * when the cursor demonstrably passed the newest message this client
     * knows the topic to have.
     */
    private void applyThreadRead(ReadState read)
    {
        if (topicScreen == null || !samePeer(topicScreen.peer(), read.peer))
        {
            return;
        }
        int at = TopicWindow.indexOf(topics, read.threadRootId);
        if (at < 0 || read.inboxMaxId < 0) { return; }
        ForumTopic row = topics[at];
        if (read.inboxMaxId > row.readInboxMaxId)
        {
            row.readInboxMaxId = read.inboxMaxId;
            if (row.topMessageId > 0
                    && row.readInboxMaxId >= row.topMessageId)
            {
                row.unreadCount = 0;
            }
        }
        localReads.applyTopic(row, topicScreen.peer());
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
        // Only when the window is showing the top of the list. Below that,
        // "promote" would mean moving the row to the head of a window that
        // starts at row four hundred - which is not where the chat has gone.
        // It has gone to row zero, outside what is held, so the honest thing
        // is to leave it where it is with its content updated and let the
        // ordering go stale until the reader comes back up or refreshes.
        if (dialogsAbove > 0) { dialogOrderStale = true; return; }
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

    /**
     * The same conversation, by kind and id. One definition, in
     * {@link AsyncScope}, because the guards there compare peers for the same
     * reason every call site here does.
     */
    private static boolean samePeer(Peer a, Peer b)
    {
        return AsyncScope.samePeer(a, b);
    }

    /**
     * Bind the open transcript, bumping the chat generation when it really
     * moved.
     *
     * The single assignment point for {@code openPeer} <em>and</em>
     * {@code openThread}, so that no path can change which conversation is
     * open - peer or thread - without the guards noticing.
     */
    private void bindOpenPeer(Peer next, ThreadInfo thread)
    {
        int nextThread = thread == null ? 0 : thread.rootId;
        if (!samePeer(openPeer, next) || nextThread != openThreadId())
        {
            unseenLiveMessages = 0;
            if (chatScreen != null) { chatScreen.setNewMessageCount(0); }
        }
        scope.chatChanged(next, nextThread);
        openPeer = next;
        openThread = next == null ? null : thread;
    }

    /** The open thread's root id, or 0 for a plain transcript. */
    private int openThreadId()
    {
        return openThread == null ? 0 : openThread.rootId;
    }

    private boolean hasOpenMessage(int messageId)
    {
        if (messageId <= 0) { return false; }
        for (int i = 0; i < openHistory.length; i++)
        {
            if (openHistory[i] != null && openHistory[i].id == messageId)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * A result that arrived for an account or a chat that is no longer current.
     *
     * Info rather than a warning, and never an alert: this is the expected
     * outcome of opening another chat while a page is in flight, not a fault.
     * Bounded by construction - Diag keeps a fixed-size ring.
     */
    private static void dropStale(String what)
    {
        Diag.info("stale result dropped: " + what);
    }

    /**
     * Queue a read acknowledgement for a dedicated worker.
     *
     * This cannot use the UI Worker: a history request may still be finishing
     * when a pushed message arrives, and dropping readHistory then would leave
     * the remote state stale. One thread drains the whole queue, so the second
     * producer joins it rather than starting another.
     */
    private void requestMarkRead(Peer peer, int thread, int maxId)
    {
        if (!readQueue.offer(peer, thread, maxId)) { return; }
        new Thread(new Runnable()
        {
            public void run()
            {
                while (readQueue.drainOne(readSink)) { }
            }
        }).start();
    }

    private static final class UpdateSnapshot
    {
        DialogPage dialogs;
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

    /**
     * "There is not enough heap for this", as distinct from every other reason
     * a background image can fail.
     *
     * A type rather than a message, because the failure path has to branch on
     * it: a download that failed is worth retrying on the next row, and a
     * refusal is not - the next row needs the same memory this one could not
     * get. Extends IOException so it travels the paths that already exist.
     */
    private static final class NoRoom extends java.io.IOException
    {
        NoRoom(String message) { super(message); }
    }

    private void scheduleSnapshotRefresh()
    {
        if (snapshotRefreshScheduled) { return; }
        snapshotRefreshScheduled = true;
        // Straight to the try. It has its own worker now, so there is nothing
        // to wait for and nothing to lose a race with: the old version spun a
        // thread on worker.isBusy() to be polite to the foreground, and the
        // version after that competed with it instead. Neither is needed once
        // the two are not the same worker.
        submitSnapshotRefresh();
    }

    /**
     * The submission half of {@link #scheduleSnapshotRefresh}, on the display
     * thread.
     *
     * {@code openPeer} is read here rather than on the waiting thread, so the
     * snapshot is taken against the chat the user is in when the request is
     * actually made, not the one they were in when the wait began.
     */
    private void submitSnapshotRefresh()
    {
        final Peer target = openPeer;
        final AsyncScope.Token asked = scope.capture(target, openThreadId());
        boolean submitted = syncWorker.submit(new Worker.Task()
        {
            public String name() { return "updates.snapshotRefresh"; }

            public Object run() throws Exception
            {
                UpdateSnapshot snapshot = new UpdateSnapshot();
                snapshot.peer = target;
                // One page, not the whole retained list. Asking for
                // dialogs.length was always a request the server would not
                // honour - it caps a getDialogs page well below the number a
                // reader can now scroll to - and the reply was then assigned,
                // so every update burst truncated the list back to one page
                // under whoever was reading it.
                snapshot.dialogs = telegram.getDialogs(
                        MemoryBudget.dialogPageSize());
                if (target != null)
                {
                    snapshot.history = telegram.getHistory(target,
                            asked.thread(),
                            Math.min(MemoryBudget.maxHistory(), Math.max(
                            MemoryBudget.historyPageSize(), openHistory.length)));
                }
                return snapshot;
            }
        }, new Worker.Callback()
        {
            public void onSuccess(final Object result)
            {
                // Session, not chat: the dialog half of this snapshot is worth
                // applying even if the reader has moved to another chat since
                // it was asked for. The history half keeps its own check below.
                if (!asked.sameSession())
                {
                    snapshotRefreshScheduled = false;
                    dropStale("updates.snapshotRefresh");
                    return;
                }
                UpdateSnapshot snapshot = (UpdateSnapshot) result;
                Peer selectedPeer = null;
                if (dialogList != null && display.getCurrent() == dialogList)
                {
                    selectedPeer = selectedDialogPeer();
                }
                // Merged, not assigned - the same correction the history half
                // of this snapshot already carries. This is the newest page;
                // assigning it would throw away every further page a reader had
                // scrolled to and drop them at the top again.
                if (snapshot.dialogs.total > dialogTotal)
                {
                    dialogTotal = snapshot.dialogs.total;
                }
                if (dialogsAbove > 0)
                {
                    // The window is not at the top, so the newest page is not
                    // adjacent to it and must not be spliced on. Content only,
                    // and the header has to admit the order is no longer the
                    // server order until the reader goes back to the top.
                    dialogs = PageMerge.restate(snapshot.dialogs.dialogs, dialogs);
                    dialogOrderStale = true;
                }
                else
                {
                    dialogs = PageMerge.refresh(snapshot.dialogs.dialogs, dialogs,
                            MemoryBudget.maxDialogs());
                }
                cacheDialogs(dialogs);
                if (snapshot.history != null
                        && samePeer(openPeer, snapshot.peer)
                        && asked.thread() == openThreadId())
                {
                    // Merged, not assigned. This is the newest page; assigning
                    // it would throw away every older page a reader had
                    // scrolled back to and drop them at the bottom again.
                    mergeHistoryPage(snapshot.history);
                    applyKnownReadState(openHistory, openPeer);
                    cacheHistory(openPeer, asked.thread(), openHistory);
                }
                snapshotRefreshScheduled = false;
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
            }

            public void onFailure(final Throwable error)
            {
                snapshotRefreshScheduled = false;
                if (!asked.sameSession())
                {
                    dropStale("updates.snapshotRefresh");
                    return;
                }
                Diag.warn("update snapshot refresh failed: "
                        + shortMessage(error));
            }
        });
        if (!submitted)
        {
            // History prefetch may be holding the shared maintenance lane. A
            // snapshot also refreshes dialogs and therefore cannot simply be
            // credited to that history-only request; retain one bounded retry.
            snapshotRefreshScheduled = false;
            snapshotRefreshRetry.schedule(400L);
        }
    }

    // ------------------------------------------------------------ sending

    /**
     * Show the compose screen for one prepared session.
     *
     * The session is what the screen is drawn from and what every later step -
     * the autosave, the send, the cleanup - reads. The screen itself carries no
     * state beyond the text: the {@code TextBox} is a single reused widget, so
     * anything left on it belongs to whoever opened it last.
     *
     * @param next a state from {@link ComposerState}, or null when the caller
     *             had no chat to open one for
     */
    private void openComposer(ComposerState next)
    {
        if (next == null)
        {
            Diag.warn("compose refused: no chat to compose for");
            return;
        }
        if (openThread != null && openThread.closed
                && next.ownedBy(openPeer, openThreadId()))
        {
            // Said before anything is typed, not after a send fails. The
            // server is still the authority - an admin can post into a closed
            // topic, and their send simply succeeds elsewhere.
            showAlert("This topic is closed; new messages can't be sent to it.",
                    AlertType.INFO, chatScreen);
            return;
        }
        if (composeBox == null)
        {
            composeBox = new TextBox("Message", "", 1000, TextField.ANY);
            composeBox.addCommand(cmdSend);
            composeBox.addCommand(cmdBack);
            composeBox.setCommandListener(this);
        }
        String draft = next.isEdit() ? next.originalText() : "";
        if (!next.isEdit())
        {
            try { draft = draftStore.load(next.peer(), next.threadRootId()); }
            catch (Throwable t) { Diag.error("draft load failed", t); }
        }
        // Published before the screen goes up, so the autosave thread cannot
        // see the box current with the previous session still installed.
        composer = next;
        lastSavedDraft = draft;
        composeBox.setString(draft);
        composeBox.setTitle(next.title());
        pushScreen(composeBox);
    }

    /**
     * Drop the composer session. Idempotent, and safe with no compose screen.
     *
     * This is the one cleanup: Back, a blank Send, an accepted enqueue, landing
     * on the chat list and logging out all end here, so there is no exit left
     * that can forget a field.
     */
    private void closeComposer()
    {
        composer = null;
        lastSavedDraft = "";
        if (composeBox != null)
        {
            composeBox.setString("");
            composeBox.setTitle("Message");
        }
    }

    /**
     * Leave the composer the way the user leaving it expects: keep the text,
     * then drop the session.
     *
     * Not used by the send path, which erases the draft instead - the message
     * is on its way, and a draft of it would come back as a second copy.
     */
    private void leaveComposer()
    {
        if (composer == null || !composer.isEdit()) { saveDraftNow(); }
        closeComposer();
    }

    // ------------------------------------------------------- message actions

    private Message selectedOpenMessage()
    {
        return chatScreen == null ? null
                : findOpenMessage(chatScreen.focusedMessageId());
    }

    private void showFullMessageText()
    {
        entityMessage = selectedOpenMessage();
        if (entityMessage == null) { return; }
        String text = entityMessage.text == null ? "" : entityMessage.text;
        MessageEntity[] actions = entityMessage.ensureEntities();
        fullTextBox = new TextBox("Message #" + entityMessage.id, text,
                Math.max(1, text.length()), TextField.ANY);
        if (actions.length > 0)
        {
            fullTextBox.addCommand(cmdEntityActions);
        }
        fullTextBox.addCommand(cmdBack);
        fullTextBox.setCommandListener(this);
        pushScreen(fullTextBox);
    }

    private void showEntityPicker()
    {
        if (navigation.current() == chatScreen)
        {
            entityMessage = selectedOpenMessage();
        }
        if (entityMessage == null) { return; }
        entityMessage.ensureEntities();
        MessageEntity[] candidates = new MessageEntity[entityMessage.entities.length];
        String[] labels = new String[entityMessage.entities.length];
        int count = 0;
        for (int i = 0; i < entityMessage.entities.length; i++)
        {
            MessageEntity entity = entityMessage.entities[i];
            ExternalAction.Target target = ExternalAction.target(
                    entity, entityMessage.text);
            if (target == null) { continue; }
            candidates[count] = entity;
            labels[count] = entityTypeName(entity.type) + ": " + target.label;
            count++;
        }
        entityItems = new MessageEntity[count];
        System.arraycopy(candidates, 0, entityItems, 0, count);
        entityList = new List("Message actions", List.IMPLICIT);
        for (int i = 0; i < count; i++) { entityList.append(labels[i], null); }
        if (count == 0) { entityList.append("(no supported actions)", null); }
        entityList.addCommand(cmdOpenEntity);
        entityList.addCommand(cmdBack);
        entityList.setCommandListener(this);
        pushScreen(entityList);
    }

    private static String entityTypeName(int type)
    {
        if (type == MessageEntity.MENTION
                || type == MessageEntity.MENTION_NAME) { return "User"; }
        if (type == MessageEntity.EMAIL) { return "Email"; }
        if (type == MessageEntity.PHONE) { return "Phone"; }
        return "Link";
    }

    private void selectEntityAction()
    {
        if (entityList == null || entityMessage == null) { return; }
        int index = entityList.getSelectedIndex();
        if (index < 0 || index >= entityItems.length) { return; }
        final ExternalAction.Target target = ExternalAction.target(
                entityItems[index], entityMessage.text);
        if (target == null) { return; }
        if (target.kind == ExternalAction.EXTERNAL)
        {
            pendingEntityTarget = target;
            entityConfirm = new Form("Open external target");
            entityConfirm.append("Shown text:\n" + target.label
                    + "\n\nActual target:\n" + target.value
                    + "\n\nThe phone may close TelegramJ2ME to continue.");
            entityConfirm.addCommand(cmdOpenExternal);
            entityConfirm.addCommand(cmdBack);
            entityConfirm.setCommandListener(this);
            pushScreen(entityConfirm);
            return;
        }

        final Peer source = openPeer;
        showBusy("Open user", "Resolving this Telegram user...");
        final AsyncScope.Token asked = scope.capture(source, openThreadId());
        boolean submitted = worker.submit(new Worker.Task()
        {
            public String name() { return "resolve message entity"; }
            public Object run() throws Exception
            {
                Peer peer;
                if (target.kind == ExternalAction.USERNAME)
                {
                    peer = telegram.resolveUsername(target.value);
                }
                else
                {
                    peer = telegram.peers().resolve(
                            new Peer(Peer.USER, target.userId));
                }
                if (peer == null || !telegram.peers().isAddressable(peer))
                {
                    throw new java.io.IOException(
                            "Telegram could not resolve this user");
                }
                return peer;
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object value)
            {
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("resolve message entity");
                    return;
                }
                popEntityScreens();
                openDialog((Peer) value);
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("resolve message entity");
                    return;
                }
                showAlertThen("Cannot open user", error, entityList);
            }
        });
        if (!submitted)
        {
            showRefused("User not opened", "Try Select again in a moment.",
                    entityList);
        }
    }

    private void popEntityScreens()
    {
        Displayable at = navigation.current();
        for (int i = 0; i < 3; i++)
        {
            if (at != entityConfirm && at != entityList && at != fullTextBox)
            {
                break;
            }
            Displayable next = navigation.pop();
            if (next == at) { break; }
            at = next;
        }
        restoreScreen(at);
    }

    private void performExternalAction()
    {
        if (pendingEntityTarget == null || entityConfirm == null) { return; }
        try
        {
            int outcome = ExternalAction.request(new ExternalAction.Launcher()
            {
                public boolean open(String uri) throws Exception
                {
                    return platformRequest(uri);
                }
            }, pendingEntityTarget.value);
            if (outcome == ExternalAction.EXIT_REQUIRED)
            {
                destroyApp(false);
                notifyDestroyed();
                return;
            }
            restoreScreen(navigation.pop());
            showAlert("The request was handed to the phone.", AlertType.INFO,
                    entityList);
        }
        catch (Throwable refused)
        {
            showAlertThen("Could not open target",
                    "The phone refused this action. The full target remains "
                    + "visible so you can check it.", entityConfirm);
        }
    }

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
        // The id, not the Message: the reply travels as an int, and holding the
        // whole message would keep a body alive past the history window that
        // evicted it.
        openComposer(ComposerState.reply(openPeer, openThreadId(), message.id));
    }

    private void beginEdit()
    {
        Message message = selectedOpenMessage();
        if (message == null || !message.canEditText())
        {
            showAlert("Only your sent text messages can be edited.",
                    AlertType.INFO, chatScreen);
            return;
        }
        openComposer(ComposerState.edit(openPeer, openThreadId(), message.id,
                message.text));
    }

    private void beginForward()
    {
        actionMessage = chatScreen == null ? null
                : findOpenMessage(chatScreen.focusedMessageId());
        actionPeer = openPeer;
        if (actionMessage == null || actionMessage.id <= 0) { return; }
        // Named for what it can actually offer. Until there is a server-side
        // peer search, a chat the reader has not scrolled to is not in here,
        // and a picker titled "Forward to" reads as the whole account.
        forwardList = new List("Forward to (loaded chats)", List.IMPLICIT);
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
        forwardList.addCommand(cmdFindChat);
        forwardList.addCommand(cmdBack);
        forwardList.setCommandListener(this);
        pushScreen(forwardList);
    }

    private void forwardToSelectedDialog()
    {
        int index = forwardList == null ? -1 : forwardList.getSelectedIndex();
        if (index < 0 || index >= forwardTargets.length) { return; }
        forwardMessageTo(forwardTargets[index]);
    }

    /**
     * Forward the pending message to {@code destination}.
     *
     * Split from the picker so a search result is a forward target on exactly
     * the same terms as a row of the loaded window - same request, same
     * staleness guard, same recovery.
     */
    /**
     * Leave the forward flow, however many screens deep it went.
     *
     * One pop was enough while the only way to pick a target was the loaded
     * list. A target found by searching sits one screen higher - results on top
     * of the picker - so a single pop landed the user back on the picker they
     * had just used, with the message already forwarded.
     *
     * Bounded rather than a while(true): {@code pop} stops at the root and
     * returns it, so a mistake here would spin instead of throwing.
     */
    private void popPastPicker()
    {
        Displayable at = navigation.current();
        for (int i = 0; i < 3; i++)
        {
            if (at != searchResults && at != forwardList) { break; }
            Displayable next = navigation.pop();
            if (next == at) { break; }
            at = next;
        }
        restoreScreen(at);
    }

    private void forwardMessageTo(final Peer destination)
    {
        if (destination == null || actionMessage == null || actionPeer == null)
        {
            return;
        }
        final Message message = actionMessage;
        final Peer source = actionPeer;
        showBusy("Forward", "Forwarding message...");
        final AsyncScope.Token asked = scope.capture(source, openThreadId());
        boolean submitted = worker.submit(new Worker.Task()
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
                // The forward happened on the server whatever the screen says.
                // Only the pop and the status line are dropped - and the status
                // line would otherwise be written into whichever chat the user
                // moved to, claiming a forward it knows nothing about.
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("messages.forwardMessages");
                    return;
                }
                popPastPicker();
                if (chatScreen != null)
                {
                    chatScreen.setStatus("forwarded / " + connectionLabel);
                }
            }
            public void onFailure(Throwable error)
            {
                if (!asked.sameSession())
                {
                    dropStale("messages.forwardMessages");
                    return;
                }
                showAlertThen("Could not forward message", error,
                        navigation.current());
            }
        });
        // The chooser stays up with the same destination selected, and
        // actionMessage/actionPeer are untouched: Forward here is one keypress.
        if (!submitted)
        {
            showRefused("Not forwarded", "Try Forward here again in a moment.");
        }
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
        final AsyncScope.Token asked = scope.capture(peer, openThreadId());
        boolean submitted = worker.submit(new Worker.Task()
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
                // The message is gone on the server either way. What the guard
                // stops is removeOpenMessage running against the wrong
                // transcript: message ids are unique per peer, not globally, so
                // deleting id 4711 from whichever chat happens to be open can
                // and did strip an unrelated message from it.
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("deleteMessages");
                    scheduleSnapshotRefresh();
                    return;
                }
                removeOpenMessage(messageId);
                restoreScreen(navigation.pop());
                if (chatScreen != null)
                {
                    chatScreen.setMessages(openHistory);
                    chatScreen.setStatus("deleted / " + connectionLabel);
                }
                scheduleSnapshotRefresh();
            }
            public void onFailure(Throwable error)
            {
                if (!asked.sameSession() || deleteConfirm == null)
                {
                    dropStale("deleteMessages");
                    return;
                }
                showAlertThen("Could not delete message", error, deleteConfirm);
            }
        });
        // Back to the confirmation, which still names the message and still
        // carries both scopes: nothing was deleted anywhere.
        if (!submitted)
        {
            showRefused("Not deleted", "Choose again in a moment.",
                    deleteConfirm);
        }
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

    /**
     * Mark the whole open conversation read.
     *
     * The id is a maximum over every trustworthy source rather than the first
     * one that answers. Both of the sources this used to consult are windows:
     * the retained dialog scrolls out of the chat list, and the retained history
     * slides off its newest end while reading backwards, which is exactly what
     * {@link ReadMark} is kept for.
     */
    private void markAllReadNow()
    {
        if (openPeer == null) { return; }
        // A thread has its own cursor; the dialog row aggregates the whole
        // forum, and its top message can be another topic's - marking a topic
        // read up to it would tell the server about messages of a thread the
        // reader never opened.
        int dialog = openThread == null ? findDialog(openPeer) : -1;
        int maxId = ReadMark.highest(knownNewestId(),
                dialog >= 0 ? dialogs[dialog].topMessageId : 0, openHistory);
        if (maxId <= 0) { return; }
        requestMarkRead(openPeer, openThreadId(), maxId);
        if (dialog >= 0)
        {
            dialogs[dialog].unreadCount = 0;
            dialogs[dialog].readInboxMaxId =
                    Math.max(dialogs[dialog].readInboxMaxId, maxId);
        }
        // Recorded outside the row as well as on it. The next snapshot refresh
        // replaces that object, and without this the badge comes back seconds
        // after the user cleared it.
        localReads.cleared(openPeer, openThreadId(), maxId);
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
        final AsyncScope.Token asked = scope.capture();
        boolean submitted = worker.submit(new Worker.Task()
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
                // A profile pushed after a logout would be the previous
                // account's contact, on top of the phone box.
                if (!asked.sameSession())
                {
                    dropStale("users.getFullUser");
                    return;
                }
                currentProfile = (Profile) result;
                rebuildProfileScreen();
                pushScreen(profileScreen);
            }
            public void onFailure(Throwable error)
            {
                if (!asked.sameSession())
                {
                    dropStale("users.getFullUser");
                    return;
                }
                showAlertThen("Could not load profile", error,
                        navigation.current());
            }
        });
        // No profile screen was pushed, so the stack still holds the chat or
        // the chat list this was opened from.
        if (!submitted)
        {
            showRefused("Profile not loaded", "Try Profile again in a moment.");
        }
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
        final AsyncScope.Token asked = scope.capture();
        boolean submitted = worker.submit(new Worker.Task()
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
                // The name is already changed on the server; only the screen
                // it would replace no longer belongs to this account.
                if (!asked.sameSession())
                {
                    dropStale("account.updateProfile");
                    return;
                }
                currentProfile = (Profile) result;
                rebuildProfileScreen();
                navigation.pop();
                replaceScreen(profileScreen);
            }
            public void onFailure(Throwable error)
            {
                if (!asked.sameSession() || editProfileForm == null)
                {
                    dropStale("account.updateProfile");
                    return;
                }
                showAlertThen("Could not update profile", error,
                        editProfileForm);
            }
        });
        // The editor is the same Form with the same three TextFields, so what
        // the user typed is still in them.
        if (!submitted)
        {
            showRefused("Profile not saved", "Press Save again in a moment.",
                    editProfileForm);
        }
    }

    private void showReactionPalette(int messageId)
    {
        Message message = findOpenMessage(messageId);
        if (message == null) { return; }
        reactionMessageId = messageId;
        // Opening a picker is a local transition.  It used to wait for
        // messages.getAvailableReactions and, for groups, getFullChat before
        // drawing anything.  On the C3-00 that made a twelve-item local menu
        // feel frozen for many seconds.  The server still validates the set on
        // send and REACTION_INVALID is handled below, so network policy can
        // never be a prerequisite for seeing or leaving this screen.
        reactionPalette = ReactionCatalog.EMOJI;
        reactionLabels = ReactionCatalog.LABELS;
        showReactionPaletteReady(message);
    }

    private void showReactionPaletteReady(Message message)
    {
        if (message == null) { return; }
        if (reactionScreen == null)
        {
            reactionScreen = new ReactionScreen(currentTheme());
            // Soft-key equivalents for the navigation cluster. The C3-00 never
            // delivers it to the MIDlet, so on that handset these three are the
            // only way to use the palette at all; everywhere else they are a
            // second route to what the d-pad already does.
            reactionScreen.addCommand(cmdSelectReaction);
            reactionScreen.addCommand(cmdReactionUp);
            reactionScreen.addCommand(cmdReactionDown);
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
        final TextScreen actorsScreen = reactionActorsScreen;
        reactionActorsPeer = peer;
        reactionActorsMessageId = message.id;
        // A user asked for remote data, so make the wait explicit and
        // cancellable with Back before trying to acquire a worker.  This push
        // was accidentally missing while the comment below claimed it existed:
        // the request then ran invisibly behind the still-active palette.
        pushScreen(actorsScreen);
        submitReactionActors(actorsScreen, peer, message.id);
    }

    private void retryReactionActors()
    {
        TextScreen screen = reactionActorsScreen;
        Peer peer = reactionActorsPeer;
        if (screen == null || peer == null
                || navigation.current() != screen
                || !samePeer(openPeer, peer))
        {
            return;
        }
        submitReactionActors(screen, peer, reactionActorsMessageId);
    }

    private void submitReactionActors(final TextScreen actorsScreen,
                                      final Peer peer, final int messageId)
    {
        if (actorsScreen == null || navigation.current() != actorsScreen
                || !samePeer(openPeer, peer))
        {
            return;
        }
        // The screen instance itself is part of the guard: a result must land
        // on the exact Loading screen it was requested for, or nowhere.
        actorsScreen.setLines(new String[] { "Loading reaction details..." });
        final AsyncScope.Token asked = scope.capture(peer, openThreadId());
        boolean submitted = syncWorker.submit(new Worker.Task()
        {
            public String name() { return "messages.getMessageReactionsList"; }
            public Object run() throws Exception
            {
                return telegram.getMessageReactions(peer, messageId, 100);
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (!asked.sameChat(openPeer, openThreadId())
                        || reactionActorsScreen != actorsScreen
                        || navigation.current() != actorsScreen)
                {
                    dropStale("messages.getMessageReactionsList");
                    return;
                }
                reactionActorsRetry.cancel();
                ReactionActorsPage page = (ReactionActorsPage) result;
                int extra = page.totalCount > page.actors.length ? 1 : 0;
                int empty = page.actors.length == 0 ? 1 : 0;
                String[] lines = new String[page.actors.length + extra + empty];
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
                    lines[page.actors.length] = "Showing "
                            + page.actors.length + " of " + page.totalCount;
                }
                if (empty != 0) { lines[0] = "No reaction details."; }
                actorsScreen.setLines(lines);
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameChat(openPeer, openThreadId())
                        || reactionActorsScreen != actorsScreen
                        || navigation.current() != actorsScreen)
                {
                    dropStale("messages.getMessageReactionsList");
                    return;
                }
                reactionActorsRetry.cancel();
                String detail = shortMessage(error);
                if (detail != null
                        && detail.indexOf("BROADCAST_FORBIDDEN") >= 0)
                {
                    actorsScreen.setLines(new String[] {
                            "Reaction details are unavailable in this chat."
                    });
                }
                else
                {
                    actorsScreen.setLines(new String[] {
                            "Could not load reaction details.",
                            detail == null ? "Network error" : detail,
                            "Press Back to return."
                    });
                }
            }
        });
        if (!submitted)
        {
            // Another automatic history/update request owns the maintenance
            // lane.  Keep the visible Loading screen and retry; never turn
            // background contention into a modal "Finishing ... first".
            actorsScreen.setLines(new String[] {
                    "Waiting for background sync...",
                    "Loading will continue automatically.",
                    "Press Back to return."
            });
            reactionActorsRetry.schedule(400L);
        }
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
        // Captured after the pop, so it names the chat this returns to. This
        // callback replaces the open chat outright - the widest transition in
        // the client - and doing that to a conversation the reader moved to in
        // the meantime is indistinguishable from the client opening a chat by
        // itself.
        final AsyncScope.Token asked = scope.capture(openPeer, openThreadId());
        boolean submitted = worker.submit(new Worker.Task()
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
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("open forwarded source");
                    return;
                }
                ForwardOpen result = (ForwardOpen) value;
                // The source opens as a plain transcript, thread-less even
                // when it is a forum: the forwarded message is what the reader
                // asked to see, and it is focused either way.
                bindOpenPeer(result.peer, null);
                rebindReadMark(openPeer, 0);
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
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("open forwarded source");
                    return;
                }
                returnChat.setStatus(connectionLabel + "/" + updateLabel);
                showAlertThen("Cannot open source", error, returnChat);
            }
        });
        // The reaction screen was already popped, so the chat is what is on
        // screen; only its status line still claims something is opening.
        if (!submitted)
        {
            returnChat.setStatus(connectionLabel + "/" + updateLabel);
            showRefused("Source not opened",
                    "Open Reactions and try View source again.", returnChat);
        }
    }

    private static final class ForwardOpen
    {
        Peer peer;
        int messageId;
        Message[] messages;
    }

    private static final class CommentsOpen
    {
        DiscussionInfo info;
        Message[] messages;
    }

    /**
     * Open the focused channel post's comment thread.
     *
     * The {@link #openForwardSource} shape: a foreground navigation the user
     * just pressed, so it takes the user's worker with a status line on the
     * chat it returns to - not the maintenance lane's Loading screen, which is
     * for work forced into the background. One task maps the post to its root
     * in the linked discussion group and fetches the first page, one worker
     * occupancy for both round trips.
     */
    private void openComments()
    {
        final Message post = selectedOpenMessage();
        if (post == null || !post.hasComments || post.id <= 0) { return; }
        final Peer channel = openPeer;
        final ChatScreen returnChat = chatScreen;
        if (channel == null || returnChat == null) { return; }
        returnChat.setStatus("opening comments...");
        final AsyncScope.Token asked = scope.capture(openPeer, openThreadId());
        boolean submitted = worker.submit(new Worker.Task()
        {
            public String name() { return "messages.getDiscussionMessage"; }
            public Object run() throws Exception
            {
                MemoryPressure.reserve(MemoryBudget.inflateOutputBytes() / 4);
                CommentsOpen result = new CommentsOpen();
                result.info = telegram.getDiscussionMessage(channel, post.id);
                result.messages = telegram.getHistory(
                        result.info.discussionPeer,
                        result.info.rootMessageId,
                        MemoryBudget.historyPageSize());
                return result;
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object value)
            {
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("open comments");
                    return;
                }
                CommentsOpen result = (CommentsOpen) value;
                DiscussionInfo info = result.info;
                bindOpenPeer(info.discussionPeer, new ThreadInfo(
                        info.rootMessageId, false, info.readInboxMaxId,
                        info.unreadCount, "Comments"));
                rebindReadMark(openPeer, openThreadId());
                historyPageInFlight = false;
                historyExhausted = false;
                historyForwardStalled = false;
                telegram.setActivePeer(openPeer);
                openHistory = new Message[0];
                setOpenHistory(result.messages);
                applyKnownReadState(openHistory, openPeer);
                chatScreen = createChatScreen(openPeer);
                chatScreen.setThread(openThread);
                chatScreen.setTitle("Comments (" + post.repliesCount + ")");
                chatScreen.resetMessages(openHistory);
                scheduleInlineThumbnails(openPeer);
                chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                pushScreen(chatScreen);
                markRead();
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("open comments");
                    return;
                }
                returnChat.setStatus(connectionLabel + "/" + updateLabel);
                showAlertThen("Cannot open comments", error, returnChat);
            }
        });
        if (!submitted)
        {
            returnChat.setStatus(connectionLabel + "/" + updateLabel);
            showRefused("Comments not opened",
                    "Try Comments again in a moment.", returnChat);
        }
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
        // Captured, not read from the field inside run(). The task body used to
        // read openPeer on the worker thread, so opening another chat while the
        // reaction was in flight sent it against the chat the user had moved
        // to - a reaction on a message that was never selected.
        final Peer peer = openPeer;
        final AsyncScope.Token asked = scope.capture(peer, openThreadId());
        boolean submitted = worker.submit(new Worker.Task()
        {
            public String name() { return "messages.sendReaction"; }
            public Object run() throws Exception
            {
                telegram.sendReactions(peer, message.id, reactions);
                return null;
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object ignored)
            {
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("messages.sendReaction");
                    return;
                }
                chatScreen.setStatus(connectionLabel + "/" + updateLabel);
            }

            public void onFailure(Throwable error)
            {
                if (!asked.sameChat(openPeer, openThreadId()))
                {
                    dropStale("messages.sendReaction");
                    return;
                }
                chatScreen.setStatus("reaction failed");
                String detail = shortMessage(error);
                if (detail != null
                        && detail.indexOf("REACTION_INVALID") >= 0)
                {
                    // Admin/global policy may have changed after the palette
                    // was opened. Force a fresh policy read next time.
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
        // Nothing local was changed - the reaction set is only ever applied from
        // the server's answer - so saying so and clearing the status is the
        // whole recovery. The palette is one keypress away.
        if (!submitted)
        {
            chatScreen.setStatus(connectionLabel + "/" + updateLabel);
            showRefused("Reaction not sent",
                    "Open Reactions and choose again in a moment.", chatScreen);
        }
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
                ui.post(new Runnable()
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
        boolean submitted = worker.submit(new Worker.Task()
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
                        ui.post(new Runnable()
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
                // The download token is the guard for this screen: it is
                // replaced on every open, cancelled on Back, and cancelled and
                // nulled by a logout. The null check is what the token cannot
                // say - the same logout takes the screen away too.
                if (token != photoToken || photoScreen == null)
                {
                    dropStale("upload.getFile/photo");
                    return;
                }
                cachedPhotoId = message.media.photo.id;
                cachedPhoto = (Image) result;
                photoScreen.setImage(cachedPhoto);
            }

            public void onFailure(Throwable error)
            {
                if (token != photoToken || token.isCancelled()
                        || photoScreen == null)
                {
                    dropStale("upload.getFile/photo");
                    return;
                }
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
        if (!submitted)
        {
            // The status line is the recovery here rather than an alert: the
            // photo screen already carries Retry, and photoReferenceExpired is
            // false, so Retry comes back through this method rather than
            // through the reference refresh.
            photoScreen.setStatus("busy with " + worker.busyWith()
                    + "; press Retry");
        }
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
        final AsyncScope.Token asked = scope.capture(peer, openThreadId());
        boolean submitted = worker.submit(new Worker.Task()
        {
            public String name() { return "refresh expired photo reference"; }
            public Object run() throws Exception
            {
                return telegram.getHistory(peer, asked.thread(),
                        MemoryBudget.historyPageSize());
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (!asked.sameChat(openPeer, openThreadId())
                        || photoScreen == null)
                {
                    dropStale("refresh expired photo reference");
                    return;
                }
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
                // The latch decides what Retry does next, so it is only worth
                // setting while there is still a photo screen to press it on.
                if (!asked.sameChat(openPeer, openThreadId())
                        || photoScreen == null)
                {
                    dropStale("refresh expired photo reference");
                    return;
                }
                photoReferenceExpired = true;
                photoScreen.setStatus("refresh failed: " + shortMessage(error));
            }
        });
        if (!submitted)
        {
            // The latch above was cleared before the submission, and it is what
            // decides which of the two things Retry does. Left false, the next
            // Retry would download against the reference that is known to have
            // expired instead of refreshing it.
            photoReferenceExpired = true;
            photoScreen.setStatus("reference expired; Retry refreshes it");
        }
    }

    private void sendComposed()
    {
        // One read of the session, held for the whole send. Everything the
        // message is - the chat, the reply id - comes from here rather than
        // from openPeer, which the navigation and a background callback both
        // move. The reference doubles as the session token below.
        final ComposerState session = composer;
        if (composeBox == null || session == null) { return; }

        final String text = composeBox.getString();
        if (text.trim().length() == 0)
        {
            if (session.isEdit())
            {
                showAlert("An edited message cannot be empty.",
                        AlertType.WARNING, composeBox);
                return;
            }
            // Send on an empty box is how a lot of people close a screen. It
            // has to mean exactly what Back means, cleanup included; it used to
            // be the one exit that left reply mode armed.
            leaveComposer();
            popComposer();
            return;
        }
        if (!session.ownedBy(openPeer, openThreadId()))
        {
            // Should be unreachable - the composer sits directly on top of its
            // own chat screen. It is checked because the consequence of being
            // wrong is a message delivered to a conversation the user was not
            // looking at when they wrote it.
            Diag.warn("compose peer no longer open; refusing to send");
            leaveComposer();
            popComposer();
            showAlertThen("Chat changed",
                    session.isEdit()
                    ? "That edit belonged to another chat, so it was not sent."
                    : "That message was written for another chat, so it was not"
                    + " sent. It was kept as a draft there.",
                    display.getCurrent());
            return;
        }

        if (session.isEdit())
        {
            sendEdited(session, text);
            return;
        }

        final Peer peer = session.peer();
        final int replyToMessageId = session.replyToMessageId();
        final int threadRootId = session.threadRootId();
        final AsyncScope.Token asked = scope.capture(peer, threadRootId);

        boolean submitted = worker.submit(new Worker.Task()
        {
            public String name() { return "outbox.enqueue"; }

            public Object run() throws Exception
            {
                return telegram.enqueueMessage(peer, text, replyToMessageId,
                        threadRootId);
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                // The durable half is already done: enqueueMessage put the row
                // in RMS and started the drain before this callback existed, so
                // the message is on its way whatever the screen does now. Only
                // the screen half is conditional.
                //
                // The draft is cleared against the captured conversation, so it
                // happens even if the reader has moved on - the message it was
                // a draft of has been sent, and leaving it would send a second
                // copy the next time that chat is opened.
                if (asked.sameSession())
                {
                    try { draftStore.save(peer, threadRootId, ""); }
                    catch (Throwable t) { Diag.error("draft clear failed", t); }
                }

                // The composer may have been closed and reopened while this was
                // in flight. Clearing then would erase a draft belonging to the
                // session now on screen and pop a screen nobody asked to leave.
                if (composer != session)
                {
                    dropStale("outbox.enqueue");
                    return;
                }

                closeComposer();
                popComposer();
                if (chatScreen != null
                        && asked.sameChat(openPeer, openThreadId()))
                {
                    chatScreen.appendLocal((replyToMessageId > 0
                            ? ("[reply to #" + replyToMessageId + "] ") : "")
                            + "[queued] " + text);
                    chatScreen.scrollToEnd();
                    chatScreen.setStatus(connectionLabel + "/" + updateLabel);
                }
            }

            public void onFailure(Throwable error)
            {
                if (composer != session || composeBox == null)
                {
                    // Nothing was queued and there is no box left to say so on.
                    // Loud in the log rather than silent: this is a message the
                    // user wrote and the client did not send.
                    Diag.warn("compose enqueue failed for a session that is no"
                            + " longer open: " + shortMessage(error));
                    return;
                }
                showAlertThen("Could not queue message", error, composeBox);
            }
        });

        if (!submitted)
        {
            // Nothing was touched, so the text and the reply target are still
            // there to press Send on again.
            showRefused("Not queued",
                    "Your message is still here - try Send again.", composeBox);
        }
    }

    private void sendEdited(final ComposerState session, final String text)
    {
        if (text.equals(session.originalText()))
        {
            closeComposer();
            popComposer();
            return;
        }
        final Peer peer = session.peer();
        final AsyncScope.Token asked = scope.capture(peer,
                session.threadRootId());
        boolean submitted = worker.submit(new Worker.Task()
        {
            public String name() { return "messages.editMessage"; }
            public Object run() throws Exception
            {
                telegram.editMessage(peer, session.editMessageId(), text);
                return null;
            }
        }, new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                if (composer != session)
                {
                    dropStale("messages.editMessage");
                    return;
                }
                closeComposer();
                popComposer();
                if (asked.sameChat(openPeer, openThreadId())
                        && chatScreen != null)
                {
                    chatScreen.setStatus("edit accepted / " + connectionLabel);
                }
            }

            public void onFailure(Throwable error)
            {
                if (composer != session || composeBox == null
                        || !asked.sameSession())
                {
                    dropStale("messages.editMessage");
                    return;
                }
                showAlertThen("Could not edit message", error, composeBox);
            }
        });
        if (!submitted)
        {
            showRefused("Not edited",
                    "Your text is still here - try Send again.", composeBox);
        }
    }

    /**
     * Return from the compose screen, if it is still the screen we are on.
     *
     * {@code ScreenStack.pop()} removes whatever is on top without asking what
     * it is, and the accepted-enqueue path runs after a round trip during which
     * Back may already have left. Popping then would take the chat screen with
     * it.
     */
    private void popComposer()
    {
        if (navigation.current() == composeBox)
        {
            restoreScreen(navigation.pop());
        }
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
                        && messages[i].peerId == openPeer.id
                        && messages[i].threadRootId == openThreadId())
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

    /**
     * Persist what is in the compose box against the chat it was written for.
     *
     * Keyed on the composer session, not on {@code openPeer}: this runs on the
     * autosave thread, and the chat that is open can change under it.
     *
     * The session is read once, and read again after the text, because a close
     * and reopen in between would otherwise file one chat's typing under
     * another chat's name. A blank box is written blank - which deletes the
     * record - only when the user cleared a draft themselves; {@code
     * lastSavedDraft} is set from the store at open, so an untouched box never
     * writes at all.
     */
    private void saveDraftNow()
    {
        final ComposerState session = composer;
        if (draftStore == null || composeBox == null || session == null
                || session.isEdit()) { return; }
        try
        {
            String text = composeBox.getString();
            if (composer != session) { return; }
            if (!text.equals(lastSavedDraft))
            {
                draftStore.save(session.peer(), session.threadRootId(), text);
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
        String[] storage = storageLines();

        Runtime rt = Runtime.getRuntime();
        String[] lines = new String[connection.length + ring.length + crash.length
                                    + budget.length + storage.length + 12];
        int at = 0;
        lines[at++] = "heapTotal=" + rt.totalMemory() + " heapFree=" + rt.freeMemory();
        lines[at++] = "";
        // Every other number in this report has to be read against the budget
        // profile the client was actually running, not the one it shipped with.
        lines[at++] = "-- memory budget --";
        System.arraycopy(budget, 0, lines, at, budget.length);
        at += budget.length;
        lines[at++] = "";
        lines[at++] = "-- storage --";
        System.arraycopy(storage, 0, lines, at, storage.length);
        at += storage.length;
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

    /**
     * Whether this suite's RMS is actually holding what was written to it.
     *
     * Record stores are scoped per MIDlet suite, so probe.jar's RMS results say
     * nothing about the messenger's - which is why "the login does not persist"
     * could not be investigated from the probe build and had to be guessed at.
     * The marker is read once at startup, before anything writes, and is the
     * only direct evidence that storage survives an exit on this handset.
     */
    private String[] storageLines()
    {
        String[] stores = RmsCheck.storageLines(new String[] {
            "tgkeys", "tgupdates", "tgdialogcache", "tghistorycache",
            "tgavatars", "tgoutbox", "tgdrafts", "tgcrash"
        });
        String[] out = new String[stores.length + 3];
        out[0] = "persistence marker: " + String.valueOf(startupMarker);
        System.arraycopy(stores, 0, out, 1, stores.length);
        String failures = store == null ? null : store.writeFailureSummary();
        out[out.length - 2] = "key store: "
                + (failures == null ? "no write failures" : failures);
        // Survives leaving the warning screen, so a partial erasure can still
        // be described after the user has navigated away from it.
        out[out.length - 1] = "last logout: "
                + (lastWipe == null ? "none this run" : lastWipe.describe());
        return out;
    }

    /**
     * The seeding version of the stored key for this account's data centre.
     *
     * Read from the record rather than from a connected session, so the answer
     * exists on the start screen before anything has connected and stays right
     * after a migration - {@code accountDc()} is kept current by the sign-in
     * path. Reads the version only; the key bytes are never decoded for this.
     */
    private int storedSeeding()
    {
        if (store == null || telegram == null) { return AuthKey.SEEDING_NONE; }
        return store.storedSeeding(telegram.accountDc(), Dc.isTest());
    }

    private String[] diagnosticLines()
    {
        String[] connection = connectionDiagnostics.lines();
        String[] pressure = MemoryPressure.lines();
        String[] lines = new String[connection.length + pressure.length + 13];
        System.arraycopy(connection, 0, lines, 0, connection.length);
        int at = connection.length;
        lines[at++] = "";
        // Permanent, and stated for every outcome rather than only the one that
        // carries a recommendation: "this key was generated by the current
        // path" is the answer a device report needs just as much.
        lines[at++] = "-- security --";
        lines[at++] = "auth key seeding: "
                + AuthKey.describeSeeding(storedSeeding());
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
            if (!connectAndCheck())
            {
                // The transport is already closed and the new settings are
                // already stored, so pressing Save again would compare equal to
                // itself and simply walk back - leaving the client disconnected
                // with no route to a connect. The start screen is the one that
                // offers Connect, and it is also the honest description of where
                // this left things.
                showStartScreen();
                showRefused("Settings saved, not connected",
                        "The connection was closed to apply them - press"
                        + " Connect when it finishes.");
            }
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
     *
     * "Check the network connection" is the right advice for most of what
     * reaches here and exactly the wrong advice for one of them. Driving the
     * client at 1195 KB of free heap produced a startup where the connection was
     * fine and everything after it - the dialog list, the RMS caches, the reader
     * thread - died with OutOfMemoryError; a screen telling that user to check
     * their signal sends them to look at the one thing that was working. The
     * heap figures are named instead, because on a handset with no console they
     * are the only evidence the user can pass on.
     */
    private void showRetryableError(String title, Throwable t)
    {
        Form form = new Form(title);
        form.append(shortMessage(t));
        Runtime rt = Runtime.getRuntime();
        form.append(outOfMemory(t)
                ? "\n\nThis phone ran out of memory rather than out of signal."
                        + " Closing other applications may help."
                        + "\n\nheapFree=" + rt.freeMemory()
                        + " of " + rt.totalMemory()
                : "\n\nCheck the network connection and try again.");
        form.addCommand(cmdRefresh);
        form.addCommand(cmdDiag);
        form.addCommand(cmdSettings);
        form.addCommand(cmdLog);
        form.addCommand(cmdBack);
        form.addCommand(cmdExit);
        form.setCommandListener(this);
        pushScreen(form);
    }

    /**
     * Was this failure the heap rather than the network?
     *
     * The class is checked first and the message second, because an
     * OutOfMemoryError raised on a worker is routinely wrapped: the RMS layer
     * reports it as an IOException whose text names it, and the reader thread
     * hands it on as a request failure. Both still mean "there was no memory",
     * and both should say so.
     */
    private static boolean outOfMemory(Throwable t)
    {
        if (t instanceof OutOfMemoryError) { return true; }
        String message = t == null ? null : t.getMessage();
        return message != null && message.indexOf("OutOfMemoryError") >= 0;
    }

    private void showAlert(String text, AlertType type, Displayable next)
    {
        Alert alert = new Alert("", text, null, type);
        alert.setTimeout(Alert.FOREVER);
        display.setCurrent(alert, next);
    }

    private void showAlertThen(String title, Throwable t, Displayable next)
    {
        showAlertThen(title, shortMessage(t), next);
    }

    /**
     * The same alert for a condition that is not an exception - a refusal the
     * user can act on, where a class name would say nothing.
     */
    private void showAlertThen(String title, String message, Displayable next)
    {
        Alert alert = new Alert(title, message, null, AlertType.ERROR);
        alert.setTimeout(Alert.FOREVER);
        display.setCurrent(alert, next);
    }

    /**
     * Say that an action was refused, and land the user on a screen they can
     * act from.
     *
     * {@link Worker} runs one operation at a time and answers false rather than
     * queueing, so a refusal is an ordinary consequence of pressing a key while
     * something else is on the wire - not a failure, and deliberately not routed
     * through the failure callback, which every caller uses to say the server
     * refused them.
     *
     * Only the wording and the landing are shared. What each caller has to undo
     * first - an in-flight flag, a status line, a screen it pushed itself, a
     * latch it cleared early - differs at every site, and folding those together
     * is how a flag ends up wrong in a path nobody looked at.
     *
     * @param title what did not happen, in the user's words
     * @param hint  what they can do about it
     */
    private void showRefused(String title, String hint)
    {
        // showBusy() sets the display without touching the navigation stack, so
        // the screen the action was started from is still the top of it, and
        // putting it back is the whole undo for a busy screen.
        Displayable back = navigation.current();
        showRefused(title, hint, back == null ? display.getCurrent() : back);
    }

    /** The same, for a caller that has already restored where it belongs. */
    private void showRefused(String title, String hint, Displayable next)
    {
        showAlertThen(title, "Finishing " + worker.busyWith() + " first. " + hint,
                next);
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
