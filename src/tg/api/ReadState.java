package tg.api;

/** One peer's read cursor changes carried to the UI. */
public final class ReadState
{
    public Peer peer;

    /** Thread root the cursor applies to, or 0 for the whole peer. */
    public int threadRootId;

    public int inboxMaxId = -1;
    public int outboxMaxId = -1;
    public int unreadCount = -1;
}
