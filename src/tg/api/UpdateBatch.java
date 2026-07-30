package tg.api;

/**
 * A small immutable delivery from the update worker to the UI.
 *
 * A fullRefresh request is conservative: it is used when a server update
 * changes message data outside Phase 3's text/read subset.
 */
public final class UpdateBatch
{
    public Message[] messages = new Message[0];
    public ReadState[] reads = new ReadState[0];
    public ReactionUpdate[] reactions = new ReactionUpdate[0];
    public boolean fullRefresh;
    public String syncState;
    public String detail;
}
