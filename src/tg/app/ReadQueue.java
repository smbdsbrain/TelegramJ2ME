package tg.app;

import tg.api.Peer;
import tg.diag.Diag;

/**
 * Read acknowledgements waiting for their round trip, one entry per chat.
 *
 * <h3>Why this exists</h3>
 * This was one slot. A producer that found it holding another conversation
 * replaced the entry instead of queueing beside it, and the window for that is
 * not a scheduling accident - it is a whole {@code readHistory} round trip. The
 * drain takes the slot, empties it, and only then goes to the network, which on
 * the measured GPRS link is seconds. Anything read in that time lands in the
 * empty slot, and the next chat the reader opens overwrites it.
 *
 * The loss that follows is the ordinary case rather than a rare one: a message
 * arrives in a chat the reader is sitting in, they walk back to the list and
 * open another, and the first is still bold on every other device they own.
 * Nothing retries it, because nothing knows an acknowledgement was pending.
 *
 * <h3>Why it coalesces</h3>
 * A read cursor is monotonic and {@code messages.readHistory} takes the maximum
 * of the id sent and the one the server already holds, so two acknowledgements
 * for one conversation are one acknowledgement for its highest id. That is what
 * keeps {@link #CAPACITY} off the common path: reading the same chat a hundred
 * times is one entry, not a hundred.
 *
 * <h3>Bounds</h3>
 * {@link #CAPACITY} conversations. It is one entry per chat the reader can open
 * while a single round trip is in flight, so on a link slow enough to matter it
 * is already more than a keypad allows. The bound is small enough to be reached,
 * so reaching it is counted and logged rather than assumed away: what goes is
 * the oldest - the conversation they have left, not the one on screen - and it
 * is recoverable, because opening that chat again acknowledges it.
 *
 * <h3>Why the drain flag lives here</h3>
 * "The queue is empty" and "no thread is draining it" have to be one decision
 * under one lock. Split across two objects, a producer can arrive between the
 * drain seeing an empty queue and clearing its flag, and leave an entry nobody
 * will ever send.
 */
public final class ReadQueue
{
    /** Conversations that can be waiting at once. */
    public static final int CAPACITY = 8;

    /** What a drained acknowledgement is handed to. */
    public interface Sink
    {
        /**
         * Acknowledge {@code peer} up to {@code maxId}.
         *
         * Implementations report their own failures: a read that does not come
         * back is best effort and is not worth interrupting the reader for.
         */
        void markRead(Peer peer, int maxId);
    }

    private final Peer[] peers = new Peer[CAPACITY];
    private final int[] maxIds = new int[CAPACITY];
    private int count;

    /** A thread is draining; a second producer must not start another. */
    private boolean draining;

    /** Entries the bound cost, for the diagnostic that reports them. */
    private int dropped;

    /**
     * Queue an acknowledgement, or raise the one this chat already has.
     *
     * @param peer  the conversation; must be addressable, since the drain sends
     *              it to the wire without resolving it again
     * @return true when the caller must start the drain thread
     */
    public synchronized boolean offer(Peer peer, int maxId)
    {
        if (peer == null || maxId <= 0) { return false; }
        for (int i = 0; i < count; i++)
        {
            if (peers[i].kind == peer.kind && peers[i].id == peer.id)
            {
                if (maxId > maxIds[i]) { maxIds[i] = maxId; }
                return startDraining();
            }
        }
        if (count == CAPACITY)
        {
            dropped++;
            // Said out loud: a bound that silently discards work reads as one
            // that was never reached. The chat is not named - a title is a
            // contact's name and does not belong in a diagnostic.
            Diag.warn("read queue full, dropped the oldest acknowledgement ("
                    + dropped + " so far)");
            removeFirst();
        }
        peers[count] = peer;
        maxIds[count] = maxId;
        count++;
        return startDraining();
    }

    /**
     * Hand the oldest acknowledgement to {@code sink}.
     *
     * @return false when the queue is dry, which is also what ends the drain
     */
    public boolean drainOne(Sink sink)
    {
        Peer peer;
        int maxId;
        synchronized (this)
        {
            if (count == 0)
            {
                draining = false;
                return false;
            }
            peer = peers[0];
            maxId = maxIds[0];
            removeFirst();
        }
        // Outside the lock: this is a network round trip, and holding the lock
        // across it would block every producer for its duration.
        try { sink.markRead(peer, maxId); }
        catch (Throwable ignored)
        {
            // The sink reports its own failures. Catching here only keeps one
            // refused acknowledgement from taking the rest of the queue - and
            // the drain flag - down with it.
        }
        return true;
    }

    /** Conversations waiting. */
    public synchronized int size()
    {
        return count;
    }

    /** Acknowledgements the bound has cost since the last {@link #clear}. */
    public synchronized int dropped()
    {
        return dropped;
    }

    /**
     * Forget everything.
     *
     * A queued {@code Peer} carries a contact's name, so this is account data
     * and a logout has to reach it.
     */
    public synchronized void clear()
    {
        for (int i = 0; i < count; i++) { peers[i] = null; }
        count = 0;
        dropped = 0;
    }

    private boolean startDraining()
    {
        if (draining) { return false; }
        draining = true;
        return true;
    }

    private void removeFirst()
    {
        System.arraycopy(peers, 1, peers, 0, count - 1);
        System.arraycopy(maxIds, 1, maxIds, 0, count - 1);
        count--;
        peers[count] = null;
    }
}
