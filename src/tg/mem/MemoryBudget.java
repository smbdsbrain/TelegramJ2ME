package tg.mem;

/**
 * Every size limit in the client, derived from a measured heap ceiling.
 *
 * <h3>Why this class exists</h3>
 * Until this class, each budget was a compile-time constant in the subsystem
 * that used it, and all of them were chosen against a single measurement:
 * roughly 5 MB of heap on an Alcatel OT-810D. A second handset happened to land
 * in the same class. Two devices agreeing is luck, not a design, and it runs out
 * on the first handset with a 1 MB heap. So the numbers move here and are
 * computed from what the device actually reported.
 *
 * <h3>Three kinds of number, deliberately not treated alike</h3>
 * <ul>
 *   <li><b>Rejection thresholds</b> on network-controlled lengths - the packet
 *       caps, the inflate output cap. Lowering one of these frees no memory:
 *       {@code Abridged} allocates the <i>declared</i> length and the cap only
 *       decides whether to throw first. Scaling them too far converts a
 *       survivable allocation into a guaranteed disconnect, so their floors come
 *       from what the protocol actually sends, not from arithmetic.</li>
 *   <li><b>Retention budgets</b> - caches and list caps. These genuinely return
 *       memory when scaled, so they scale proportionally.</li>
 *   <li><b>Transient single-allocation caps</b> - the photo pixel budget. This
 *       one sets a ceiling; whether a particular decode fits <i>right now</i> is
 *       {@link MemoryPressure}'s job, not this class's.</li>
 * </ul>
 *
 * <h3>Two states</h3>
 * Before {@link #init} is called every accessor returns the value that shipped
 * before this class existed. That is the whole safety story: a forgotten or
 * failed measurement degrades to the only configuration ever validated on
 * hardware, and {@code ProbeMidlet} - which never calls {@code init} - behaves
 * exactly as it did.
 *
 * <h3>Never larger than the reference</h3>
 * A measured ceiling above {@link #REFERENCE_HEAP} produces the reference values
 * unchanged, never bigger ones. No handset above 5 MB has been measured, so
 * growth would be speculation; more importantly, a handset that over-reports its
 * heap must not be able to talk the client into a buffer the VM cannot deliver.
 *
 * <h3>Why 4 MiB is the reference and not 5</h3>
 * The Samsung GT-C3592 measures {@code totalMemory = 5 242 860} - twenty bytes
 * below 5 MiB. With a 5 MiB reference and integer arithmetic that handset would
 * receive 199 dialogs, 119 messages of history and 307 198 pixels: every budget
 * one unit below today's value, on the exact hardware the no-regression rule was
 * written for. 4 MiB is clear of both measured handsets by 25%.
 *
 * <h3>No constants outside this class</h3>
 * The accessors are methods, not {@code public static final int} fields, and
 * that is load-bearing rather than stylistic. A {@code static final int} with a
 * constant initialiser is a <i>constant variable</i> (JLS 4.12.4): javac inlines
 * the literal at every use site, so turning one into a runtime value while
 * leaving the declaration shape alone would change nothing at all. A method
 * reading a non-final static cannot be folded, by javac or by ProGuard.
 */
public final class MemoryBudget
{
    // ------------------------------------------------------------- provenance

    /** No measurement; every budget is the value validated on hardware. */
    public static final int SOURCE_DEFAULT = 0;

    /** Read back from RMS, measured during some earlier launch. */
    public static final int SOURCE_STORED = 1;

    /** Measured by HeapProbe during this launch. */
    public static final int SOURCE_MEASURED = 2;

    // ---------------------------------------------------------- the reference

    /**
     * The heap the reference profile below is calibrated to. At or above this,
     * every accessor returns its reference value exactly.
     */
    private static final int REFERENCE_HEAP = 4 * 1024 * 1024;

    /**
     * Measured ceilings are rounded down to this, so a heap that reports a
     * slightly different figure from one launch to the next does not silently
     * change a cache size, and so the stored number is readable in a report.
     */
    private static final int GRAIN = 64 * 1024;

    /**
     * Below this the client is not expected to reach a signed-in state.
     *
     * Measured, not reasoned. A first cut put this at 1.5 MiB from arithmetic
     * about the protocol floors, and driving the client under a constrained
     * heap showed that to be exactly wrong: at a measured ceiling of 1536 KB
     * the connect task dies with an OutOfMemoryError before the dialog list,
     * while viable() cheerfully returned true and the start screen said
     * nothing. At 3584 KB everything works - sign-in, dialogs with avatars, a
     * chat, a full photo decode.
     *
     * Nothing between those two could be measured: the host JVM used for the
     * sweep resolves every -Xmx in that range to one or the other. So the
     * threshold sits between the proven failure and the proven success, closer
     * to the failure, because the cost of warning a handset that would have
     * worked is a sentence on one screen, and the cost of staying silent on one
     * that will not is a user who never learns why.
     */
    private static final int VIABLE_HEAP = 2 * 1024 * 1024;

