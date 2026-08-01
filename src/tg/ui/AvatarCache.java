package tg.ui;

import javax.microedition.lcdui.Image;

import tg.api.Peer;
import tg.mem.MemoryBudget;

/**
 * Small LRU of decoded dialog avatars; about 150 KiB at 48x48 and sixteen
 * entries, which is what a heap of four megabytes or more allows.
 *
 * The capacity is per instance rather than a class constant because the arrays
 * are allocated with it: a static value would have to be read before the heap
 * measurement exists, and could never be resized afterwards.
 */
public final class AvatarCache
{
    private static final int EMPTY = 0;
    private static final int LOADING = 1;
    private static final int READY = 2;
    private static final int FAILED = 3;

    private final int capacity;
    private final int[] kinds;
    private final long[] peerIds;
    private final long[] photoIds;
    private final int[] states;
    private final int[] ages;
    private final Image[] images;
    private int tick;

    public AvatarCache()
    {
        this(MemoryBudget.avatarCacheEntries());
    }

    /**
     * @param capacity entries to hold; floored at two, because a single slot
     *                 evicts on every scroll step and caches nothing
     */
    public AvatarCache(int capacity)
    {
        if (capacity < 2) { capacity = 2; }
        this.capacity = capacity;
        kinds = new int[capacity];
        peerIds = new long[capacity];
        photoIds = new long[capacity];
        states = new int[capacity];
        ages = new int[capacity];
        images = new Image[capacity];
    }

    public int capacity() { return capacity; }

    public synchronized Image get(Peer peer)
    {
        int at = find(peer);
        if (at < 0) { return null; }
        ages[at] = ++tick;
        return images[at];
    }

    /** Claims a missing entry for one loader. */
    public synchronized boolean markLoading(Peer peer)
    {
        if (!hasAvatar(peer) || find(peer) >= 0) { return false; }
        int at = slotFor(peer);
        states[at] = LOADING;
        ages[at] = ++tick;
        return true;
    }

    public synchronized void put(Peer peer, Image image)
    {
        int at = find(peer);
        if (at < 0) { at = slotFor(peer); }
        images[at] = image;
        states[at] = image == null ? FAILED : READY;
        ages[at] = ++tick;
    }

    public synchronized void fail(Peer peer)
    {
        int at = find(peer);
        if (at < 0) { at = slotFor(peer); }
        images[at] = null;
        states[at] = FAILED;
        ages[at] = ++tick;
    }

    public synchronized void clearFailures()
    {
        for (int i = 0; i < capacity; i++)
        {
            if (states[i] == FAILED) { clear(i); }
        }
    }

    public synchronized void clear()
    {
        for (int i = 0; i < capacity; i++) { clear(i); }
        tick = 0;
    }

    private int slotFor(Peer peer)
    {
        int at = slot();
        kinds[at] = peer.kind;
        peerIds[at] = peer.id;
        photoIds[at] = peer.avatar == null ? 0 : peer.avatar.photoId;
        return at;
    }

    private int slot()
    {
        int oldest = 0;
        for (int i = 0; i < capacity; i++)
        {
            if (states[i] == EMPTY) { return i; }
            if (ages[i] < ages[oldest]) { oldest = i; }
        }
        clear(oldest);
        return oldest;
    }

    private int find(Peer peer)
    {
        if (!hasAvatar(peer)) { return -1; }
        for (int i = 0; i < capacity; i++)
        {
            if (states[i] != EMPTY && kinds[i] == peer.kind
                    && peerIds[i] == peer.id
                    && photoIds[i] == peer.avatar.photoId)
            {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasAvatar(Peer peer)
    {
        return peer != null && peer.avatar != null
                && peer.avatar.photoId != 0;
    }

    private void clear(int at)
    {
        states[at] = EMPTY;
        images[at] = null;
        peerIds[at] = 0;
        photoIds[at] = 0;
        ages[at] = 0;
    }
}
