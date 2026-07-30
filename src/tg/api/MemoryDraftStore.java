package tg.api;

import java.util.Hashtable;

/** Deterministic in-memory DraftStore used by desktop tests. */
public final class MemoryDraftStore implements DraftStore
{
    private final Hashtable drafts = new Hashtable();

    public synchronized String load(Peer peer)
    {
        String value = (String) drafts.get(peer.key());
        return value == null ? "" : value;
    }

    public synchronized void save(Peer peer, String text)
    {
        if (text == null || text.length() == 0) { drafts.remove(peer.key()); }
        else { drafts.put(peer.key(), text); }
    }

    public synchronized void clear() { drafts.clear(); }
}
