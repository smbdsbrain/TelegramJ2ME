package tgtest;

import tg.api.Api;
import tg.api.Peer;
import tg.api.Requests;
import tg.tl.TlReader;

/** Wire shapes of the forum topic and comment thread requests. */
public final class RequestsForumTest implements Test
{
    public String name() { return "forum/request-wire"; }

    public void run() throws Exception
    {
        forumTopicsWire();
        repliesWire();
        discussionWire();
        sendIntoThreadWire();
    }

    private static void forumTopicsWire() throws Exception
    {
        TlReader r = new TlReader(Requests.getForumTopics(
                channel(), 1234, 55, 66, 20));
        Assert.equal("method", Api.MESSAGES_GET_FORUM_TOPICS, r.readInt());
        Assert.equal("flags carry no query", 0, r.readInt());
        Assert.equal("peer type", Api.INPUT_PEER_CHANNEL, r.readInt());
        Assert.equal("peer id", 77L, r.readLong());
        Assert.equal("peer hash", 88L, r.readLong());
        Assert.equal("offset_date", 1234, r.readInt());
        Assert.equal("offset_id", 55, r.readInt());
        Assert.equal("offset_topic", 66, r.readInt());
        Assert.equal("limit", 20, r.readInt());
    }

    private static void repliesWire() throws Exception
    {
        TlReader older = new TlReader(Requests.getRepliesBefore(
                channel(), 400, 77, 30));
        Assert.equal("older method", Api.MESSAGES_GET_REPLIES, older.readInt());
        older.readInt();
        older.readLong();
        older.readLong();
        Assert.equal("older thread root", 400, older.readInt());
        Assert.equal("older offset", 77, older.readInt());
        Assert.equal("older offset_date", 0, older.readInt());
        Assert.equal("older pages walk forwards from the offset",
                0, older.readInt());
        Assert.equal("older limit", 30, older.readInt());
        Assert.equal("older max_id", 0, older.readInt());
        Assert.equal("older min_id", 0, older.readInt());
        Assert.equal("older hash", 0L, older.readLong());

        // The first page is the older form anchored at 0, like getHistory.
        Assert.bytesEqual("latest is before-zero",
                Requests.getRepliesBefore(channel(), 400, 0, 30),
                Requests.getRepliesLatest(channel(), 400, 30));

        TlReader newer = new TlReader(Requests.getRepliesAfter(
                channel(), 400, 77, 30));
        newer.readInt();
        newer.readInt();
        newer.readLong();
        newer.readLong();
        newer.readInt();
        Assert.equal("newer offset", 77, newer.readInt());
        newer.readInt();
        Assert.equal("newer pages walk backwards a whole page",
                -30, newer.readInt());

        TlReader around = new TlReader(Requests.getRepliesAround(
                channel(), 400, 505, 30));
        around.readInt();
        around.readInt();
        around.readLong();
        around.readLong();
        Assert.equal("around thread root", 400, around.readInt());
        Assert.equal("around centre", 505, around.readInt());
        around.readInt();
        Assert.equal("around walks back half a page", -15, around.readInt());
    }

    private static void discussionWire() throws Exception
    {
        TlReader map = new TlReader(Requests.getDiscussionMessage(
                channel(), 55));
        Assert.equal("map method", Api.MESSAGES_GET_DISCUSSION_MESSAGE,
                map.readInt());
        Assert.equal("map peer", Api.INPUT_PEER_CHANNEL, map.readInt());
        map.readLong();
        map.readLong();
        Assert.equal("map post", 55, map.readInt());

        TlReader read = new TlReader(Requests.readDiscussion(
                channel(), 400, 610));
        Assert.equal("read method", Api.MESSAGES_READ_DISCUSSION,
                read.readInt());
        Assert.equal("read peer", Api.INPUT_PEER_CHANNEL, read.readInt());
        read.readLong();
        read.readLong();
        Assert.equal("read thread root", 400, read.readInt());
        Assert.equal("read cursor", 610, read.readInt());
    }

    private static void sendIntoThreadWire() throws Exception
    {
        Peer to = channel();

        // A plain send into a topic roots the reply header at the topic.
        TlReader plain = new TlReader(Requests.sendMessage(
                to, "hello", 123L, 0, 400));
        Assert.equal("topic method", Api.MESSAGES_SEND_MESSAGE, plain.readInt());
        Assert.equal("topic flags", 1, plain.readInt());
        plain.readInt();
        plain.readLong();
        plain.readLong();
        Assert.equal("topic reply ctor", Api.INPUT_REPLY_TO_MESSAGE,
                plain.readInt());
        Assert.equal("topic inner flags", 1, plain.readInt());
        Assert.equal("topic reply id is the root", 400, plain.readInt());
        Assert.equal("topic top_msg_id", 400, plain.readInt());
        Assert.equal("topic text", "hello", plain.readString());

        // A reply inside a topic keeps its target and names the topic.
        TlReader reply = new TlReader(Requests.sendMessage(
                to, "hello", 123L, 7, 400));
        reply.readInt();
        Assert.equal("reply flags", 1, reply.readInt());
        reply.readInt();
        reply.readLong();
        reply.readLong();
        Assert.equal("reply ctor", Api.INPUT_REPLY_TO_MESSAGE, reply.readInt());
        Assert.equal("reply inner flags", 1, reply.readInt());
        Assert.equal("reply target kept", 7, reply.readInt());
        Assert.equal("reply top_msg_id", 400, reply.readInt());

        // General needs no header of its own: both forms are byte-identical
        // to what a plain chat sends today.
        Assert.bytesEqual("send into General is a plain send",
                Requests.sendMessage(to, "hello", 123L, 0),
                Requests.sendMessage(to, "hello", 123L, 0, 1));
        Assert.bytesEqual("reply in General is a plain reply",
                Requests.sendMessage(to, "hello", 123L, 7),
                Requests.sendMessage(to, "hello", 123L, 7, 1));
        Assert.bytesEqual("thread zero is no thread",
                Requests.sendMessage(to, "hello", 123L, 7),
                Requests.sendMessage(to, "hello", 123L, 7, 0));
    }

    private static Peer channel()
    {
        Peer peer = new Peer(Peer.CHANNEL, 77);
        peer.accessHash = 88;
        return peer;
    }
}
