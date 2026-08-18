package tg.api;

/**
 * Array arithmetic for the topic-list window: merge, trim, count.
 *
 * The {@code PageMerge} idea one screen down, keyed by topic id instead of
 * peer. Pure functions over arrays, because the window logic in the MIDlet is
 * only testable to the extent this part of it is not in the MIDlet.
 */
public final class TopicWindow
{
    private TopicWindow() { }

    /** Index of {@code id} in {@code rows}, or -1. */
    public static int indexOf(ForumTopic[] rows, int id)
    {
        if (rows == null || id <= 0) { return -1; }
        for (int i = 0; i < rows.length; i++)
        {
            if (rows[i] != null && rows[i].id == id) { return i; }
        }
        return -1;
    }

    /**
     * {@code first} in order, then every row of {@code second} it does not
     * already hold. The page a request returned goes second, so a topic the
     * window already shows keeps its place - and its fresher unread count is
     * copied across rather than reordering the reader's view.
     */
    public static ForumTopic[] merge(ForumTopic[] first, ForumTopic[] second)
    {
        if (first == null) { first = new ForumTopic[0]; }
        if (second == null) { second = new ForumTopic[0]; }
        ForumTopic[] out = new ForumTopic[first.length + second.length];
        int w = 0;
        for (int i = 0; i < first.length; i++)
        {
            if (first[i] != null) { out[w++] = first[i]; }
        }
        int held = w;
        for (int i = 0; i < second.length; i++)
        {
            ForumTopic fresh = second[i];
            if (fresh == null) { continue; }
            int at = -1;
            for (int k = 0; k < held; k++)
            {
                if (out[k].id == fresh.id) { at = k; break; }
            }
            if (at >= 0) { out[at] = fresh; }
            else { out[w++] = fresh; }
        }
        if (w == out.length) { return out; }
        ForumTopic[] trimmed = new ForumTopic[w];
        System.arraycopy(out, 0, trimmed, 0, w);
        return trimmed;
    }

    /** The last {@code cap} rows - what appending keeps. */
    public static ForumTopic[] keepLast(ForumTopic[] rows, int cap)
    {
        if (rows == null) { return new ForumTopic[0]; }
        if (rows.length <= cap) { return rows; }
        ForumTopic[] out = new ForumTopic[cap];
        System.arraycopy(rows, rows.length - cap, out, 0, cap);
        return out;
    }

    /** The first {@code cap} rows - what restoring keeps. */
    public static ForumTopic[] keepFirst(ForumTopic[] rows, int cap)
    {
        if (rows == null) { return new ForumTopic[0]; }
        if (rows.length <= cap) { return rows; }
        ForumTopic[] out = new ForumTopic[cap];
        System.arraycopy(rows, 0, out, 0, cap);
        return out;
    }

    /** Rows of {@code page} the window does not already hold. */
    public static int countNew(ForumTopic[] window, ForumTopic[] page)
    {
        if (page == null) { return 0; }
        int fresh = 0;
        for (int i = 0; i < page.length; i++)
        {
            if (page[i] != null && indexOf(window, page[i].id) < 0)
            {
                fresh++;
            }
        }
        return fresh;
    }
}
