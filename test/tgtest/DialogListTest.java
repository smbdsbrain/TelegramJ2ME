package tgtest;

import javax.microedition.lcdui.Canvas;

import org.microemu.device.DeviceFactory;

import tg.api.Dialog;
import tg.api.PageHash;
import tg.api.PageMerge;
import tg.api.Peer;
import tg.ui.DialogListScreen;
import tg.ui.Theme;

/**
 * Paging the chat list by scroll position.
 *
 * The chat list was the last manual pager in the client: chats arrived by
 * pressing More and stopped at two hundred with an alert. What replaces it is
 * decided by arithmetic that is invisible from a screenshot, so it is pinned
 * here:
 *
 *   - what is held is a window around the reader, so how far they have scrolled
 *     stops deciding how much memory the list costs. A 1690-chat account is the
 *     same 500 rows as a 60-chat one;
 *   - a run dropped off the top can be fetched back, in one request rather than
 *     by paging from the start. messages.getDialogs goes downwards only, so
 *     without that the window would be a wall at the other end;
 *   - the decision to fetch is measured against the whole window rather than
 *     against what a filter is showing, or a filter matching three chats near
 *     the top would ask for a page for ever;
 *   - a refresh lays the newest page over what is held instead of replacing it,
 *     and only when the window is at the top: below that the newest page is not
 *     adjacent to the window and splicing it on would hide a hole;
 *   - a chat promoted past the reader by an arriving message moves the rows
 *     around them, not the row they are on;
 *   - a dialog has a bounded size, which is what makes any cap on the count
 *     mean anything at all.
 */
public final class DialogListTest implements Test
{
    private static final int WIDTH = 240;
    private static final int HEIGHT = 320;

    public String name() { return "ui/dialog-list-paging"; }

    public void run()
    {
        DeviceFactory.setDevice(new UiTestDevice("dialog-list", WIDTH, HEIGHT));

        refreshPrefersTheHeadAndKeepsTheTail();
        appendingAPageStillAppends();
        theFetchMarginIgnoresTheFilter();
        aDialogHasABoundedSize();
        theAnchoredRowSurvivesAReorder();
        theBottomRowIsWhatDecidesTheFetch();
        theHashFoldsAsDocumented();
        theWindowIsBoundedWhicheverWayItMoves();
        aRestatedRefreshChangesContentAndNotOrder();
        theHeaderCountsPositionNotRetention();
    }

    // -------------------------------------------------------------- the window

    /**
     * The whole point. Scrolling a thousand rows must not retain a thousand
     * rows - if it does, a long chat list is still an OutOfMemoryError with
     * extra steps, and the cap that prevents it is still a wall.
     */
    private static void theWindowIsBoundedWhicheverWayItMoves()
    {
        final int cap = 100;
        final int page = 20;

        // Page all the way down a list of a thousand, a page at a time, the
        // way the client does.
        Dialog[] window = list(range(0, page));
        int above = 0;
        for (int fetched = page; fetched < 1000; fetched += page)
        {
            Dialog[] next = list(range(fetched, page));
            Dialog[] merged = PageMerge.dialogs(window, next, Integer.MAX_VALUE);
            int drop = merged.length - cap;
            if (drop > 0) { above += drop; }
            window = PageMerge.keepLast(merged, cap);
            Assert.isTrue("the window never exceeds the cap: " + window.length,
                    window.length <= cap);
        }

        Assert.equal("the window is full and no larger", cap, window.length);
        Assert.equal("and it sits where the reader is", 1000 - cap, above);
        Assert.equal("holding the last rows fetched",
                999, (int) window[window.length - 1].peer.id);
        Assert.equal("and not the first", 900, (int) window[0].peer.id);

        // Back up. A restored run goes on the front and the bottom is given up,
        // which needs no restore point: the last retained row is itself the
        // offset the next downward request is made from.
        Dialog[] restored = list(range(880, page));
        Dialog[] merged = PageMerge.dialogs(restored, window, Integer.MAX_VALUE);
        int gained = merged.length - window.length;
        window = PageMerge.keepFirst(merged, cap);
        above -= gained;

        Assert.equal("still bounded going the other way", cap, window.length);
        Assert.equal("the restored run is on the front",
                880, (int) window[0].peer.id);
        Assert.equal("the window start moved up by what was gained",
                880, above);
        Assert.equal("and the bottom was given up",
                979, (int) window[window.length - 1].peer.id);

        // A restored run the window already holds must not double the rows.
        Dialog[] overlap = PageMerge.dialogs(list(range(880, page)), window,
                Integer.MAX_VALUE);
        Assert.equal("re-restoring an overlapping run gains nothing",
                window.length, overlap.length);

        Assert.equal("keepLast of a short list is the list",
                3, PageMerge.keepLast(list(1, 2, 3), 10).length);
        Assert.equal("keepFirst of a short list is the list",
                3, PageMerge.keepFirst(list(1, 2, 3), 10).length);
        Assert.equal("a zero cap is empty, not negative",
                0, PageMerge.keepLast(list(1, 2, 3), 0).length);
        Assert.equal("null is not a crash",
                0, PageMerge.keepFirst(null, 10).length);
    }

