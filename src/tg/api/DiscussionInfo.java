package tg.api;

/**
 * Where a channel post's comments live: the linked discussion group and the
 * auto-forwarded copy of the post that roots the thread there.
 */
public final class DiscussionInfo
{
    /** The discussion supergroup, resolved with its access_hash. */
    public Peer discussionPeer;

    /** Id of the forwarded post inside the discussion group. */
    public int rootMessageId;

    public int readInboxMaxId;
    public int unreadCount;
}
