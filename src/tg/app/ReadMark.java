package tg.app;

import tg.api.Message;
import tg.api.Peer;

/**
 * How far one conversation has been read, and which conversation that is.
 *
 * <h3>Why this exists</h3>
 * {@code openHistory[0]} used to be the newest message by construction, and
 * marking read against it was correct. It stopped being correct when the
 * retained history became a <em>window</em>: reading backwards slides that
 * window off the newest end, so the head of the array is whatever the reader
 * scrolled past, not where the conversation ends. The automatic mark-read
 * already went through a separately kept high-water mark for exactly that
 * reason; the explicit {@code Mark all read} was the one path that did not, and
 * a reader who had scrolled back and pressed it marked read up to where they
 * were sitting and left everything after it unread.
 *
 * <h3>Why it is bound to a peer</h3>
 * The mark only ever rises - it has to, because an older page must not walk it
 * backwards - so nothing about the value itself says when it stops applying.
 * {@code TgMidlet.restoreScreen} adopts whichever ChatScreen is topmost on the
 * navigation stack, and the stack can hold two: opening a forwarded message's
 * source pushes a second chat over the first. Back then returns to a chat while
 * a bare {@code int} still held the other one's mark, and a channel's message
 * ids say nothing at all about a one-to-one chat. So the peer is captured with
 * the value, the same shape {@link ComposerState} uses for the composer, and
 * {@link #newestKnownIdFor} answers 0 rather than another chat's id.
 *
 * <h3>Bounds</h3>
 * Three primitives. The {@code Peer} is deliberately <em>not</em> retained -
 * unlike a composer this never has to address anything, and the identity pair is
 * all ownership needs. {@link Peer} is mutable with public fields and the same
 * conversation arrives as a fresh instance from every dialog page, so the kind
 * and id are copied here, the pair {@code TgMidlet.samePeer} compares.
 */
public final class ReadMark
{
    /** Identity frozen at capture; the {@code Peer} it came from is shared. */
    private final int peerKind;
    private final long peerId;

    /**
     * The thread the mark applies to, or 0 for the whole peer. Two topics of
     * one forum have independent read cursors, so the thread is part of the
     * identity for the same reason the peer is.
     */
    private final int threadRootId;

    /** Highest server message id ever seen in this conversation. */
    private int newestKnownId;

    private ReadMark(Peer peer, int threadRootId)
    {
        this.peerKind = peer.kind;
        this.peerId = peer.id;
        this.threadRootId = threadRootId;
    }

    /**
     * A fresh mark for {@code (peer, threadRootId)}.
     *
     * @return null when there is no chat, which is not a conversation to read
     */
    public static ReadMark forPeer(Peer peer, int threadRootId)
    {
        if (peer == null) { return null; }
        return new ReadMark(peer, threadRootId);
    }

    /** Whether {@code (other, thread)} is what this mark was taken in. */
    public boolean ownedBy(Peer other, int thread)
    {
        return other != null && other.kind == peerKind && other.id == peerId
                && thread == threadRootId;
    }

    /** Highest server message id seen here, or 0 before anything has arrived. */
    public int newestKnownId()
    {
        return newestKnownId;
    }

    /**
     * How far {@code (peer, thread)} may be marked read, or 0.
     *
     * Static and null-tolerant because "no conversation is open" and "the mark
     * belongs to the chat the reader has just come back from" are the same
     * answer at the call site: nothing this peer can be marked read up to.
     */
    public static int newestKnownIdFor(ReadMark mark, Peer peer, int thread)
    {
        return mark != null && mark.ownedBy(peer, thread)
                ? mark.newestKnownId : 0;
    }

    /**
     * Record everything {@code messages} carries.
     *
     * Called with the merged set <em>before</em> it is windowed, because a
     * message can arrive, be the newest thing this conversation has, and then
     * fall outside a window anchored two hundred messages back. It is still what
     * "read up to here" has to mean.
     *
     * @return true when the mark moved forward, which is also what clears the
     *         "a forward fetch found nothing newer" flag
     */
    public boolean note(Message[] messages)
    {
        int highest = highest(newestKnownId, 0, messages);
        if (highest <= newestKnownId) { return false; }
        newestKnownId = highest;
        return true;
    }

    /**
     * The highest id this conversation can defensibly be marked read up to.
     *
     * A numeric maximum over every trustworthy source, deliberately not the
     * first element of anything: the retained history is a window that slides,
     * and the retained chat list is a window that scrolls past, so either can be
     * absent or stale while the other is right. Marking with an id above what
     * the reader has on screen is safe when it came from the server, because the
     * server takes the maximum of it and the cursor it already has. An invented
     * id is not, which is why nothing below 1 is treated as a message.
     *
     * @param dialogTopMessageId the retained dialog row's newest message, or 0
     *                           when the chat has scrolled out of the list
     * @param retained           the retained history; null and holes allowed
     */
    public static int highest(int newestKnownId, int dialogTopMessageId,
                              Message[] retained)
    {
        int max = newestKnownId > 0 ? newestKnownId : 0;
        if (dialogTopMessageId > max) { max = dialogTopMessageId; }
        if (retained != null)
        {
            for (int i = 0; i < retained.length; i++)
            {
                Message message = retained[i];
                if (message != null && message.id > max) { max = message.id; }
            }
        }
        return max;
    }
}
