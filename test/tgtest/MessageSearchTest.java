package tgtest;

import tg.api.Api;
import tg.api.MessageSearchPage;
import tg.api.Peer;
import tg.api.PeerCache;
import tg.api.Requests;
import tg.tl.TlObj;
import tg.tl.TlReader;

/** Wire, peer ingestion and bounded paging for messages.search. */
public final class MessageSearchTest implements Test
{
    public String name() { return "search/in-chat-messages"; }

    public void run() throws Exception
    {
        requestWire();
        boundedPageAndPeerResolution();
        repeatedOffsetStops();
    }

    private static void requestWire() throws Exception
    {
        Peer peer = new Peer(Peer.USER, 42);
        peer.accessHash = 84;
        TlReader r = new TlReader(Requests.searchMessages(
                peer, "needle", 77, -2, 8));
        Assert.equal("method", Api.MESSAGES_SEARCH, r.readInt());
        Assert.equal("flags", 0, r.readInt());
        Assert.equal("peer type", Api.INPUT_PEER_USER, r.readInt());
        Assert.equal("peer id", 42L, r.readLong());
        Assert.equal("peer hash", 84L, r.readLong());
        Assert.equal("query", "needle", r.readString());
        Assert.equal("empty filter", Api.INPUT_MESSAGES_FILTER_EMPTY,
                r.readInt());
        Assert.equal("min date", 0, r.readInt());
        Assert.equal("max date", 0, r.readInt());
        Assert.equal("offset id", 77, r.readInt());
        Assert.equal("add offset", -2, r.readInt());
        Assert.equal("limit", 8, r.readInt());
        Assert.equal("max id", 0, r.readInt());
        Assert.equal("min id", 0, r.readInt());
        Assert.equal("hash", 0L, r.readLong());
    }

    private static void boundedPageAndPeerResolution()
    {
        TlObj user = obj(Api.USER, Api.F_USER__BOT_ACTIVE_USERS + 1);
        user.nums[Api.F_USER__ID] = 9;
        user.nums[Api.F_USER__ACCESS_HASH] = 99;
        user.refs[Api.F_USER__FIRST_NAME] = "Sender";

        TlObj one = message(30, 9, "first");
        TlObj duplicate = message(30, 9, "duplicate");
        TlObj two = message(20, 9, "second");
        TlObj reply = obj(Api.MESSAGES_MESSAGES_SLICE,
                Api.F_MESSAGES_MESSAGES_SLICE__USERS + 1);
        reply.nums[Api.F_MESSAGES_MESSAGES_SLICE__COUNT] = 100;
        reply.nums[Api.F_MESSAGES_MESSAGES_SLICE__INEXACT] = 1;
        reply.refs[Api.F_MESSAGES_MESSAGES_SLICE__MESSAGES] =
                new TlObj[] { one, duplicate, two };
        reply.refs[Api.F_MESSAGES_MESSAGES_SLICE__CHATS] = new TlObj[0];
        reply.refs[Api.F_MESSAGES_MESSAGES_SLICE__USERS] =
                new TlObj[] { user };

        MessageSearchPage page = MessageSearchPage.from(
                reply, new PeerCache(), 2, 0);
        Assert.equal("dedupe and bound", 2, page.messages.length);
        Assert.equal("sender absorbed before flatten", "Sender",
                page.messages[0].senderName());
        Assert.equal("stable next offset", 20, page.nextOffsetId);
        Assert.equal("server total", 100, page.totalCount);
        Assert.isFalse("inexact total retained", page.totalExact);
        Assert.isFalse("full page may continue", page.exhausted);
    }

    private static void repeatedOffsetStops()
    {
        TlObj reply = obj(Api.MESSAGES_MESSAGES,
                Api.F_MESSAGES_MESSAGES__USERS + 1);
        reply.refs[Api.F_MESSAGES_MESSAGES__MESSAGES] =
                new TlObj[] { message(77, 0, "same") };
        reply.refs[Api.F_MESSAGES_MESSAGES__CHATS] = new TlObj[0];
        reply.refs[Api.F_MESSAGES_MESSAGES__USERS] = new TlObj[0];
        MessageSearchPage page = MessageSearchPage.from(
                reply, new PeerCache(), 1, 77);
        Assert.isTrue("same offset terminates loop", page.exhausted);
    }

    private static TlObj message(int id, long sender, String text)
    {
        int fields = Api.F_MESSAGE__REACTIONS + 1;
        TlObj message = obj(Api.MESSAGE, fields);
        message.nums[Api.F_MESSAGE__ID] = id;
        message.refs[Api.F_MESSAGE__MESSAGE] = text;
        message.refs[Api.F_MESSAGE__PEER_ID] = peerUser(1);
        if (sender != 0) { message.refs[Api.F_MESSAGE__FROM_ID] = peerUser(sender); }
        return message;
    }

    private static TlObj peerUser(long id)
    {
        TlObj peer = obj(Api.PEER_USER, Api.F_PEER_USER__USER_ID + 1);
        peer.nums[Api.F_PEER_USER__USER_ID] = id;
        return peer;
    }

    private static TlObj obj(int id, int fields)
    {
        TlObj value = new TlObj(id, fields);
        value.refs = new Object[fields];
        return value;
    }
}
