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

    /**
     * Raw thread facts from the reply header, kept unresolved on purpose: a
     * forum topic and a comment thread derive membership differently, and at
     * parse time nothing here can reliably say which kind of peer this is -
     * {@link PeerCache} is bounded and can have dropped it. The open
     * transcript applies {@link #threadRootIn} or {@link #inThread} instead.
     */
    public int replyToTopId;
    public boolean forumTopic;

    /** Comment count of a channel post, when the server offers a thread. */
    public int repliesCount;
    public boolean hasComments;

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
            readReplyHeader(m, obj.obj(Api.F_MESSAGE__REPLY_TO));
            TlObj replies = obj.obj(Api.F_MESSAGE__REPLIES);
            if (replies != null && replies.id == Api.MESSAGE_REPLIES)
            {
                m.hasComments = replies.num(Api.F_MESSAGE_REPLIES__COMMENTS) != 0;
                if (m.hasComments)
                {
                    m.repliesCount = replies.intAt(Api.F_MESSAGE_REPLIES__REPLIES);
                }
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
            readReplyHeader(m, obj.obj(Api.F_MESSAGE_SERVICE__REPLY_TO));
            return m;
        }

        // messageEmpty, or anything the schema gained since this was written.
        return null;
    }

    private static void readReplyHeader(Message m, TlObj reply)
    {
        if (reply != null && reply.id == Api.MESSAGE_REPLY_HEADER)
        {
            m.replyToMessageId = reply.intAt(
                    Api.F_MESSAGE_REPLY_HEADER__REPLY_TO_MSG_ID);
            m.replyToTopId = reply.intAt(
                    Api.F_MESSAGE_REPLY_HEADER__REPLY_TO_TOP_ID);
            m.forumTopic = reply.num(
                    Api.F_MESSAGE_REPLY_HEADER__FORUM_TOPIC) != 0;
        }
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

    /**
     * The topic this message belongs to, in a forum supergroup.
     *
     * A topic message carries the {@code forum_topic} flag; its root is
     * {@code reply_to_top_id} when the message replies to something inside
     * the topic, and {@code reply_to_msg_id} when the topic root itself is
     * what it answers. A forum message with no header at all lives in
     * General.
     *
     * @param forumPeer whether the conversation peer is a forum
     * @return the topic root id, or 0 when the peer is not a forum
     */
    public int threadRootIn(boolean forumPeer)
    {
        if (forumTopic)
        {
            return replyToTopId != 0 ? replyToTopId : replyToMessageId;
        }
        return forumPeer ? ForumTopic.GENERAL_ID : 0;
    }

    /**
     * Whether this message belongs to the comment thread rooted at
     * {@code root} in a discussion group. Discussion replies do not carry
     * the forum flag: a direct comment answers the root, a nested one names
     * it as the top.
     */
    public boolean inThread(int root)
    {
        return id == root || replyToTopId == root || replyToMessageId == root;
    }

    /**
     * The message this one visibly answers inside a {@code (peer, thread)}
     * transcript, or 0.
     *
     * Every message in a thread carries a reply header naming the thread's
     * root - that is how membership travels on the wire - so inside the
     * thread's own transcript a target equal to the root is membership, not
     * an answer, and rendering it as one captioned every plain message with
     * "Reply to" the root service message. A real reply within the thread
     * names its target in {@code reply_to_msg_id} and moves the root to
     * {@code reply_to_top_id}, which is what this keeps.
     *
     * @param threadRootId the open transcript's root, 0 for a plain chat
     */
    public int visibleReplyTo(int threadRootId)
    {
        if (threadRootId > 0 && replyToMessageId == threadRootId)
        {
            return 0;
        }
        return replyToMessageId;
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
        String visible = visibleText(false);
        return media == null ? visible : visible + " " + media.label;
    }

    /** Text for a plain widget, optionally exposing Telegram spoilers. */
    public String visibleText(boolean revealSpoilers)
    {
        String value = text == null ? "" : text;
        return revealSpoilers ? value : MessageEntity.conceal(value, entities);
    }
}
