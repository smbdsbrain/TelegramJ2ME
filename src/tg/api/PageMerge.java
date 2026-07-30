package tg.api;

/** Bounded page merging and local dialog filtering shared by UI and tests. */
public final class PageMerge
{
    private PageMerge() { }

    public static Dialog[] dialogs(Dialog[] first, Dialog[] second, int limit)
    {
        Dialog[] merged = new Dialog[Math.min(limit,
                first.length + second.length)];
        int count = 0;
        for (int pass = 0; pass < 2 && count < merged.length; pass++)
        {
            Dialog[] source = pass == 0 ? first : second;
            for (int i = 0; i < source.length && count < merged.length; i++)
            {
                Dialog value = source[i];
                if (value == null || value.peer == null) { continue; }
                int duplicate = find(merged, count, value.peer);
                if (duplicate >= 0)
                {
                    if (pass == 1) { merged[duplicate] = value; }
                }
                else
                {
                    merged[count++] = value;
                }
            }
        }
        Dialog[] out = new Dialog[count];
        System.arraycopy(merged, 0, out, 0, count);
        return out;
    }

    public static Message[] messages(Message[] first, Message[] second,
                                     int limit)
    {
        Message[] merged = new Message[Math.min(limit,
                first.length + second.length)];
        int count = 0;
        for (int pass = 0; pass < 2 && count < merged.length; pass++)
        {
            Message[] source = pass == 0 ? first : second;
            for (int i = 0; i < source.length && count < merged.length; i++)
            {
                Message value = source[i];
                if (value == null) { continue; }
                int duplicate = find(merged, count, value.id);
                if (duplicate >= 0)
                {
                    if (pass == 1) { merged[duplicate] = value; }
                }
                else
                {
                    merged[count++] = value;
                }
            }
        }
        Message[] out = new Message[count];
        System.arraycopy(merged, 0, out, 0, count);
        return out;
    }

    public static Dialog[] filter(Dialog[] source, String filter)
    {
        if (filter == null || filter.trim().length() == 0) { return source; }
        String needle = filter.trim().toLowerCase();
        Dialog[] found = new Dialog[source.length];
        int count = 0;
        for (int i = 0; i < source.length; i++)
        {
            Dialog dialog = source[i];
            String title = dialog == null ? "" : dialog.title();
            if (title.toLowerCase().indexOf(needle) >= 0)
            {
                found[count++] = dialog;
            }
        }
        Dialog[] out = new Dialog[count];
        System.arraycopy(found, 0, out, 0, count);
        return out;
    }

    private static int find(Dialog[] values, int count, Peer peer)
    {
        for (int i = 0; i < count; i++)
        {
            Peer candidate = values[i].peer;
            if (candidate.kind == peer.kind && candidate.id == peer.id) { return i; }
        }
        return -1;
    }

    private static int find(Message[] values, int count, int id)
    {
        for (int i = 0; i < count; i++)
        {
            if (values[i].id == id) { return i; }
        }
        return -1;
    }
}
