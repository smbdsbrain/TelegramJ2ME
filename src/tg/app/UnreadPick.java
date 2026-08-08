package tg.app;

import tg.api.Message;

/**
 * Which message "first unread" actually means, out of a page.
 *
 * <h3>Why this is not readInboxMaxId + 1</h3>
 * The read marker is a high-water id, not an index. Telegram allocates ids per
 * peer and they are not contiguous: service messages, deletions and messages
 * the client never fetched all leave gaps, so the id one past the marker
 * usually does not exist. A client that jumps to it lands nowhere.
 *
 * It is also not "the first message in the page after the marker". A page comes
 * back newest first, it contains the reader's own outgoing messages, and read
 * markers refer to incoming ones - the reader has read everything they sent by
 * definition. Picking the first row would select their own last reply about as
 * often as not.
 *
 * So: the <em>oldest incoming message with an id above the marker</em>, out of
 * whatever the bounded page actually contained. If the exact boundary message
 * was deleted, that is the earliest unread still available, which is the honest
 * best effort and is labelled as such by the caller.
 *
 * <h3>Pending messages</h3>
 * Excluded. A queued outbox row has no server id yet and is the reader's own
 * text; neither makes it something to catch up on.
 */
public final class UnreadPick
{
    private UnreadPick() { }

    /** Nothing in the page qualifies. */
    public static final int NONE = 0;

    /**
     * @param messages   a history page, in any order
     * @param readMaxId  the server read high-water mark for this chat
     * @return the id to focus, or {@link #NONE}
     */
    public static int firstUnread(Message[] messages, int readMaxId)
    {
        if (messages == null) { return NONE; }
        int best = NONE;
        for (int i = 0; i < messages.length; i++)
        {
            Message message = messages[i];
            if (message == null) { continue; }
            // Ids at or below the marker are read; a local row has no server id
            // to compare at all.
            if (message.id <= readMaxId) { continue; }
            if (message.outgoing) { continue; }
            if (best == NONE || message.id < best) { best = message.id; }
        }
        return best;
    }

    /**
     * Is the page complete enough to trust "no unread" as an answer?
     *
     * A page bounded at thirty messages that is entirely outgoing tells you
     * nothing about what is below it. Saying "you are up to date" on that
     * evidence is a guess; saying "none in the last thirty" is a fact.
     *
     * @return true when the page reached back past the read marker, so anything
     *         unread in it would have been seen
     */
    public static boolean pageReachesMarker(Message[] messages, int readMaxId)
    {
        if (messages == null || messages.length == 0) { return false; }
        for (int i = 0; i < messages.length; i++)
        {
            if (messages[i] != null && messages[i].id <= readMaxId)
            {
                return true;
            }
        }
        return false;
    }

    /** The highest id in a page, for keeping a high-water mark up to date. */
    public static int newestId(Message[] messages)
    {
        int best = 0;
        if (messages == null) { return best; }
        for (int i = 0; i < messages.length; i++)
        {
            if (messages[i] != null && messages[i].id > best)
            {
                best = messages[i].id;
            }
        }
        return best;
    }
}
