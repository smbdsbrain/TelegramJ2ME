package tgtest;

import tg.api.ForumTopic;
import tg.api.MemoryDraftStore;
import tg.api.OutgoingMessage;
import tg.api.Peer;
import tg.app.AsyncScope;
import tg.app.ComposerState;
import tg.app.LocalReads;
import tg.app.ReadMark;
import tg.app.ReadQueue;
import tg.tl.TlWriter;

/**
 * The thread half of a conversation's identity, everywhere it is part of one:
 * two topics of one forum are different transcripts at the same peer, and
 * every guard that told conversations apart by peer alone has to tell them
 * apart too.
 */
public final class ThreadIdentityTest implements Test
{
    public String name() { return "forum/thread-identity"; }

    public void run() throws Exception
    {
        scopeBumpsOnThreadMoves();
        composerOwnsOneThread();
        readMarkIsPerThread();
        readQueueCoalescesPerThread();
        localReadsTellThreadsApart();
        outboxCarriesTheThread();
        draftsAreKeyedByThread();
    }

    private static void scopeBumpsOnThreadMoves()
    {
        AsyncScope scope = new AsyncScope();
        Peer forum = new Peer(Peer.CHANNEL, 77);

        // A page in flight for topic 400 survives a rebind to the same topic -
        // Back out and straight in again must not cost a Refresh.
        scope.chatChanged(forum, 400);
        AsyncScope.Token page = scope.capture(forum, 400);
        scope.chatChanged(forum, 400);
        Assert.isTrue("same (peer, thread) rebind keeps the token",
                page.sameChat(forum, 400));

        // A different topic of the same forum is a different conversation: its
        // pages come from other offsets, and this one must be dropped.
        scope.chatChanged(forum, 401);
        Assert.isFalse("a different thread of the same peer bumps",
                page.sameChat(forum, 401));

        // And a detour through another topic drops the first topic's page even
        // though the reader came back to it.
        AsyncScope.Token second = scope.capture(forum, 401);
        scope.chatChanged(forum, 400);
        Assert.isFalse("a detour through another thread drops its page",
                second.sameChat(forum, 401));

        // The plain transcript and General are also distinct conversations.
        AsyncScope flat = new AsyncScope();
        flat.chatChanged(forum, 0);
        AsyncScope.Token plain = flat.capture(forum, 0);
        flat.chatChanged(forum, ForumTopic.GENERAL_ID);
        Assert.isFalse("thread zero and General are told apart",
                plain.sameChat(forum, ForumTopic.GENERAL_ID));
    }

    private static void composerOwnsOneThread()
    {
        Peer forum = new Peer(Peer.CHANNEL, 77);
        forum.accessHash = 88;

        ComposerState write = ComposerState.write(forum, 400);
        Assert.equal("write captures the thread", 400, write.threadRootId());
        Assert.isTrue("owned by its topic", write.ownedBy(forum, 400));
        Assert.isFalse("not by another topic", write.ownedBy(forum, 401));
        Assert.isFalse("not by the flat transcript", write.ownedBy(forum, 0));

        ComposerState reply = ComposerState.reply(forum, 400, 555);
        Assert.equal("reply keeps its target", 555, reply.replyToMessageId());
        Assert.equal("and its thread", 400, reply.threadRootId());

        ComposerState edit = ComposerState.edit(forum, 400, 555, "text");
        Assert.equal("edit carries the thread too", 400, edit.threadRootId());
        Assert.equal("without leaking it into the reply slot",
                0, edit.replyToMessageId());
    }

    private static void readMarkIsPerThread()
    {
        Peer forum = new Peer(Peer.CHANNEL, 77);
        ReadMark mark = ReadMark.forPeer(forum, 400);
        mark.note(new tg.api.Message[] { message(600) });

        Assert.equal("the mark answers its own thread", 600,
                ReadMark.newestKnownIdFor(mark, forum, 400));
        Assert.equal("another topic gets nothing", 0,
                ReadMark.newestKnownIdFor(mark, forum, 401));
        Assert.equal("the flat transcript gets nothing either", 0,
                ReadMark.newestKnownIdFor(mark, forum, 0));
    }

