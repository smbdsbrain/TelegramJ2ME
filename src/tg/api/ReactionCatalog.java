package tg.api;

import java.util.Vector;

/** Canonical compact fallback palette and server-policy intersection. */
public final class ReactionCatalog
{
    public static final String[] EMOJI = {
        "\ud83d\udc4d", "\u2764", "\ud83e\udd23", "\ud83d\ude31",
        "\ud83d\ude22", "\ud83d\ude4f", "\ud83d\udd25", "\ud83d\udc4e",
        "\ud83c\udf89", "\ud83e\udd14", "\ud83d\ude0d", "\ud83e\udd2f"
    };

    public static final String[] LABELS = {
        "Like", "Love", "Laugh", "Wow",
        "Sad", "Thanks", "Fire", "Dislike",
        "Party", "Think", "Adore", "Mind blown"
    };

    private ReactionCatalog() { }

    /**
     * Retain preferred reactions present in both the global and peer sets.
     * A null peer set means ChatReactionsAll; an empty one means none.
     */
    public static String[] filter(String[] global, String[] peer)
    {
        Vector out = new Vector();
        for (int i = 0; i < EMOJI.length; i++)
        {
            if (contains(global, EMOJI[i])
                    && (peer == null || contains(peer, EMOJI[i])))
            {
                out.addElement(EMOJI[i]);
            }
        }
        String[] result = new String[out.size()];
        out.copyInto(result);
        return result;
    }

    public static String[] labelsFor(String[] emoji)
    {
        if (emoji == null) { return new String[0]; }
        String[] labels = new String[emoji.length];
        for (int i = 0; i < emoji.length; i++)
        {
            labels[i] = "";
            for (int j = 0; j < EMOJI.length; j++)
            {
                if (EMOJI[j].equals(emoji[i]))
                {
                    labels[i] = LABELS[j];
                    break;
                }
            }
        }
        return labels;
    }

    private static boolean contains(String[] values, String wanted)
    {
        if (values == null) { return false; }
        for (int i = 0; i < values.length; i++)
        {
            if (wanted.equals(values[i])) { return true; }
        }
        return false;
    }
}