    /**
     * Below the top of the list, a refresh may change what rows say and not
     * where they are.
     *
     * A window at row four hundred is not adjacent to the newest page. Laying
     * one over the other - which is right when the window starts at row zero -
     * would put row 400 directly beneath row 0 and read as a contiguous list
     * that quietly skips four hundred chats.
     */
    private static void aRestatedRefreshChangesContentAndNotOrder()
    {
        Dialog[] window = list(400, 401, 402, 403);
        Dialog[] fresh = list(1, 2, 402);
        fresh[2].unreadCount = 12;
        fresh[2].lastMessage = "newer";

        Dialog[] after = PageMerge.restate(fresh, window);

        Assert.equal("no row is added", 4, after.length);
        Assert.equal("order is untouched", 400, (int) after[0].peer.id);
        Assert.equal("order is untouched", 403, (int) after[3].peer.id);
        Assert.equal("the shared row takes the fresher content",
                12, after[2].unreadCount);
        Assert.equal("including its preview", "newer", after[2].lastMessage);
        Assert.equal("rows the page did not mention are left alone",
                "message 400", after[0].lastMessage);

        Assert.isTrue("null is not a crash",
                PageMerge.restate(null, window) == window);
    }

    /**
     * The header says where the reader is, not how much is held.
     *
     * Retained-against-total was both, back when the list was everything
     * loaded. With a window it would sit at "500/1690" whether the reader was
     * at row 500 or row 1500, which is the one thing a scroll position is for.
     */
    private static void theHeaderCountsPositionNotRetention()
    {
        ExposedList screen = new ExposedList();
        Dialog[] window = list(range(900, 40));
        screen.setDialogs(window, 900, 1690, null);

        Assert.equal("the window start is remembered", 900, screen.windowStart());
        Assert.equal("the top row is the first visible peer",
                900, screen.firstVisiblePeer().id);
        Assert.equal("nothing is above it inside the window",
                0, PageMerge.above(window, screen.firstVisiblePeer()));

        for (int i = 0; i < 5; i++) { screen.press(Canvas.KEY_NUM8); }
        Assert.equal("moving down moves the absolute position too",
                905, screen.windowStart() + screen.selectedIndex());
        Assert.equal("rows above the viewport are counted within the window",
                screen.topIndex(),
                PageMerge.above(window, screen.firstVisiblePeer()));
    }

    // ------------------------------------------------------------- merging

