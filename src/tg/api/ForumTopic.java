package tg.api;

import tg.tl.TlObj;

/**
 * One row of a forum's topic list.
 *
 * A flat view object in the {@link Dialog} mould: the screen needs a title, a
 * preview, a badge and a date, and its id doubles as the offset for the next
 * page and as the thread root every topic request takes. The custom-emoji icon
 * ({@code icon_emoji_id}) is deliberately not read - the same trade the
 * message emoji made - so a topic row is its title.
 */
public final class ForumTopic
{
    /**
     * The General topic's fixed id - the id of the service message every
     * forum is created around. A forum message with no reply header lives
     * here, and sends into it need no reply header of their own.
     */
    public static final int GENERAL_ID = 1;

    public int id;
    public int date;
    public String title = "";
    public boolean closed;
    public boolean pinned;
    public boolean hidden;
    public int topMessageId;
    public int unreadCount;
    public int readInboxMaxId;
    public int readOutboxMaxId;

    /** Preview of the topic's newest message; bounded like a dialog row's. */
    public String lastMessage = "";

    /** Unix time of the newest message, or the topic date before the join. */
    public int lastDate;

    public boolean lastOutgoing;

    /** Entity-aware preview shared by initial pages and live topic updates. */
    public void setPreview(Message message)
    {
        if (message == null)
        {
            lastMessage = "";
            lastOutgoing = false;
            return;
        }
        lastMessage = Dialog.clipPreview(message.summaryText());
        lastDate = message.date;
        lastOutgoing = message.outgoing;
    }

    /**
     * Read a {@code forumTopic} constructor.
     *
     * @return null for {@code forumTopicDeleted} and anything else - a
     *         deleted topic has no row, and the next refresh drops it
     */
    public static ForumTopic from(TlObj obj)
    {
        if (obj == null || obj.id != Api.FORUM_TOPIC)
        {
            return null;
        }
        ForumTopic t = new ForumTopic();
        t.id = obj.intAt(Api.F_FORUM_TOPIC__ID);
        t.date = obj.intAt(Api.F_FORUM_TOPIC__DATE);
        t.title = obj.strOrEmpty(Api.F_FORUM_TOPIC__TITLE);
        t.closed = obj.num(Api.F_FORUM_TOPIC__CLOSED) != 0;
        t.pinned = obj.num(Api.F_FORUM_TOPIC__PINNED) != 0;
        t.hidden = obj.num(Api.F_FORUM_TOPIC__HIDDEN) != 0;
        t.topMessageId = obj.intAt(Api.F_FORUM_TOPIC__TOP_MESSAGE);
        t.readInboxMaxId = obj.intAt(Api.F_FORUM_TOPIC__READ_INBOX_MAX_ID);
        t.readOutboxMaxId = obj.intAt(Api.F_FORUM_TOPIC__READ_OUTBOX_MAX_ID);
        t.unreadCount = obj.intAt(Api.F_FORUM_TOPIC__UNREAD_COUNT);
        t.lastDate = t.date;
        if (t.title.length() == 0)
        {
            // short topics may omit the title; an empty row would read as a bug
            t.title = t.id == GENERAL_ID ? "General" : ("Topic " + t.id);
        }
        return t;
    }

    /** One-line preview, same shape as a dialog row's. */
    public String preview()
    {
        if (lastMessage == null || lastMessage.length() == 0)
        {
            return "";
        }
        return lastOutgoing ? ("You: " + lastMessage) : lastMessage;
    }

    public String toString()
    {
        return title + (unreadCount > 0 ? (" (" + unreadCount + ")") : "");
    }
}
