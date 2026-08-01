package tg.api;

/** Bounded page merging and local dialog filtering shared by UI and tests. */
public final class PageMerge
{
    private PageMerge() { }

    public static Dialog[] dialogs(Dialog[] first, Dialog[] second, int limit)
    {
        Dialog[] merged = new Dialog[Math.min(limit,
                first.length + second.length)];
        int count = 0;
        for (int pass = 0; pass < 2 && count < merged.length; pass++)
        {
            Dialog[] source = pass == 0 ? first : second;
            for (int i = 0; i < source.length && count < merged.length; i++)
            {
                Dialog value = source[i];
                if (value == null || value.peer == null) { continue; }
                int duplicate = find(merged, count, value.peer);
                if (duplicate >= 0)
                {
                    if (pass == 1) { merged[duplicate] = value; }
                }
                else
                {
                    merged[count++] = value;
                }
            }
        }
        Dialog[] out = new Dialog[count];
        System.arraycopy(merged, 0, out, 0, count);
        return out;
    }

    /**
     * Lay a freshly fetched head page over the retained list.
     *
     * {@link #dialogs} gives position to the first array and content to the
     * second, which is exactly right for appending a page at the bottom and
     * exactly backwards for a refresh, where the head is both newer and
     * authoritative about order. Assigning instead of merging is worse still:
     * the server caps a {@code messages.getDialogs} page well below the number
     * a reader can scroll to, so a refresh that replaces would truncate the
     * list under them on every update burst.
     *
     * Appending the retained tail unchanged is safe because the head is the
     * newest run: anything the server did not just send is older than
     * everything it did.
     *
     * @param head     newest first, as the server returned it; wins on order
     *                 and on content
     * @param retained what the client already holds, head first
     */
    public static Dialog[] refresh(Dialog[] head, Dialog[] retained, int limit)
    {
        if (head == null) { head = new Dialog[0]; }
        if (retained == null) { retained = new Dialog[0]; }
        Dialog[] merged = new Dialog[Math.min(limit,
                head.length + retained.length)];
        int count = 0;
        for (int pass = 0; pass < 2 && count < merged.length; pass++)
        {
            Dialog[] source = pass == 0 ? head : retained;
            for (int i = 0; i < source.length && count < merged.length; i++)
            {
                Dialog value = source[i];
                if (value == null || value.peer == null) { continue; }
                if (find(merged, count, value.peer) >= 0) { continue; }
                merged[count++] = value;
            }
        }
        Dialog[] out = new Dialog[count];
        System.arraycopy(merged, 0, out, 0, count);
        return out;
    }

    /**
     * Keep at most {@code limit} dialogs, dropping from the top.
     *
     * The counterpart of {@link #messages}'s tail truncation, and the opposite
     * end, because the chat list is read downwards: the rows a reader has gone
     * past are above them, and those are the ones nobody is looking at. What
     * makes dropping them safe is that they can be fetched again - see the
     * restore stack in {@code TgMidlet}, which records the one dialog sitting
     * above each dropped run so a single request brings the run back.
     *
     * @return the rows kept; {@code dropped[0]} of the caller's bookkeeping is
     *         whatever fell off the front
     */
    public static Dialog[] keepLast(Dialog[] source, int limit)
    {
        if (source == null) { return new Dialog[0]; }
        if (limit >= source.length) { return source; }
        if (limit <= 0) { return new Dialog[0]; }
        Dialog[] out = new Dialog[limit];
        System.arraycopy(source, source.length - limit, out, 0, limit);
        return out;
    }

    /** Keep at most {@code limit} dialogs, dropping from the bottom. */
    public static Dialog[] keepFirst(Dialog[] source, int limit)
    {
        if (source == null) { return new Dialog[0]; }
        if (limit >= source.length) { return source; }
        if (limit <= 0) { return new Dialog[0]; }
        Dialog[] out = new Dialog[limit];
        System.arraycopy(source, 0, out, 0, limit);
        return out;
    }

    /**
     * Refresh the content of rows a page happens to share, and nothing else.
     *
     * {@link #refresh} lays the newest page over the front of the list, which
     * is right when the list starts at the top and wrong the moment it does
     * not. A window sitting at row four hundred is not adjacent to the newest
     * page: splicing them would put row 400 directly under row 0 and read as a
     * contiguous list that skips four hundred chats.
     *
     * So when the window has scrolled, this is what a refresh is allowed to do.
     * Unread counts, previews and dates come forward; order and membership do
     * not move. The ordering goes stale until the reader scrolls back to the
     * top or presses Refresh, which is a far smaller lie than a list with an
     * invisible hole in it.
     *
     * @return {@code window}, mutated in place
     */
    public static Dialog[] restate(Dialog[] fresh, Dialog[] window)
    {
        if (fresh == null || window == null) { return window; }
        for (int i = 0; i < fresh.length; i++)
        {
            Dialog value = fresh[i];
            if (value == null || value.peer == null) { continue; }
            int at = find(window, window.length, value.peer);
            if (at >= 0) { window[at] = value; }
        }
        return window;
    }

    /**
     * How many dialogs sit above {@code first} in the retained window.
     *
     * The mirror of {@link #below}, and it exists for the same reason: a reader
     * coming back up has to provoke a fetch before they run out of window, and
     * a filter must not be able to make that decision on their behalf.
     */
    public static int above(Dialog[] all, Peer first)
    {
        if (all == null) { return 0; }
        if (first == null) { return all.length; }
        for (int i = 0; i < all.length; i++)
        {
            Peer candidate = all[i] == null ? null : all[i].peer;
            if (candidate != null && candidate.kind == first.kind
                    && candidate.id == first.id)
            {
                return i;
            }
        }
        return all.length;
    }