    /*
     * Reference profile. These are the numbers that shipped before this class,
     * each paired with the floor below which shrinking it further breaks
     * something rather than saving something.
     */

    private static final int REF_PACKET          = 1024 * 1024;
    private static final int MIN_PACKET          = 256 * 1024;
    private static final int REF_INFLATE         = 2 * 1024 * 1024;
    private static final int MIN_INFLATE         = 192 * 1024;
    private static final int REF_PHOTO_BYTES     = 512 * 1024;
    private static final int MIN_PHOTO_BYTES     = 48 * 1024;
    private static final int REF_UPDATE_QUEUE    = 256 * 1024;
    private static final int MIN_UPDATE_QUEUE    = 32 * 1024;
    private static final int REF_HTTP_QUEUE      = 256 * 1024;
    private static final int MIN_HTTP_QUEUE      = 64 * 1024;

    /*
     * This is a window, not a limit on the chat list. It says how many rows are
     * held around the reader; it says nothing about how far they can scroll.
     *
     * It was 200 and it was a limit, which is to say a wall: the list was a
     * manual pager that stopped there and said so out loud. Raising it would
     * only have moved the wall - an account of 1690 chats was driven through
     * this code, and no share of a 4 MiB heap holds 1690 rows. So the list
     * fetches on scroll and drops what the reader has gone past, recording for
     * each dropped run the one dialog that brings it back. Memory then stops
     * depending on how far anybody scrolled, which is the only version of this
     * that works on a handset.
     *
     * Both halves of the number are measured. Dialog.PREVIEW_MAX gave a row a
     * fixed size for the first time - until then it held the whole of the last
     * message, up to 4096 characters, behind a row that draws one clipped line,
     * so no count of rows bounded anything. And a row then weighs 431 bytes:
     * the Dialog, the Peer it holds alive, a title and a preview at the cap,
     * weighed over two thousand of them in MicroEmulator's MIDP runtime.
     * Reported on every probe run as dialogBytes, so a change to either class
     * shows up as a changed number rather than as a stale comment.
     *
     * The size is three pages, and that is the whole derivation. A window has
     * to hold what is on screen, a prefetch margin at each end, and enough
     * slack that a page arriving does not immediately provoke the next one:
     * seven rows plus two twenties plus a page of room, rounded to the unit
     * fetches actually come in. It leaves about eleven screens of free movement
     * between requests.
     *
     * Deliberately not sized as a share of the heap. That was the first attempt
     * and it produced 500 - about 5%, which sounds modest and is seventy-one
     * screens of buffer against a margin that reasks after three. Coverage
     * stopped being this number's job the moment the list could be scrolled
     * past it, so the only question left is how much slack scrolling needs, and
     * the answer is a few pages rather than a few hundred rows.
     *
     * 120 rows is 52 KB, and being small matters here more than anywhere else
     * in this file: it is the one retained structure the client cannot give
     * back. MemoryRelief may be called from a worker thread, and trimming a
     * list a live screen has already laid out is exactly what its contract
     * forbids - so whatever this holds is held for the session, on a handset
     * where avatars start failing around 1.5 MB free.
     *
     * What a smaller window costs is round trips on the way back up, and only
     * there: going down, requests track pages scrolled whatever the window is.
     * Measured over 90 screens down and 110 back up on a 1690-chat account,
     * moving from 500 to 120 left the descent at 12 requests and took the
     * return from 5 to 15. Ten round trips against 163 KB that cannot be
     * reclaimed is not a close trade on a 2 MB handset.
     */
    private static final int REF_DIALOGS         = 120;
    private static final int MIN_DIALOGS         = 40;
    private static final int REF_DIALOG_PAGE     = 40;
    private static final int MIN_DIALOG_PAGE     = 10;
    private static final int REF_DIALOG_MARGIN   = 20;
    private static final int MIN_DIALOG_MARGIN   = 5;
    private static final int REF_HISTORY         = 120;
    private static final int MIN_HISTORY         = 20;
    private static final int REF_HISTORY_PAGE    = 30;
    private static final int MIN_HISTORY_PAGE    = 10;
    private static final int REF_WINDOW_SCREENS  = 3;
    private static final int MIN_WINDOW_SCREENS  = 1;
    private static final int REF_PREFETCH_MARGIN = 15;
    private static final int MIN_PREFETCH_MARGIN = 5;
    private static final int REF_PEERS           = 500;
    private static final int MIN_PEERS           = 64;
    private static final int REF_AVATARS         = 16;
    private static final int MIN_AVATARS         = 2;
    private static final int REF_THUMBNAILS      = 12;
    private static final int MIN_THUMBNAILS      = 2;
    private static final int REF_SCREEN_STACK    = 16;
    private static final int MIN_SCREEN_STACK    = 4;

