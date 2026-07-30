package tg.api;

/**
 * One row of the dialog list.
 *
 * A flat view object rather than a wrapper around the TL types: the UI needs a
 * title, a preview line, a badge and a date, and holding the whole parsed
 * response alive for a hundred rows would keep far more on the heap than the
 * screen ever shows.
 */
public final class Dialog
{
    public Peer peer;
    public int topMessageId;
    public int unreadCount;
    public boolean pinned;
    public int readInboxMaxId;
    public int readOutboxMaxId;
    public int channelPts;

    /** Preview text of the most recent message; may be empty. */
    public String lastMessage = "";

    /** Unix time of the last message, or 0. */
    public int date;

    public boolean lastMessageOutgoing;

    public String title()
    {
        return peer == null ? "?" : peer.title;
    }

    /** One-line preview, matching what other clients show. */
    public String preview()
    {
        if (lastMessage == null || lastMessage.length() == 0)
        {
            return "";
        }
        String text = lastMessage;
        // Newlines would break the single-line row layout on a 320x240 screen.
        int nl = text.indexOf('\n');
        if (nl >= 0)
        {
            text = text.substring(0, nl);
        }
        return lastMessageOutgoing ? ("You: " + text) : text;
    }

    public String toString()
    {
        return title() + (unreadCount > 0 ? (" (" + unreadCount + ")") : "");
    }
}
