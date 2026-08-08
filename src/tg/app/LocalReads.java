package tg.app;

import tg.api.Dialog;
import tg.api.Peer;

/**
 * Chats the reader has marked read, until the server says so too.
 *
 * <h3>The defect</h3>
 * {@code Mark all read} clears the badge on the retained {@link Dialog} and
 * sends the acknowledgement. The next snapshot refresh then replaces that row
 * wholesale - {@code PageMerge.restate} assigns the fresh object, and
 * {@code PageMerge.refresh} prefers the fresh head-page entry - so the badge
 * the user just cleared comes back, and stays back until the server's own read
 * cursor catches up and the refresh after that reports it. On a slow
 * connection that is several seconds of the client contradicting the user.
 *
 * <h3>Why not merge the fields in PageMerge</h3>
 * Because {@code PageMerge} is positioning, and this is read-state policy. The
 * question "what may a server-authoritative refresh overwrite in a window the
 * reader has acted on" is the same question as stale ordering, and it belongs
 * beside the rest of the answer rather than inside a merge helper that has no
 * business knowing what an unread count means.
 *
 * <h3>Bounded, and it expires</h3>
 * Sixteen entries, oldest evicted - a reader who marks seventeen chats read
 * before any of the acknowledgements land is not a case worth holding memory
 * for. An entry drops itself the moment the server reports a
 * {@code readInboxMaxId} that reaches the id it was recorded for: at that point
 * the server agrees, and anything after that is news rather than lag.
 *
 * Peer kind and id are stored rather than the {@link Peer}, so nothing here
 * keeps a contact's name alive past a logout.
 */
public final class LocalReads
{
    /** Enough for a session of catching up; small enough to be free. */
    private static final int MAX = 16;

    private final int[] kinds = new int[MAX];
    private final long[] ids = new long[MAX];
    private final int[] upTo = new int[MAX];
    private int count;

    /** The reader cleared {@code peer} up to and including {@code maxId}. */
    public synchronized void cleared(Peer peer, int maxId)
    {
        if (peer == null || maxId <= 0) { return; }
        int at = find(peer);
        if (at >= 0)
        {
            if (maxId > upTo[at]) { upTo[at] = maxId; }
            return;
        }
        if (count == MAX)
        {
            // Oldest out. Losing one means one badge may flicker back for a
            // few seconds, which is what this whole class is for and not worth
            // an unbounded array to avoid.
            System.arraycopy(kinds, 1, kinds, 0, MAX - 1);
            System.arraycopy(ids, 1, ids, 0, MAX - 1);
            System.arraycopy(upTo, 1, upTo, 0, MAX - 1);
            count--;
        }
        kinds[count] = peer.kind;
        ids[count] = peer.id;
        upTo[count] = maxId;
        count++;
    }

    /**
     * Re-apply what the reader did to a row the server has just restated.
     *
     * Called after every refresh, on the display thread, for each retained
     * dialog. Cheap: sixteen comparisons against a list of at most a hundred
     * and twenty rows, once per repaint of the chat list.
     */
    public synchronized void apply(Dialog dialog)
    {
        if (dialog == null || dialog.peer == null) { return; }
        int at = find(dialog.peer);
        if (at < 0) { return; }

        if (dialog.readInboxMaxId >= upTo[at])
        {
            // The server has caught up. Whatever it says now is newer than what
            // we remembered, including a badge that is legitimately back
            // because something arrived after the acknowledgement.
            remove(at);
            return;
        }
        dialog.readInboxMaxId = upTo[at];
        dialog.unreadCount = 0;
    }

    /** Forget everything. A logout, or a deliberate reload of the list. */
    public synchronized void clear()
    {
        count = 0;
    }

    /** How many chats are waiting for the server to agree. Diagnostics. */
    public synchronized int pending()
    {
        return count;
    }

    private int find(Peer peer)
    {
        for (int i = 0; i < count; i++)
        {
            if (kinds[i] == peer.kind && ids[i] == peer.id) { return i; }
        }
        return -1;
    }

    private void remove(int at)
    {
        int move = count - at - 1;
        if (move > 0)
        {
            System.arraycopy(kinds, at + 1, kinds, at, move);
            System.arraycopy(ids, at + 1, ids, at, move);
            System.arraycopy(upTo, at + 1, upTo, at, move);
        }
        count--;
    }
}