    private static final int REF_PHOTO_PIXELS    = 307200;
    private static final int MIN_PHOTO_PIXELS    = 16384;

    /**
     * Bytes of live heap per decoded pixel at the peak of a JPEG decode.
     *
     * Not 4. The compressed bytes stay live for the whole decode; the
     * coefficient {@code short[]} is allocated per component and is still live
     * when the {@code int[]} framebuffer is allocated; and
     * {@code Image.createRGBImage} copies on top of that. Ten is the observed
     * shape of that sum, and it is what makes an honest refusal possible.
     */
    private static final int DECODE_BYTES_PER_PIXEL = 10;

    // --------------------------------------------------------- measured state

    /** Measured usable heap in bytes, or 0 when nothing has been measured. */
    private static int ceiling;

    /** Largest contiguous block the VM would hand out, or 0 if unknown. */
    private static int largestBlock;

    private static int source = SOURCE_DEFAULT;

    private MemoryBudget() { }

    // ------------------------------------------------------------- lifecycle

    /**
     * Install a measurement. Safe to call with nonsense: a negative, zero or
     * absurd ceiling leaves the reference profile in place rather than
     * producing a budget the device cannot honour.
     *
     * @param measuredCeiling usable heap in bytes
     * @param measuredBlock   largest single allocation in bytes, or 0 if unknown
     * @param provenance      one of the {@code SOURCE_*} constants
     */
    public static void init(long measuredCeiling, long measuredBlock, int provenance)
    {
        int c = clampToInt(measuredCeiling);
        int b = clampToInt(measuredBlock);

        c = (c / GRAIN) * GRAIN;
        b = (b / GRAIN) * GRAIN;

        if (c <= 0)
        {
            reset();
            return;
        }

        ceiling = c;
        largestBlock = b < 0 ? 0 : b;
        source = provenance;
    }

    /** Forget the measurement and return to the reference profile. */
    public static void reset()
    {
        ceiling = 0;
        largestBlock = 0;
        source = SOURCE_DEFAULT;
    }

    /** Measured heap in bytes, or 0 when no measurement is installed. */
    public static int ceiling() { return ceiling; }

    /** Largest measured single allocation in bytes, or 0 when unknown. */
    public static int largestBlock() { return largestBlock; }

    /** One of the {@code SOURCE_*} constants. Never enters arithmetic. */
    public static int source() { return source; }

    /**
     * Whether this handset has enough heap for the client to be expected to
     * work at all.
     *
     * This is advisory and must stay advisory. A handset that under-reports its
     * heap would otherwise be permanently refused, which is a worse failure than
     * letting the user try and see. An unmeasured device is assumed viable.
     */
    public static boolean viable()
    {
        return ceiling <= 0 || ceiling >= VIABLE_HEAP;
    }

    /** The measured floor, for a caller that wants to explain the warning. */
    public static int viableHeap() { return VIABLE_HEAP; }

    // ---------------------------------------------- rejection thresholds

    /** Largest MTProto packet any link will send or accept. */
    public static int packetBytes()
    {
        return blockCapped(scale(REF_PACKET, MIN_PACKET), MIN_PACKET);
    }

    /** Largest permitted output of one gzip/deflate expansion. */
    public static int inflateOutputBytes()
    {
        return blockCapped(scale(REF_INFLATE, MIN_INFLATE), MIN_INFLATE);
    }

    /** Largest compressed photo this device will select and download. */
    public static int photoCompressedBytes()
    {
        return blockCapped(scale(REF_PHOTO_BYTES, MIN_PHOTO_BYTES), MIN_PHOTO_BYTES);
    }

    /** Bytes of undelivered update bodies held before falling back to difference. */
    public static int updateQueueBytes()
    {
        return scale(REF_UPDATE_QUEUE, MIN_UPDATE_QUEUE);
    }