    private static void readQueueCoalescesPerThread()
    {
        ReadQueue queue = new ReadQueue();
        Peer forum = new Peer(Peer.CHANNEL, 77);

        // Two topics of one forum hold independent cursors: the second offer
        // must queue beside the first, not raise it.
        queue.offer(forum, 400, 600);
        queue.offer(forum, 401, 700);
        queue.offer(forum, 400, 650);
        Assert.equal("topics queue apart, same topic coalesces",
                2, queue.size());

        final int[] drained = new int[4];
        final int[] at = { 0 };
        ReadQueue.Sink sink = new ReadQueue.Sink()
        {
            public void markRead(Peer peer, int thread, int maxId)
            {
                drained[at[0]++] = thread;
                drained[at[0]++] = maxId;
            }
        };
        while (queue.drainOne(sink)) { }
        Assert.equal("first thread", 400, drained[0]);
        Assert.equal("first cursor is the coalesced maximum", 650, drained[1]);
        Assert.equal("second thread", 401, drained[2]);
        Assert.equal("second cursor", 700, drained[3]);
    }

    private static void localReadsTellThreadsApart()
    {
        LocalReads reads = new LocalReads();
        Peer forum = new Peer(Peer.CHANNEL, 77);
        reads.cleared(forum, 400, 600);

        ForumTopic topic = new ForumTopic();
        topic.id = 400;
        topic.readInboxMaxId = 500;
        topic.unreadCount = 3;
        reads.applyTopic(topic, forum);
        Assert.equal("the reader's clear wins over a stale row",
                600, topic.readInboxMaxId);
        Assert.equal("and the badge stays cleared", 0, topic.unreadCount);

        ForumTopic other = new ForumTopic();
        other.id = 401;
        other.readInboxMaxId = 500;
        other.unreadCount = 3;
        reads.applyTopic(other, forum);
        Assert.equal("another topic's row is untouched", 3, other.unreadCount);

        // The peer-level entry is thread 0 and does not leak into topics.
        tg.api.Dialog dialog = new tg.api.Dialog();
        dialog.peer = forum;
        dialog.readInboxMaxId = 100;
        dialog.unreadCount = 5;
        reads.apply(dialog);
        Assert.equal("a topic clear does not zero the forum's dialog row",
                5, dialog.unreadCount);
    }

    private static void outboxCarriesTheThread() throws Exception
    {
        OutgoingMessage message = new OutgoingMessage();
        message.peerKind = Peer.CHANNEL;
        message.peerId = 77;
        message.accessHash = 88;
        message.peerTitle = "Forum";
        message.text = "into the topic";
        message.replyToMessageId = 555;
        message.threadRootId = 400;
        message.randomId = 11;
        OutgoingMessage decoded = OutgoingMessage.decode(1,
                OutgoingMessage.encode(message));
        Assert.equal("v3 thread persisted", 400, decoded.threadRootId);
        Assert.equal("v3 reply persisted beside it",
                555, decoded.replyToMessageId);

        // A v2 record - written before threads existed - reads as thread 0, so
        // a retry after an upgrade sends where it always would have.
        TlWriter v2 = new TlWriter(128);
        v2.writeInt(0x54474f32);
        v2.writeInt(2);
        v2.writeInt(OutgoingMessage.QUEUED);
        v2.writeInt(Peer.CHANNEL);
        v2.writeLong(77);
        v2.writeLong(88);
        v2.writeString("Forum");
        v2.writeString("old reply");
        v2.writeInt(555);
        v2.writeLong(12);
        v2.writeLong(13);
        v2.writeInt(0);
        v2.writeLong(0);
        v2.writeString("");
        OutgoingMessage old = OutgoingMessage.decode(2, v2.toByteArray());
        Assert.equal("v2 thread defaults to none", 0, old.threadRootId);
        Assert.equal("v2 reply still read", 555, old.replyToMessageId);
    }

    private static void draftsAreKeyedByThread() throws Exception
    {
        MemoryDraftStore drafts = new MemoryDraftStore();
        Peer forum = new Peer(Peer.CHANNEL, 77);
        drafts.save(forum, 0, "for the forum");
        drafts.save(forum, 400, "for the topic");

        Assert.equal("threads hold separate drafts", "for the topic",
                drafts.load(forum, 400));
        Assert.equal("the peer's own draft is untouched", "for the forum",
                drafts.load(forum, 0));

        drafts.save(forum, 400, "");
        Assert.equal("clearing the topic leaves the peer's",
                "for the forum", drafts.load(forum, 0));
        Assert.equal("and the topic draft is gone", "",
                drafts.load(forum, 400));
    }

    private static tg.api.Message message(int id)
    {
        tg.api.Message m = new tg.api.Message();
        m.id = id;
        return m;
    }
}