    /**
     * A refresh is the newest page laid over the retained list.
     *
     * {@code PageMerge.dialogs} gives position to the first array and content to
     * the second, which is right for appending at the bottom and backwards
     * here. Both directions are checked in one place so the difference is
     * impossible to read as an accident.
     */
    private static void refreshPrefersTheHeadAndKeepsTheTail()
    {
        Dialog[] retained = list(1, 2, 3, 4, 5, 6);
        retained[2].unreadCount = 0;

        // The server has since promoted 3 to the top and it has unread mail.
        Dialog[] head = list(3, 1, 2);
        head[0].unreadCount = 7;
        head[0].lastMessage = "fresher";

        Dialog[] merged = PageMerge.refresh(head, retained, 100);

        Assert.equal("nothing is lost and nothing is duplicated",
                6, merged.length);
        Assert.equal("the head decides the order", 3, (int) merged[0].peer.id);
        Assert.equal("the head decides the content", 7, merged[0].unreadCount);
        Assert.equal("the head's preview wins", "fresher", merged[0].lastMessage);
        Assert.equal("then the rest of the head", 1, (int) merged[1].peer.id);
        Assert.equal("then the rest of the head", 2, (int) merged[2].peer.id);
        Assert.equal("then the retained tail, in order",
                4, (int) merged[3].peer.id);
        Assert.equal("then the retained tail, in order",
                6, (int) merged[5].peer.id);

        // The point of the whole method: a reader who paged to 6 keeps 6.
        Assert.equal("a refresh does not truncate the list a reader scrolled to",
                6, PageMerge.refresh(list(1, 2), retained, 100).length);

        Dialog[] capped = PageMerge.refresh(head, retained, 4);
        Assert.equal("the cap is honoured", 4, capped.length);
        Assert.equal("and it drops the tail, not the head",
                3, (int) capped[0].peer.id);

        Assert.equal("an empty head is the retained list",
                6, PageMerge.refresh(new Dialog[0], retained, 100).length);
        Assert.equal("null is not a crash",
                0, PageMerge.refresh(null, null, 100).length);
    }

    /** The append path is unchanged; this is the guard on saying so. */
    private static void appendingAPageStillAppends()
    {
        Dialog[] held = list(1, 2, 3);
        Dialog[] page = list(3, 4, 5);
        page[0].unreadCount = 9;

        Dialog[] merged = PageMerge.dialogs(held, page, 100);
        Assert.equal("the page is appended", 5, merged.length);
        Assert.equal("the held list keeps its order", 1, (int) merged[0].peer.id);
        Assert.equal("the page follows it", 4, (int) merged[3].peer.id);
        Assert.equal("a duplicate keeps its position",
                3, (int) merged[2].peer.id);
        Assert.equal("but takes the fresher content", 9, merged[2].unreadCount);
    }

    // -------------------------------------------------------- the decision