    /**
     * Bytes of buffered HTTP response bodies held for the reader.
     *
     * This replaces a fixed count of eight. Eight responses of up to a megabyte
     * each is eight megabytes of possible retention, which is more than the
     * entire heap of either handset ever measured.
     */
    public static int httpQueueBytes()
    {
        return scale(REF_HTTP_QUEUE, MIN_HTTP_QUEUE);
    }

    // ----------------------------------------------------- retention budgets

    /** Dialogs held around the reader. Not a limit on how far the list goes. */
    public static int maxDialogs() { return scale(REF_DIALOGS, MIN_DIALOGS); }

    /** Dialogs fetched per request. */
    public static int dialogPageSize() { return scale(REF_DIALOG_PAGE, MIN_DIALOG_PAGE); }

    /**
     * Dialogs below the viewport before the next page is requested.
     *
     * The chat-list twin of {@link #historyPrefetchMargin}, and a latency
     * budget for the same reason: on GPRS a {@code messages.getDialogs} round
     * trip is seconds, and the page has to arrive before the reader does rather
     * than while they watch. Half a page at the reference.
     */
    public static int dialogPrefetchMargin()
    {
        return scale(REF_DIALOG_MARGIN, MIN_DIALOG_MARGIN);
    }

    /** Messages held in memory for the open conversation. */
    public static int maxHistory() { return scale(REF_HISTORY, MIN_HISTORY); }

    /** Messages fetched per request. */
    public static int historyPageSize() { return scale(REF_HISTORY_PAGE, MIN_HISTORY_PAGE); }

    /**
     * Screens of wrapped transcript kept laid out either side of the viewport.
     *
     * This is the budget that makes chat memory proportional to the screen
     * rather than to how far back the user has read. A conversation screen holds
     * five parallel arrays keyed by display line plus one String per line, and
     * before windowing every message ever loaded was in them.
     *
     * Three rather than one because it is also the hysteresis: the window is
     * rebuilt when the viewport comes within a screen of an edge, so at three
     * screens either side a rebuild leaves two screens of scrolling before the
     * next one can be provoked. One screen either side would reflow on almost
     * every keypress.
     */
    public static int layoutWindowScreens()
    {
        return scale(REF_WINDOW_SCREENS, MIN_WINDOW_SCREENS);
    }

    /**
     * Messages of loaded history above the viewport before an older page is
     * requested.
     *
     * A latency budget rather than a memory one, and it lives here because no
     * size literal in this client lives anywhere else. Half a page at the
     * reference: on GPRS a {@code messages.getHistory} round trip is measured in
     * seconds, and the margin has to be wide enough that the request finishes
     * before the reader arrives rather than while they watch.
     */
    public static int historyPrefetchMargin()
    {
        return scale(REF_PREFETCH_MARGIN, MIN_PREFETCH_MARGIN);
    }

    /** Resolved users and chats kept for title and avatar lookup. */
    public static int peerCacheEntries() { return scale(REF_PEERS, MIN_PEERS); }

    /** Decoded dialog-list avatars kept in memory. */
    public static int avatarCacheEntries() { return scale(REF_AVATARS, MIN_AVATARS); }

    /** Decoded inline thumbnails kept per conversation screen. */
    public static int thumbnailCacheEntries() { return scale(REF_THUMBNAILS, MIN_THUMBNAILS); }

    /** Screens retained for Back navigation. */
    public static int screenStackDepth() { return scale(REF_SCREEN_STACK, MIN_SCREEN_STACK); }

    // ---------------------------------------------------- transient capacity

    /** Largest decoded image this device will attempt, in pixels. */
    public static int photoPixels()
    {
        int pixels = scale(REF_PHOTO_PIXELS, MIN_PHOTO_PIXELS);
        if (largestBlock > 0)
        {
            // The framebuffer is one int per pixel and is the largest single
            // allocation in the decoder, so half the largest block bounds it.
            int cap = largestBlock / (2 * 4);
            if (cap < MIN_PHOTO_PIXELS) { cap = MIN_PHOTO_PIXELS; }
            if (pixels > cap) { pixels = cap; }
        }
        return pixels;
    }

