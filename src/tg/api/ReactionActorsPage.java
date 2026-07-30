package tg.api;

/** Bounded page returned by messages.getMessageReactionsList. */
public final class ReactionActorsPage
{
    public int totalCount;
    public ReactionActor[] actors = new ReactionActor[0];
    public String nextOffset;
}