    /**
     * The margin is measured against the list, not against the matches.
     *
     * This is the one the issue warns about. Under a filter the reader is at the
     * bottom of what is displayed almost immediately, and a fetch trigger that
     * believed the filtered array would ask for a page on every keypress for as
     * long as the filter was set.
     */
    private static void theFetchMarginIgnoresTheFilter()
    {
        Dialog[] all = list(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        for (int i = 0; i < all.length; i++)
        {
            all[i].peer.title = i < 3 ? ("match " + i) : ("other " + i);
        }

        Dialog[] shown = PageMerge.filter(all, "match");
        Assert.equal("the filter narrows the display", 3, shown.length);

        Peer bottomOfMatches = shown[shown.length - 1].peer;
        Assert.equal("the bottom of three matches is not the bottom of ten",
                7, PageMerge.below(all, bottomOfMatches));

        Assert.equal("the bottom of the list is the bottom of the list",
                0, PageMerge.below(all, all[all.length - 1].peer));
        Assert.equal("one from the end", 1, PageMerge.below(all, all[8].peer));

        // A peer that is not in the list at all - a filter matching nothing, or
        // a row that vanished mid-update. The answer that asks for nothing.
        Assert.equal("an unplaceable row does not provoke a fetch",
                all.length, PageMerge.below(all, new Peer(Peer.USER, 999)));
        Assert.equal("nor does an empty screen",
                all.length, PageMerge.below(all, null));
    }

    // ------------------------------------------------------------ the cost

    /**
     * A dialog costs the same whatever arrived in it.
     *
     * Until the preview was clipped at ingest, {@code lastMessage} held the
     * whole of the last message - up to four thousand characters - behind a row
     * that draws one clipped line. That is why nothing in this client could say
     * what a dialog cost, and why a cap on the count of them bounded nothing.
     */
    private static void aDialogHasABoundedSize()
    {
        StringBuffer huge = new StringBuffer();
        for (int i = 0; i < 4096; i++) { huge.append('x'); }

        String clipped = Dialog.clipPreview(huge.toString());
        Assert.equal("a long message is clipped at ingest",
                Dialog.PREVIEW_MAX, clipped.length());

        Assert.equal("a short one is left alone",
                "hello", Dialog.clipPreview("hello"));
        Assert.equal("null is empty, not a crash", "", Dialog.clipPreview(null));
        Assert.equal("a second paragraph is not retained either",
                "first", Dialog.clipPreview("first\nsecond"));

        Dialog d = new Dialog();
        d.peer = new Peer(Peer.USER, 1);
        d.lastMessage = clipped;
        Assert.equal("preview() is unchanged for text that fits under the clip",
                clipped, d.preview());
        d.lastMessageOutgoing = true;
        Assert.isTrue("and still prefixes an outgoing one",
                d.preview().startsWith("You: "));
    }

    // ---------------------------------------------------------- the screen

    /**
     * A chat promoted past the reader moves the rows around them.
     *
     * Selection following the peer was already true; holding the row at the
     * same offset within the viewport is what this adds. Without it a
     * promotion from below shifts every row by one and the reader's place
     * slides a line at a time while somebody else is typing.
     */
    private static void theAnchoredRowSurvivesAReorder()
    {
        ExposedList screen = new ExposedList();
        Dialog[] all = list(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                16, 17, 18, 19, 20);
        screen.setDialogs(all, 0, all.length, null);

        // Walk down until the selection is off the first screen, so there is a
        // scroll offset to preserve at all.
        for (int i = 0; i < 12; i++) { screen.press(Canvas.KEY_NUM8); }
        Peer anchor = screen.selectedPeer();
        int offsetBefore = screen.selectedIndex() - screen.topIndex();
        Assert.isTrue("the reader is scrolled, not at the top",
                screen.topIndex() > 0);

        // A message arrives in chat 18, below the reader, and it goes to the top.
        Dialog[] reordered = promote(all, 17);
        screen.setDialogs(reordered, 0, reordered.length, anchor);

        Assert.equal("the anchored chat is still selected",
                anchor.id, screen.selectedPeer().id);
        Assert.equal("and still sits at the same place on the screen",
                offsetBefore, screen.selectedIndex() - screen.topIndex());

        // A reset - no anchor - starts from the top rather than preserving a
        // position that means nothing any more.
        screen.setDialogs(list(1, 2, 3), 0, 3, null);
        Assert.equal("a reset does not preserve an offset", 0, screen.topIndex());
    }

    /**
     * The bottom row is what the fetch decision is made from.
     *
     * The screen reports it rather than the caller deriving it from
     * {@code visiblePeers}, so that a hole in the list cannot read as an empty
     * screen and stop paging.
     */
    private static void theBottomRowIsWhatDecidesTheFetch()
    {
        ExposedList screen = new ExposedList();
        Assert.isTrue("an empty list has no bottom row",
                screen.lastVisiblePeer() == null);

        Dialog[] all = list(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                16, 17, 18, 19, 20);
        screen.setDialogs(all, 0, 312, null);

        Peer[] shown = screen.visiblePeers();
        Assert.isTrue("something is on screen", shown.length > 0);
        Assert.equal("the bottom row is the last visible peer",
                shown[shown.length - 1].id, screen.lastVisiblePeer().id);
        Assert.equal("which is what the margin is measured from",
                all.length - shown.length,
                PageMerge.below(all, screen.lastVisiblePeer()));

        // At the end of the list there is nothing below, which is what latches
        // the fetch off rather than provoking one more empty page.
        for (int i = 0; i < all.length; i++) { screen.press(Canvas.KEY_NUM8); }
        Assert.equal("paged to the end, nothing is below",
                0, PageMerge.below(all, screen.lastVisiblePeer()));

        Assert.equal("the header counts against the server's total, not its own",
                312, screen.totalCount());
        Assert.equal("and reports what it holds separately",
                20, screen.dialogCount());
    }

    // -------------------------------------------------------------- the hash

    /**
     * The fold is the documented one, character for character.
     *
     * <a href="https://core.telegram.org/api/offsets#hash-generation">api/offsets</a>:
     *
     * <pre>
     *   hash = 0
     *   for id in ids:
     *       hash = hash ^ (hash &gt;&gt; 21)
     *       hash = hash ^ (hash &lt;&lt; 35)
     *       hash = hash ^ (hash &gt;&gt; 4)
     *       hash = hash + id
     * </pre>
     *
     * Reimplemented here rather than compared against a stored constant: a
     * golden number would agree with a transcription error just as happily as
     * with the algorithm, and the shifts are the whole of what can go wrong.
     */
    private static void theHashFoldsAsDocumented()
    {
        long[] ids = { 1, 2, 3, 500, -7, 0, 1234567890123L, Long.MIN_VALUE };
        long expected = 0;
        for (int i = 0; i < ids.length; i++)
        {
            expected = expected ^ (expected >>> 21);
            expected = expected ^ (expected << 35);
            expected = expected ^ (expected >>> 4);
            expected = expected + ids[i];
        }
        Assert.equal("the fold matches the documented pseudocode",
                expected, PageHash.fold(ids));

        Assert.equal("an empty list hashes to 0 - which asks for everything",
                0, PageHash.fold(new long[0]));
        Assert.equal("so does null", 0, PageHash.fold(null));

        // Order is part of the hash. If it were not, a list that had merely
        // been reordered would come back "not modified" and the reader would
        // never see the chat that moved to the top.
        Assert.isTrue("order changes the hash",
                PageHash.fold(new long[] { 1, 2 })
                        != PageHash.fold(new long[] { 2, 1 }));

        Dialog[] held = list(11, 22, 33);
        held[0].topMessageId = 900;
        held[1].topMessageId = 901;
        held[2].topMessageId = 902;
        Assert.equal("by top message is the folded top messages",
                PageHash.fold(new long[] { 900, 901, 902 }),
                PageHash.dialogs(held, PageHash.BY_TOP_MESSAGE));
        Assert.equal("by peer is the folded peer ids",
                PageHash.fold(new long[] { 11, 22, 33 }),
                PageHash.dialogs(held, PageHash.BY_PEER));
        Assert.isTrue("the three candidates are genuinely different vectors",
                PageHash.dialogs(held, PageHash.BY_TOP_MESSAGE)
                        != PageHash.dialogs(held, PageHash.BY_DIALOG_STATE));
        Assert.equal("an empty list hashes to 0, which forces a full response",
                0, PageHash.dialogs(new Dialog[0], PageHash.BY_TOP_MESSAGE));
    }

    // -------------------------------------------------------------- helpers

    private static Dialog[] list(int[] ids)
    {
        Dialog[] out = new Dialog[ids.length];
        for (int i = 0; i < ids.length; i++)
        {
            Dialog d = new Dialog();
            d.peer = new Peer(Peer.USER, ids[i]);
            d.peer.title = "chat " + ids[i];
            d.topMessageId = 1000 + ids[i];
            d.date = 1767225600 - ids[i] * 60;
            d.lastMessage = "message " + ids[i];
            out[i] = d;
        }
        return out;
    }

    /** {@code count} consecutive ids from {@code from}. */
    private static int[] range(int from, int count)
    {
        int[] ids = new int[count];
        for (int i = 0; i < count; i++) { ids[i] = from + i; }
        return ids;
    }

    private static Dialog[] list(int a, int b) { return list(new int[] { a, b }); }

    private static Dialog[] list(int a, int b, int c, int d)
    {
        return list(new int[] { a, b, c, d });
    }

    private static Dialog[] list(int a, int b, int c)
    {
        return list(new int[] { a, b, c });
    }

    private static Dialog[] list(int a, int b, int c, int d, int e, int f)
    {
        return list(new int[] { a, b, c, d, e, f });
    }

    private static Dialog[] list(int a, int b, int c, int d, int e, int f,
                                 int g, int h, int i, int j)
    {
        return list(new int[] { a, b, c, d, e, f, g, h, i, j });
    }

    private static Dialog[] list(int a, int b, int c, int d, int e, int f,
                                 int g, int h, int i, int j, int k, int l,
                                 int m, int n, int o, int p, int q, int r,
                                 int s, int t)
    {
        return list(new int[] { a, b, c, d, e, f, g, h, i, j,
                                k, l, m, n, o, p, q, r, s, t });
    }

    /** What promoteDialog does: move one unpinned row to the top. */
    private static Dialog[] promote(Dialog[] source, int index)
    {
        Dialog[] out = new Dialog[source.length];
        out[0] = source[index];
        int w = 1;
        for (int i = 0; i < source.length; i++)
        {
            if (i != index) { out[w++] = source[i]; }
        }
        return out;
    }

    /** keyPressed is protected, and this suite is not in tg.ui. */
    private static final class ExposedList extends DialogListScreen
    {
        ExposedList() { super(Theme.byId(Theme.LIGHT)); }

        void press(int keyCode) { keyPressed(keyCode); }
    }
}
