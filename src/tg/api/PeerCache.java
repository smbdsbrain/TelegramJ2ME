package tg.api;

import java.util.Hashtable;

import tg.mem.MemoryBudget;
import tg.tl.TlObj;

/**
 * Identity cache for users, groups and channels.
 *
 * Telegram responses are normalised: a dialog or message names its peer by id
 * only, and the names and access_hashes arrive once per response in separate
 * {@code users} and {@code chats} vectors. Nothing can be displayed or
 * addressed without joining the two, so every response is fed through
 * {@link #absorb} and lookups go through {@link #resolve}.
 *
 * Bounded on purpose: a busy account has thousands of peers and the heap is
 * small. When the cache is full the newest entries still win, because an entry
 * we just received is the one about to be used. The bound is
 * {@link tg.mem.MemoryBudget#peerCacheEntries} - roughly a hundred bytes each,
 * so a low hundreds of KB at the reference heap.
 */
public final class PeerCache
{
    private final Hashtable peers = new Hashtable();

    /** The signed-in user, once known. */
    private Peer self;

    public Peer self()
    {
        return self;
    }

    public int size()
    {
        return peers.size();
    }

    public void clear()
    {
        peers.clear();
        self = null;
    }

    /**
     * Take the users and chats out of a response.
     *
     * Both vectors are optional - a response may carry neither - so this is safe
     * to call on anything.
     */
    public void absorb(TlObj[] users, TlObj[] chats)
    {
        if (users != null)
        {
            for (int i = 0; i < users.length; i++)
            {
                put(Peer.fromUser(users[i]));
            }
        }
        if (chats != null)
        {
            for (int i = 0; i < chats.length; i++)
            {
                put(Peer.fromChat(chats[i]));
            }
        }
    }

    public void put(Peer p)
    {
        if (p == null)
        {
            return;
        }
        if (p.self)
        {
            self = p;
        }
        if (peers.size() >= MemoryBudget.peerCacheEntries()
                && !peers.containsKey(p.key()))
        {
            // Drop an arbitrary existing entry rather than refusing the new one:
            // what just arrived is what is about to be displayed.
            java.util.Enumeration keys = peers.keys();
            if (keys.hasMoreElements())
            {
                peers.remove(keys.nextElement());
            }
        }
        peers.put(p.key(), p);
    }

    /**
     * Fill in a peer reference from the cache.
     *
     * Returns a peer with the access_hash and title when known. When it is not,
     * the reference is returned as-is: it can still identify a row, it just
     * cannot be used to send anything.
     */
    public Peer resolve(Peer reference)
    {
        if (reference == null)
        {
            return null;
        }
        Object hit = peers.get(reference.key());
        return hit != null ? (Peer) hit : reference;
    }

    public Peer get(int kind, long id)
    {
        Object hit = peers.get(Peer.key(kind, id));
        return hit != null ? (Peer) hit : null;
    }

    /** True when this peer can be addressed - i.e. we hold its access_hash. */
    public boolean isAddressable(Peer p)
    {
        if (p == null)
        {
            return false;
        }
        if (p.kind == Peer.CHAT)
        {
            return true;                    // basic groups need no access_hash
        }
        return p.accessHash != 0;
    }
}
