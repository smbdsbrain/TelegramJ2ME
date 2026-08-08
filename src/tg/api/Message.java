package tg.api;

import tg.tl.TlObj;
import tg.mem.MemoryBudget;

/**
 * One message, flattened for display.
 *
 * Text, media metadata and reaction aggregates are flattened out of the
 * generated TL object. Large media bytes are never retained here.
 */
public final class Message
{
    public int id;
    public int date;
    public int editDate;
    public boolean outgoing;
    public boolean service;
    public boolean read;
    public String text = "";
    public Media media;
    public ReactionSummary[] reactions = new ReactionSummary[0];
    public MessageEntity[] entities = new MessageEntity[0];
    public ForwardInfo forwarded;
    public int replyToMessageId;

    /** Conversation this message belongs to. */
    public Peer peer;

    /** Who sent it, in a group. Null in a one-to-one chat. */
    public Peer sender;

    /**
     * Build from a {@code Message} or {@code messageService} constructor.
     *
     * @param peers may be null when the sender does not need resolving
     * @return null if the object is neither form
     */
    public static Message from(TlObj obj, PeerCache peers)
    {
        if (obj == null)
        {
            return null;
        }

        if (obj.id == Api.MESSAGE)
        {
            Message m = new Message();
            m.id = obj.intAt(Api.F_MESSAGE__ID);
            m.date = obj.intAt(Api.F_MESSAGE__DATE);
            m.editDate = obj.intAt(Api.F_MESSAGE__EDIT_DATE);
            m.outgoing = obj.num(Api.F_MESSAGE__OUT) != 0;
            m.text = obj.strOrEmpty(Api.F_MESSAGE__MESSAGE);
            m.peer = resolvePeer(obj.obj(Api.F_MESSAGE__PEER_ID), peers);
            m.media = Media.from(obj.obj(Api.F_MESSAGE__MEDIA));
            m.forwarded = ForwardInfo.from(
                    obj.obj(Api.F_MESSAGE__FWD_FROM), peers);
            TlObj reply = obj.obj(Api.F_MESSAGE__REPLY_TO);
            if (reply != null && reply.id == Api.MESSAGE_REPLY_HEADER)
            {
                m.replyToMessageId = reply.intAt(
                        Api.F_MESSAGE_REPLY_HEADER__REPLY_TO_MSG_ID);
            }
            m.reactions = ReactionSummary.from(
                    obj.obj(Api.F_MESSAGE__REACTIONS));
            m.entities = MessageEntity.fromOrDetect(
                    obj.vec(Api.F_MESSAGE__ENTITIES), m.text,
                    MemoryBudget.messageEntityLimit());
            m.sender = resolveSender(obj.obj(Api.F_MESSAGE__FROM_ID), peers);
            return m;
        }

        if (obj.id == Api.MESSAGE_SERVICE)
        {
            Message m = new Message();
            m.id = obj.intAt(Api.F_MESSAGE_SERVICE__ID);
            m.date = obj.intAt(Api.F_MESSAGE_SERVICE__DATE);
            m.outgoing = obj.num(Api.F_MESSAGE_SERVICE__OUT) != 0;
            m.service = true;
            m.peer = resolvePeer(obj.obj(Api.F_MESSAGE_SERVICE__PEER_ID), peers);
            m.text = "[service message]";
            m.sender = resolveSender(obj.obj(Api.F_MESSAGE_SERVICE__FROM_ID), peers);
            return m;
        }

        // messageEmpty, or anything the schema gained since this was written.
        return null;
    }

    private static Peer resolveSender(TlObj fromId, PeerCache peers)
    {
        Peer reference = Peer.fromPeerObj(fromId);
        if (reference == null)
        {
            return null;
        }
        return peers == null ? reference : peers.resolve(reference);
    }

    private static Peer resolvePeer(TlObj peerId, PeerCache peers)
    {
        Peer reference = Peer.fromPeerObj(peerId);
        if (reference == null) { return null; }
        return peers == null ? reference : peers.resolve(reference);
    }

    public String senderName()
    {
        if (outgoing)
        {
            return "You";
        }
        return sender == null ? "" : sender.title;
    }

    /**
     * Populate safe actions for cache records written before entities existed.
     * A non-empty server/cache vector always wins; detection only fills the
     * compatibility default used by v1 and by servers that omit entities.
     */
    public MessageEntity[] ensureEntities()
    {
        if (entities == null || entities.length == 0)
        {
            entities = MessageEntity.detect(text,
                    MemoryBudget.messageEntityLimit());
        }
        return entities;
    }

    /** Local visibility rule; the server remains authoritative on permissions. */
    public boolean canEditText()
    {
        return id > 0 && outgoing && !service && media == null
                && text != null && text.length() > 0;
    }

    public String toString()
    {
        return senderName() + ": " + summaryText();
    }

    /** One-line representation for dialog lists and message pickers. */
    public String summaryText()
    {
        if (text.length() == 0)
        {
            return media == null ? "" : media.label;
        }
        return media == null ? text : text + " " + media.label;
    }
}