    /**
     * What a dialog-list avatar costs to decode, before one has been fetched.
     *
     * The avatar path has no size to work from: {@code AvatarRef} carries a
     * photo id and a data centre, the server picks the dimensions, and by the
     * time the JPEG header can be read the download has already happened. So the
     * question "is there room for an avatar at all" - asked on the UI thread
     * before anything is requested - needs a number from somewhere else.
     *
     * Measured, not assumed. Twelve avatars cached by a real account, read back
     * out of {@code tgavatars} and decoded: every one of them 160x160, 3 269 to
     * 16 129 compressed bytes. Telegram serves the small peer photo at that size
     * and it does not vary with our heap, so unlike the retention budgets this
     * one does not scale - it describes the server's object, not our appetite.
     * Reproduce with any {@code drive-emulator.ps1} scenario that loads a dialog
     * list; it reports {@code avatarSizes} at the end of the run.
     *
     * The compressed figure is the largest seen rather than the mean, on the
     * same principle as {@link #photoDecodeCost}: an over-estimate costs a
     * placeholder and an under-estimate costs the OutOfMemoryError this exists
     * to avoid.
     */
    public static long avatarDecodeCost()
    {
        return photoDecodeCost(AVATAR_EDGE, AVATAR_EDGE, AVATAR_COMPRESSED);
    }

    private static final int AVATAR_EDGE = 160;
    private static final int AVATAR_COMPRESSED = 16 * 1024;

    /**
     * Live bytes at the peak of decoding an image of this size.
     *
     * Used to refuse a decode that cannot fit rather than discovering it with an
     * OutOfMemoryError. Deliberately an over-estimate: refusing a photo that
     * would have fitted costs a picture, and the alternative costs the session.
     */
    public static long photoDecodeCost(int width, int height, int compressedBytes)
    {
        if (width < 0) { width = 0; }
        if (height < 0) { height = 0; }
        if (compressedBytes < 0) { compressedBytes = 0; }
        return (long) width * (long) height * DECODE_BYTES_PER_PIXEL
               + (long) compressedBytes;
    }

    // -------------------------------------------------------------- reporting

    /** Diagnostic lines. Contains no user data, so it is safe to upload. */
    public static String[] lines()
    {
        String[] out = new String[9];
        out[0] = "heapCeiling = " + ceiling
                 + (ceiling > 0 ? " (" + (ceiling / 1024) + " KB)" : " (unmeasured)");
        out[1] = "heapBlock = " + largestBlock;
        out[2] = "heapSource = " + sourceName(source);
        out[3] = "viable = " + viable();
        out[4] = "packet = " + packetBytes() + " inflate = " + inflateOutputBytes();
        out[5] = "photoBytes = " + photoCompressedBytes()
                 + " photoPixels = " + photoPixels();
        out[6] = "dialogs = " + maxDialogs() + "/" + dialogPageSize()
                 + " history = " + maxHistory() + "/" + historyPageSize();
        out[7] = "peers = " + peerCacheEntries()
                 + " avatars = " + avatarCacheEntries()
                 + " thumbs = " + thumbnailCacheEntries()
                 + " screens = " + screenStackDepth();
        out[8] = "chatWindow = " + layoutWindowScreens() + " screens"
                 + " prefetch = " + historyPrefetchMargin() + " messages"
                 + "/" + dialogPrefetchMargin() + " dialogs";
        return out;
    }

    public static String sourceName(int provenance)
    {
        if (provenance == SOURCE_STORED) { return "stored"; }
        if (provenance == SOURCE_MEASURED) { return "measured"; }
        return "default";
    }

    // --------------------------------------------------------------- internals

    /**
     * Scale a reference value by the measured ceiling, never above the
     * reference and never below the floor.
     */
    private static int scale(int reference, int floor)
    {
        int c = ceiling;
        if (c <= 0 || c >= REFERENCE_HEAP) { return reference; }

        // long: reference * ceiling overflows an int well inside the range a
        // handset can report, and init() is required to survive nonsense.
        long value = (long) reference * (long) c / REFERENCE_HEAP;
        if (value >= reference) { return reference; }
        if (value <= floor) { return floor; }
        return (int) value;
    }

    /**
     * Bound a byte budget by the largest block the VM actually handed out.
     *
     * Total free heap and largest contiguous block are different numbers, and
     * fragmentation is exactly how a handset that reports an honest ceiling
     * still refuses a single large buffer. This is what makes the measurement
     * survive being partly wrong.
     */
    private static int blockCapped(int value, int floor)
    {
        if (largestBlock <= 0) { return value; }
        int cap = largestBlock / 2;
        if (cap < floor) { cap = floor; }
        return value > cap ? cap : value;
    }

    private static int clampToInt(long value)
    {
        if (value < 0) { return 0; }
        if (value > Integer.MAX_VALUE) { return Integer.MAX_VALUE; }
        return (int) value;
    }
}