    /**
     * How many dialogs sit below {@code last} in the list as a whole.
     *
     * This is what decides when another page is worth asking for, and it takes
     * the unfiltered array on purpose. {@link #filter} narrows what is
     * displayed, so "scrolled to the bottom" under a filter means the bottom of
     * the matches: measuring against the filtered array would fetch for ever on
     * a filter that matches three chats near the top.
     *
     * @param last the peer on the bottom row, or null when nothing is shown
     * @return dialogs after it, or {@code all.length} when it cannot be placed -
     *         the answer that asks for nothing
     */
    public static int below(Dialog[] all, Peer last)
    {
        if (all == null) { return 0; }
        if (last == null) { return all.length; }
        for (int i = 0; i < all.length; i++)
        {
            Peer candidate = all[i] == null ? null : all[i].peer;
            if (candidate != null && candidate.kind == last.kind
                    && candidate.id == last.id)
            {
                return all.length - 1 - i;
            }
        }
        return all.length;
    }

    public static Message[] messages(Message[] first, Message[] second,
                                     int limit)
    {
        Message[] merged = new Message[Math.min(limit,
                first.length + second.length)];
        int count = 0;
        for (int pass = 0; pass < 2 && count < merged.length; pass++)
        {
            Message[] source = pass == 0 ? first : second;
            for (int i = 0; i < source.length && count < merged.length; i++)
            {
                Message value = source[i];
                if (value == null) { continue; }
                int duplicate = find(merged, count, value.id);
                if (duplicate >= 0)
                {
                    if (pass == 1) { merged[duplicate] = value; }
                }
                else
                {
                    merged[count++] = value;
                }
            }
        }
        Message[] out = new Message[count];
        System.arraycopy(merged, 0, out, 0, count);
        return out;
    }

    /**
     * Merge two newest-first pages into one, preferring the fresher copy.
     *
     * {@link #messages} concatenates, which is correct only when the second page
     * is entirely older than the first - true of paging backwards, and false of
     * a refresh that arrives while the reader has scrolled the retained window
     * off the newest end. Ordering by {@code (date, id)} descending is the
     * invariant the update path already inserts against, so an out-of-order page
     * lands where it belongs instead of at the tail.
     *
     * @param fresher wins on a duplicate id: it carries newer read state,
     *                reactions and edits
     */
    public static Message[] merge(Message[] older, Message[] fresher)
    {
        if (older == null) { older = new Message[0]; }
        if (fresher == null) { fresher = new Message[0]; }
        Message[] merged = new Message[older.length + fresher.length];
        int count = 0;
        int a = 0;
        int b = 0;
        while (a < older.length || b < fresher.length)
        {
            Message pick;
            if (a >= older.length) { pick = fresher[b++]; }
            else if (b >= fresher.length) { pick = older[a++]; }
            else
            {
                Message left = older[a];
                Message right = fresher[b];
                if (left == null) { a++; continue; }
                if (right == null) { b++; continue; }
                if (left.id == right.id) { pick = right; a++; b++; }
                else if (newer(left, right)) { pick = left; a++; }
                else { pick = right; b++; }
            }
            // A duplicate normally meets itself in the branch above. Reaching
            // here means the same id sorted to two different places, which
            // takes a date that changed between fetches; first position wins
            // rather than leaving the id in twice.
            if (pick == null || find(merged, count, pick.id) >= 0) { continue; }
            merged[count++] = pick;
        }
        Message[] out = new Message[count];
        System.arraycopy(merged, 0, out, 0, count);
        return out;
    }

    private static boolean newer(Message left, Message right)
    {
        if (left.date != right.date) { return left.date > right.date; }
        return left.id > right.id;
    }

    /**
     * Keep at most {@code limit} messages, the run containing {@code anchor}.
     *
     * {@link #messages} truncates the tail, which is the wrong end once history
     * is scrolled rather than paged: reading backwards means the oldest messages
     * are the ones on screen and the newest are the ones nobody is looking at.
     * This keeps the anchor centred, so whichever direction the reader is moving
     * in, what gets dropped is behind them.
     *
     * @param anchor index of the message to keep in view; clamped into range
     */
    public static Message[] window(Message[] messages, int anchor, int limit)
    {
        if (messages == null) { return new Message[0]; }
        if (limit >= messages.length) { return messages; }
        if (limit <= 0) { return new Message[0]; }
        if (anchor < 0) { anchor = 0; }
        if (anchor >= messages.length) { anchor = messages.length - 1; }

        int from = anchor - limit / 2;
        if (from < 0) { from = 0; }
        if (from + limit > messages.length) { from = messages.length - limit; }
        Message[] out = new Message[limit];
        System.arraycopy(messages, from, out, 0, limit);
        return out;
    }

    public static Dialog[] filter(Dialog[] source, String filter)
    {
        if (filter == null || filter.trim().length() == 0) { return source; }
        String needle = filter.trim().toLowerCase();
        Dialog[] found = new Dialog[source.length];
        int count = 0;
        for (int i = 0; i < source.length; i++)
        {
            Dialog dialog = source[i];
            String title = dialog == null ? "" : dialog.title();
            if (title.toLowerCase().indexOf(needle) >= 0)
            {
                found[count++] = dialog;
            }
        }
        Dialog[] out = new Dialog[count];
        System.arraycopy(found, 0, out, 0, count);
        return out;
    }

    private static int find(Dialog[] values, int count, Peer peer)
    {
        for (int i = 0; i < count; i++)
        {
            Peer candidate = values[i].peer;
            if (candidate.kind == peer.kind && candidate.id == peer.id) { return i; }
        }
        return -1;
    }

    private static int find(Message[] values, int count, int id)
    {
        for (int i = 0; i < count; i++)
        {
            if (values[i].id == id) { return i; }
        }
        return -1;
    }
}
