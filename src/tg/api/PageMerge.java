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
