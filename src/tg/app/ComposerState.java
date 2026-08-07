package tg.app;

import tg.api.Peer;

/**
 * What one composer session is for: a chat, a mode, and the message it answers.
 *
 * <h3>Why this exists</h3>
 * Reply used to be a bare {@code Message replyTarget} field beside the reused
 * compose {@code TextBox} and the mutable {@code openPeer}. Nothing tied the
 * three together, so nothing could go wrong in one place: it went wrong on the
 * one exit that cleared nothing. Pressing Send on an empty box left reply mode
 * armed, and the next Write - in whatever chat the user had walked to by then -
 * came up holding the first chat's message id and sent it there.
 *
 * So the peer and the message id are captured together, at the moment the
 * composer is opened, and they are never read apart. There is no valid state in
 * which a reply id exists without the chat it belongs to.
 *
 * <h3>Why it is a value, not a holder</h3>
 * Immutable, with {@code null} meaning "no composer is open". That makes the
 * reference itself the session token: the outbox callback captures the state it
 * submitted with and compares it by identity before cleaning anything up, so a
 * composer the user reopened in the meantime is not closed, and its draft is not
 * erased, by the previous send. A mutable holder would need a generation counter
 * to say the same thing. It also gives the three-second draft autosave thread a
 * consistent snapshot from one volatile read rather than five racing fields.
 *
 * <h3>Why the peer is kept whole but compared by parts</h3>
 * {@code Telegram.enqueueMessage} and {@code DraftStore} both need the
 * {@code access_hash}, so the reference is retained - and deliberately not
 * re-resolved at send time, because {@link tg.api.PeerCache} is bounded and
 * evicts, which would turn a long compose session into an unsendable message.
 * But {@code Peer} is mutable with public fields and the same conversation
 * arrives as a fresh instance from every dialog page, so ownership is decided on
 * the kind and id copied here, the same pair {@code TgMidlet.samePeer} and the
 * draft record use.
 *
 * <h3>Why the mode is a field</h3>
 * Today it is derivable - a reply id is positive and a Write has none. It is a
 * field because the next mode will not be: editing an outgoing message is also
 * a peer and a message id, and "edit #5" must not be able to arrive at the wire
 * as "reply to #5". {@link #replyToMessageId()} answers 0 for every mode but
 * reply, so a mode added later cannot leak its id into the reply slot by
 * forgetting a check.
 *
 * <h3>Bounds</h3>
 * Five fields, one retained {@code Peer}, no {@code Message}. The reply label is
 * built from the id alone, so it stays correct and short after the message it
 * answers has been evicted from the retained history window.
 */
public final class ComposerState
{
    private static final int MODE_WRITE = 0;
    private static final int MODE_REPLY = 1;

    private final int mode;

    /** The chat this composer sends to; retained for its {@code access_hash}. */
    private final Peer peer;

    /** Identity frozen at capture; {@code peer} is mutable and shared. */
    private final int peerKind;
    private final long peerId;

    /** The message this mode acts on, or 0 for an ordinary Write. */
    private final int targetMessageId;

    private ComposerState(int mode, Peer peer, int targetMessageId)
    {
        this.mode = mode;
        this.peer = peer;
        this.peerKind = peer.kind;
        this.peerId = peer.id;
        this.targetMessageId = targetMessageId;
    }

    /**
     * An ordinary message to {@code peer}.
     *
     * @return null when there is no chat to send to, which is not a composer
     */
    public static ComposerState write(Peer peer)
    {
        if (peer == null) { return null; }
        return new ComposerState(MODE_WRITE, peer, 0);
    }

    /**
     * A reply to {@code messageId} in {@code peer}.
     *
     * Refused rather than downgraded to a Write: a caller that asked for a reply
     * and silently got an ordinary message would send the text to the right chat
     * with the wrong meaning, which is the failure this class exists to stop.
     *
     * @param messageId a server message id; ids are positive, and a queued
     *                  message that has no id yet cannot be replied to
     * @return null when the chat is missing or the id is not a message
     */
    public static ComposerState reply(Peer peer, int messageId)
    {
        if (peer == null || messageId <= 0) { return null; }
        return new ComposerState(MODE_REPLY, peer, messageId);
    }

    /** The chat this composer was opened for. Never null. */
    public Peer peer()
    {
        return peer;
    }

    /** Whether {@code other} is the chat this composer was opened for. */
    public boolean ownedBy(Peer other)
    {
        return other != null && other.kind == peerKind && other.id == peerId;
    }

    /** The reply id for the wire, or 0 in every mode that is not a reply. */
    public int replyToMessageId()
    {
        return mode == MODE_REPLY ? targetMessageId : 0;
    }

    /** The compose screen's title. Bounded, and holds no message text. */
    public String title()
    {
        switch (mode)
        {
            case MODE_REPLY:
                return "Reply to #" + targetMessageId;
            default:
                return "Message";
        }
    }
}
