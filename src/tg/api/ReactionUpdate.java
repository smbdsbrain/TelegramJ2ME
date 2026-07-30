package tg.api;

/** Point update for message reaction aggregates. */
public final class ReactionUpdate
{
    public Peer peer;
    public int messageId;
    public ReactionSummary[] reactions = new ReactionSummary[0];
}
