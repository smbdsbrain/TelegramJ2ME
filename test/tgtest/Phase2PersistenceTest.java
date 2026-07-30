package tgtest;

import java.io.IOException;

import tg.api.MemoryDraftStore;
import tg.api.MemoryOutgoingStore;
import tg.api.OutgoingMessage;
import tg.api.Peer;

/** Durable Phase-2 data model and UTF-8 codec tests. */
public final class Phase2PersistenceTest implements Test
{
    public String name() { return "reliability/outbox-drafts"; }

    public void run() throws Exception
    {
        codec();
        memoryOutbox();
        drafts();
        limit();
    }

    private void codec() throws Exception
    {
        OutgoingMessage message = sample("Привет 🌍");
        message.localId = 17;
        message.state = OutgoingMessage.FAILED;
        message.attempts = 3;
        message.nextAttemptAt = 1234567L;
        message.lastError = "420 FLOOD_WAIT_12";
        message.replyToMessageId = 55;
        OutgoingMessage decoded = OutgoingMessage.decode(
                message.localId, OutgoingMessage.encode(message));
        Assert.equal("codec local id", 17, decoded.localId);
        Assert.equal("codec state", OutgoingMessage.FAILED, decoded.state);
        Assert.equal("codec peer", 99, decoded.peerId);
        Assert.equal("codec access hash", 123, decoded.accessHash);
        Assert.equal("codec text", message.text, decoded.text);
        Assert.equal("codec title", message.peerTitle, decoded.peerTitle);
        Assert.equal("codec random id", 987654321L, decoded.randomId);
        Assert.equal("codec attempts", 3, decoded.attempts);
        Assert.equal("codec retry time", 1234567L, decoded.nextAttemptAt);
        Assert.equal("codec error", message.lastError, decoded.lastError);
        Assert.equal("codec reply", 55, decoded.replyToMessageId);
    }

    private void memoryOutbox() throws Exception
    {
        MemoryOutgoingStore store = new MemoryOutgoingStore();
        Peer peer = peer();
        OutgoingMessage first = store.add(peer, "one", 11, 100);
        OutgoingMessage second = store.add(peer, "two", 22, 200);
        Assert.equal("outbox count", 2, store.list().length);
        Assert.equal("FIFO first", first.localId, store.list()[0].localId);
        Assert.equal("stable random id", 11, store.list()[0].randomId);

        first.state = OutgoingMessage.SENDING;
        first.attempts = 1;
        store.save(first);
        Assert.equal("saved state", OutgoingMessage.SENDING, store.list()[0].state);
        Assert.equal("saved random id", 11, store.list()[0].randomId);

        store.remove(second.localId);
        Assert.equal("remove", 1, store.list().length);
        store.clear();
        Assert.equal("clear", 0, store.list().length);
    }

    private void drafts() throws Exception
    {
        MemoryDraftStore store = new MemoryDraftStore();
        Peer first = peer();
        Peer second = new Peer(Peer.CHAT, 5);
        store.save(first, "черновик 🌟");
        store.save(second, "other");
        Assert.equal("draft Unicode", "черновик 🌟", store.load(first));
        Assert.equal("draft per peer", "other", store.load(second));
        store.save(first, "");
        Assert.equal("empty deletes", "", store.load(first));
        Assert.equal("other survives", "other", store.load(second));
        store.clear();
        Assert.equal("draft clear", "", store.load(second));
    }

    private void limit() throws Exception
    {
        MemoryOutgoingStore store = new MemoryOutgoingStore();
        Peer peer = peer();
        for (int i = 0; i < 64; i++)
        {
            store.add(peer, "x", i, i);
        }
        try
        {
            store.add(peer, "overflow", 65, 65);
            Assert.fail("outbox accepted item 65");
        }
        catch (IOException expected) { }
    }

    private static OutgoingMessage sample(String text)
    {
        OutgoingMessage message = new OutgoingMessage();
        message.peerKind = Peer.USER;
        message.peerId = 99;
        message.accessHash = 123;
        message.peerTitle = "Сохранённые";
        message.text = text;
        message.randomId = 987654321L;
        message.createdAt = 42;
        return message;
    }

    private static Peer peer()
    {
        Peer peer = new Peer(Peer.USER, 99);
        peer.accessHash = 123;
        peer.title = "Saved Messages";
        return peer;
    }
}
