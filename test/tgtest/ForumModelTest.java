package tgtest;

import tg.api.Api;
import tg.api.ForumTopic;
import tg.api.Message;
import tg.api.Peer;
import tg.tl.TlObj;

/**
 * Thread facts on the model: topic mapping of messages, comment-thread
 * membership, the channel forum flag, and forum topic rows.
 */
public final class ForumModelTest implements Test
{
    public String name() { return "forum/model-mapping"; }

    public void run() throws Exception
    {
        topicMappingFromReplyHeader();
        serviceMessagesMapLikeOrdinaryOnes();
        visibleRepliesInsideAThread();
        commentThreadMembership();
        channelForumFlag();
        forumTopicRows();
        commentCounts();
    }

    private static void topicMappingFromReplyHeader() throws Exception
    {
        // A nested reply names the topic in reply_to_top_id.
        Message nested = Message.from(message(600, reply(500, 400, true)), null);
        Assert.equal("nested reply target", 500, nested.replyToMessageId);
        Assert.equal("nested topic root", 400, nested.threadRootIn(true));

        // A direct answer to the topic root carries only reply_to_msg_id.
        Message direct = Message.from(message(601, reply(400, 0, true)), null);
        Assert.equal("direct topic root", 400, direct.threadRootIn(true));

        // No header at all: General in a forum, no thread anywhere else.
        Message bare = Message.from(message(602, null), null);
        Assert.equal("bare message lives in General",
                ForumTopic.GENERAL_ID, bare.threadRootIn(true));
        Assert.equal("bare message has no thread outside a forum",
                0, bare.threadRootIn(false));

        // A plain reply without the forum flag is not topic routing.
        Message plain = Message.from(message(603, reply(42, 0, false)), null);
        Assert.equal("plain reply keeps its target", 42, plain.replyToMessageId);
        Assert.equal("plain reply maps to General in a forum",
                ForumTopic.GENERAL_ID, plain.threadRootIn(true));
        Assert.equal("plain reply maps nowhere outside one",
                0, plain.threadRootIn(false));
    }

    private static void serviceMessagesMapLikeOrdinaryOnes() throws Exception
    {
        // A pin or join notice inside a topic carries the same header; unread,
        // it would land in General.
        TlObj service = obj(Api.MESSAGE_SERVICE,
                Api.F_MESSAGE_SERVICE__DATE + 1);
        service.nums[Api.F_MESSAGE_SERVICE__ID] = 700;
        service.refs[Api.F_MESSAGE_SERVICE__PEER_ID] = peerChannel(9);
        service.refs[Api.F_MESSAGE_SERVICE__REPLY_TO] = reply(400, 0, true);
        Message m = Message.from(service, null);
        Assert.isTrue("service form recognised", m.service);
        Assert.equal("service message topic root", 400, m.threadRootIn(true));
    }

    private static void visibleRepliesInsideAThread() throws Exception
    {
        // A plain topic message: the header names the root, and inside the
        // topic that is membership, not an answer.
        Message plain = Message.from(message(601, reply(400, 0, true)), null);
        Assert.equal("a plain topic message answers nothing visibly",
                0, plain.visibleReplyTo(400));
        Assert.equal("outside the thread the target stays visible",
                400, plain.visibleReplyTo(0));

        // A real reply keeps its target either way.
        Message nested = Message.from(message(602, reply(555, 400, true)), null);
        Assert.equal("a real reply keeps its target in the topic",
                555, nested.visibleReplyTo(400));

        // A direct comment answers the discussion root - membership again.
        Message comment = Message.from(message(101, reply(100, 0, false)), null);
        Assert.equal("a direct comment answers nothing visibly",
                0, comment.visibleReplyTo(100));
        Assert.equal("an ordinary reply in a plain chat is untouched",
                100, comment.visibleReplyTo(0));
    }

    private static void commentThreadMembership() throws Exception
    {
        Message root = Message.from(message(400, null), null);
        Message direct = Message.from(message(401, reply(400, 0, false)), null);
        Message nested = Message.from(message(402, reply(401, 400, false)), null);
        Message other = Message.from(message(403, reply(300, 0, false)), null);

        Assert.isTrue("root belongs to its own thread", root.inThread(400));
        Assert.isTrue("direct comment belongs", direct.inThread(400));
        Assert.isTrue("nested comment belongs", nested.inThread(400));
        Assert.isFalse("a stranger does not", other.inThread(400));
        Assert.isFalse("membership is per root", direct.inThread(300));
    }

    private static void channelForumFlag() throws Exception
    {
        TlObj channel = obj(Api.CHANNEL, Api.F_CHANNEL__PHOTO + 1);
        channel.nums[Api.F_CHANNEL__ID] = 77;
        channel.nums[Api.F_CHANNEL__ACCESS_HASH] = 88;
        channel.refs[Api.F_CHANNEL__TITLE] = "Topics";
        channel.nums[Api.F_CHANNEL__FORUM] = 1;
        Peer forum = Peer.fromChat(channel);
        Assert.isTrue("forum flag read", forum.forum);

        channel.nums[Api.F_CHANNEL__FORUM] = 0;
        Assert.isFalse("plain channel stays plain", Peer.fromChat(channel).forum);

        TlObj forbidden = obj(Api.CHANNEL_FORBIDDEN,
                Api.F_CHANNEL_FORBIDDEN__TITLE + 1);
        forbidden.nums[Api.F_CHANNEL_FORBIDDEN__ID] = 77;
        Assert.isFalse("forbidden form has no forum flag",
                Peer.fromChat(forbidden).forum);
    }

