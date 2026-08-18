package tg.api;

import java.util.Hashtable;

/** Deterministic in-memory DraftStore used by desktop tests. */
public final class MemoryDraftStore implements DraftStore
{
    private final Hashtable drafts = new Hashtable();

    public synchronized String load(Peer peer, int thread)
    {
        String value = (String) drafts.get(key(peer, thread));
        return value == null ? "" : value;
    }

    public synchronized void save(Peer peer, int thread, String text)
    {
        if (text == null || text.length() == 0)
        {
            drafts.remove(key(peer, thread));
        }
        else { drafts.put(key(peer, thread), text); }
    }

    private static String key(Peer peer, int thread)
    {
        return peer.key() + ":" + thread;
    }

    public synchronized void clear() { drafts.clear(); }
}
