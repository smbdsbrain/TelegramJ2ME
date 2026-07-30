package tg.api;

/** One peer's read cursor changes carried to the UI. */
public final class ReadState
{
    public Peer peer;
    public int inboxMaxId = -1;
    public int outboxMaxId = -1;
    public int unreadCount = -1;
}
