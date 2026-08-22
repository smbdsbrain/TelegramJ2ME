package tg.api;

/** Point update for one poll, routable even when peer/message fields are absent. */
public final class PollUpdate
{
    public Peer peer;
    public int messageId;
    public int topMessageId;
    public long pollId;
    public Poll poll;
}
