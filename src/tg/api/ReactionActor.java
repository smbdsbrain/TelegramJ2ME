package tg.api;

/** One peer and the reaction they left on a message. */
public final class ReactionActor
{
    public Peer peer;
    public String emoji = "[reaction]";
    public int date;
}
