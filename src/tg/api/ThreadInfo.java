package tg.api;

/**
 * The thread half of an open transcript's identity.
 *
 * An open conversation is (peer, thread): a plain chat is (peer, null), a
 * forum topic is the forum peer plus the topic root, a comment thread is the
 * discussion group plus the forwarded post's root. Immutable, because it is
 * part of what {@code AsyncScope} captures - a value that changed under a
 * token would unmake the staleness guard.
 */
public final class ThreadInfo
{
    /** Root message id: the topic id, or the discussion root. Always &gt; 0. */
    public final int rootId;

    /** A closed topic refuses new messages; the composer says so instead. */
    public final boolean closed;

    /** Read cursor and badge as of when the thread was opened. */
    public final int readInboxMaxId;
    public final int unreadCount;

    /** Screen title: the topic title, or "Comments". */
    public final String title;

    public ThreadInfo(int rootId, boolean closed, int readInboxMaxId,
                      int unreadCount, String title)
    {
        this.rootId = rootId;
        this.closed = closed;
        this.readInboxMaxId = readInboxMaxId;
        this.unreadCount = unreadCount;
        this.title = title == null ? "" : title;
    }

    public String toString()
    {
        return title + " (#" + rootId + ")";
    }
}
