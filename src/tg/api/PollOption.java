package tg.api;

/** One bounded poll answer and the current aggregate attached to it. */
public final class PollOption
{
    public String text = "";
    /** Opaque token passed back to messages.sendVote. */
    public byte[] option = new byte[0];
    /** -1 when Telegram did not disclose per-option results. */
    public int voters = -1;
    public boolean chosen;
    public boolean correct;

    public PollOption copy()
    {
        PollOption out = new PollOption();
        out.text = text;
        out.option = Poll.copyBytes(option);
        out.voters = voters;
        out.chosen = chosen;
        out.correct = correct;
        return out;
    }
}
