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
    /**
     * Characters of preview kept per row.
     *
     * The row draws one clipped line and always did, but what was <i>held</i>
     * was the whole message - up to 4096 characters of it. That is why nothing
     * in this client could say what a dialog costs: it had no fixed size, and a
     * list capped at a count was bounded only in the sense that a hundred
     * novels is a hundred of something.
     *
     * 96 is wider than any 320px row survives after {@code UiChrome.clip}, with
     * room for the "You: " prefix and for a proportional font narrower than the
     * one measured, so nothing visible changes.
     */
    public static final int PREVIEW_MAX = 96;

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

    /**
     * Adopt one authoritative message as this row's preview.
     *
     * Keep the privacy decision here rather than at each caller: a spoiler
     * must be concealed before the text is clipped, cached or handed to the
     * Canvas. The old dialog cache retained only this flattened string, so a
     * caller that copied {@link Message#text} directly made the leak durable.
     */
    public void setPreview(Message message)
    {
        if (message == null)
        {
            lastMessage = "";
            lastMessageOutgoing = false;
            return;
        }
        lastMessage = clipPreview(message.summaryText());
        date = message.date;
        lastMessageOutgoing = message.outgoing;
    }

    public String title()
    {
        return peer == null ? "?" : peer.title;
    }

    /**
     * Bound a preview at ingest.
     *
     * At ingest rather than at paint, because paint already clips and the
     * problem is retention: every path that fills {@link #lastMessage} goes
     * through here, so a Dialog costs the same whatever arrived in it.
     */
    public static String clipPreview(String text)
    {
        if (text == null) { return ""; }
        // The newline cut is here as well as in preview() so the retained copy
        // is not carrying a second paragraph nobody can see.
        int nl = text.indexOf('\n');
        if (nl >= 0) { text = text.substring(0, nl); }
        return text.length() <= PREVIEW_MAX
                ? text : text.substring(0, PREVIEW_MAX);
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
