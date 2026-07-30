package tg.api;

import java.util.Vector;

import tg.tl.TlObj;

/** One reaction and its aggregate count on a message. */
public final class ReactionSummary
{
    public String emoji;
    public int count;
    public boolean chosen;
    public int chosenOrder = -1;
    public boolean custom;
    public boolean paid;

    static ReactionSummary[] from(TlObj reactions)
    {
        if (reactions == null || reactions.id != Api.MESSAGE_REACTIONS)
        {
            return new ReactionSummary[0];
        }
        TlObj[] raw = reactions.vec(Api.F_MESSAGE_REACTIONS__RESULTS);
        Vector out = new Vector();
        for (int i = 0; i < raw.length; i++)
        {
            TlObj count = raw[i];
            if (count == null || count.id != Api.REACTION_COUNT) { continue; }
            TlObj reaction = count.obj(Api.F_REACTION_COUNT__REACTION);
            ReactionSummary item = new ReactionSummary();
            item.count = count.intAt(Api.F_REACTION_COUNT__COUNT);
            item.chosen = count.flag(0);
            if (item.chosen)
            {
                item.chosenOrder = count.intAt(
                        Api.F_REACTION_COUNT__CHOSEN_ORDER);
            }
            if (reaction != null && reaction.id == Api.REACTION_EMOJI)
            {
                item.emoji = reaction.strOrEmpty(Api.F_REACTION_EMOJI__EMOTICON);
            }
            else if (reaction != null && reaction.id == Api.REACTION_CUSTOM_EMOJI)
            {
                item.emoji = "[custom]";
                item.custom = true;
            }
            else if (reaction != null && reaction.id == Api.REACTION_PAID)
            {
                item.emoji = "[paid]";
                item.paid = true;
            }
            else
            {
                continue;
            }
            out.addElement(item);
        }
        ReactionSummary[] result = new ReactionSummary[out.size()];
        out.copyInto(result);
        return result;
    }

    public static String[] chosenEmoji(ReactionSummary[] reactions)
    {
        Vector out = new Vector();
        if (reactions != null)
        {
            for (int i = 0; i < reactions.length; i++)
            {
                ReactionSummary r = reactions[i];
                if (r != null && r.chosen && !r.custom && !r.paid
                        && r.emoji != null)
                {
                    int at = out.size();
                    for (int j = 0; j < out.size(); j++)
                    {
                        ReactionSummary before =
                                (ReactionSummary) out.elementAt(j);
                        if (r.chosenOrder < before.chosenOrder)
                        {
                            at = j;
                            break;
                        }
                    }
                    out.insertElementAt(r, at);
                }
            }
        }
        String[] result = new String[out.size()];
        for (int i = 0; i < result.length; i++)
        {
            result[i] = ((ReactionSummary) out.elementAt(i)).emoji;
        }
        return result;
    }
}