    private static void forumTopicRows() throws Exception
    {
        TlObj raw = obj(Api.FORUM_TOPIC, Api.F_FORUM_TOPIC__UNREAD_COUNT + 1);
        raw.nums[Api.F_FORUM_TOPIC__ID] = 400;
        raw.nums[Api.F_FORUM_TOPIC__DATE] = 1234;
        raw.refs[Api.F_FORUM_TOPIC__TITLE] = "Hardware";
        raw.nums[Api.F_FORUM_TOPIC__CLOSED] = 1;
        raw.nums[Api.F_FORUM_TOPIC__PINNED] = 1;
        raw.nums[Api.F_FORUM_TOPIC__TOP_MESSAGE] = 600;
        raw.nums[Api.F_FORUM_TOPIC__READ_INBOX_MAX_ID] = 590;
        raw.nums[Api.F_FORUM_TOPIC__UNREAD_COUNT] = 3;

        ForumTopic topic = ForumTopic.from(raw);
        Assert.equal("topic id", 400, topic.id);
        Assert.equal("topic title", "Hardware", topic.title);
        Assert.isTrue("closed read", topic.closed);
        Assert.isTrue("pinned read", topic.pinned);
        Assert.isFalse("hidden defaults off", topic.hidden);
        Assert.equal("top message", 600, topic.topMessageId);
        Assert.equal("read cursor", 590, topic.readInboxMaxId);
        Assert.equal("unread badge", 3, topic.unreadCount);
        Assert.equal("date seeds the preview date", 1234, topic.lastDate);

        // A deleted topic has no row.
        TlObj deleted = obj(Api.FORUM_TOPIC_DELETED,
                Api.F_FORUM_TOPIC_DELETED__ID + 1);
        deleted.nums[Api.F_FORUM_TOPIC_DELETED__ID] = 401;
        Assert.isTrue("deleted topic skipped", ForumTopic.from(deleted) == null);

        // A short topic may omit the title; an empty row reads as a bug.
        TlObj untitled = obj(Api.FORUM_TOPIC,
                Api.F_FORUM_TOPIC__UNREAD_COUNT + 1);
        untitled.nums[Api.F_FORUM_TOPIC__ID] = ForumTopic.GENERAL_ID;
        Assert.equal("untitled General named",
                "General", ForumTopic.from(untitled).title);
        untitled.nums[Api.F_FORUM_TOPIC__ID] = 402;
        Assert.equal("untitled topic named by id",
                "Topic 402", ForumTopic.from(untitled).title);
    }

    private static void commentCounts() throws Exception
    {
        TlObj raw = message(55, null);
        TlObj replies = obj(Api.MESSAGE_REPLIES,
                Api.F_MESSAGE_REPLIES__REPLIES + 1);
        replies.nums[Api.F_MESSAGE_REPLIES__COMMENTS] = 1;
        replies.nums[Api.F_MESSAGE_REPLIES__REPLIES] = 12;
        raw.refs[Api.F_MESSAGE__REPLIES] = replies;
        Message post = Message.from(raw, null);
        Assert.isTrue("comments offered", post.hasComments);
        Assert.equal("comment count", 12, post.repliesCount);

        // A reply tally without the comments flag is a same-group thread
        // counter, not an entry point.
        replies.nums[Api.F_MESSAGE_REPLIES__COMMENTS] = 0;
        Message counted = Message.from(raw, null);
        Assert.isFalse("no comments entry", counted.hasComments);
        Assert.equal("count not retained without the flag",
                0, counted.repliesCount);
    }

    // ------------------------------------------------------------ fixtures

    private static TlObj message(int id, TlObj replyHeader)
    {
        TlObj message = obj(Api.MESSAGE, Api.F_MESSAGE__REACTIONS + 1);
        message.nums[Api.F_MESSAGE__ID] = id;
        message.refs[Api.F_MESSAGE__MESSAGE] = "text";
        message.refs[Api.F_MESSAGE__PEER_ID] = peerChannel(9);
        if (replyHeader != null)
        {
            message.refs[Api.F_MESSAGE__REPLY_TO] = replyHeader;
        }
        return message;
    }

    private static TlObj reply(int msgId, int topId, boolean forumTopic)
    {
        TlObj reply = obj(Api.MESSAGE_REPLY_HEADER,
                Api.F_MESSAGE_REPLY_HEADER__REPLY_TO_TOP_ID + 1);
        reply.nums[Api.F_MESSAGE_REPLY_HEADER__REPLY_TO_MSG_ID] = msgId;
        reply.nums[Api.F_MESSAGE_REPLY_HEADER__REPLY_TO_TOP_ID] = topId;
        reply.nums[Api.F_MESSAGE_REPLY_HEADER__FORUM_TOPIC] = forumTopic ? 1 : 0;
        return reply;
    }

    private static TlObj peerChannel(long id)
    {
        TlObj peer = obj(Api.PEER_CHANNEL, Api.F_PEER_CHANNEL__CHANNEL_ID + 1);
        peer.nums[Api.F_PEER_CHANNEL__CHANNEL_ID] = id;
        return peer;
    }

    private static TlObj obj(int id, int fields)
    {
        TlObj value = new TlObj(id, fields);
        value.refs = new Object[fields];
        return value;
    }
}
