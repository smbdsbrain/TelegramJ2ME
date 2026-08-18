package tgtest;

import java.util.ArrayList;
import java.util.List;

import tg.api.Peer;
import tg.app.ReadQueue;

/**
 * A read acknowledgement waits its turn instead of being overwritten.
 *
 * The queue used to be one slot. A producer that found it occupied by another
 * conversation replaced the entry rather than queueing beside it, and the window
 * for that is not a scheduling accident - it is a whole {@code readHistory}
 * round trip. The drain thread takes the slot, empties it, and only then goes to
 * the network, which on the measured GPRS link is seconds. Anything read in that
 * time lands in the empty slot; the next chat the reader opens overwrites it.
 *
 * The shape of the loss is the ordinary one: a message arrives in chat A while
 * the reader is in it, they walk back to the list and open chat B, and A is
 * still bold on every other device they own. Nothing retries, because nothing
 * knows an acknowledgement was ever pending.
 *
 * So the queue holds one entry per conversation, coalescing by peer because a
 * read cursor is monotonic and the server takes the maximum anyway. It is
 * bounded like everything else here, and the bound is small enough that it can
 * be reached, so reaching it is reported rather than assumed away.
 *
 * The thread that drains it is {@code TgMidlet}'s and is not observable from a
 * desktop suite; what is observable is the ordering, the coalescing and the
 * hand-off, which is what these cases drive.
 */
public final class ReadQueueTest implements Test
{
    public String name() { return "api/read-queue"; }

    public void run()
    {
        aSecondChatDoesNotOverwriteTheFirst();
        theSameChatCoalescesToTheHighestId();
        theDrainStopsWhenItRunsDry();
        onlyTheFirstProducerStartsTheDrain();
        aFullQueueDropsTheOldestAndSaysSo();
        aFailingSinkDoesNotStrandTheRest();
        clearForgetsEverything();
    }

    // -------------------------------------------------------------- the defect

    /**
     * The regression. Chat A is acknowledged while the drain is on the network,
     * the reader opens chat B, and both must reach the server.
     */
    private static void aSecondChatDoesNotOverwriteTheFirst()
    {
        ReadQueue queue = new ReadQueue();
        Peer anna = user(10, "Anna");
        Peer group = new Peer(Peer.CHAT, 77);

        Assert.isTrue("the first acknowledgement starts the drain",
                queue.offer(anna, 0, 500));
        Assert.isFalse("the second only joins the queue",
                queue.offer(group, 0, 900));
        Assert.equal("both are waiting", 2, queue.size());

        Sink sink = new Sink();
        Assert.isTrue("the drain hands over the first", queue.drainOne(sink));
        Assert.isTrue("and then the second", queue.drainOne(sink));

        Assert.equal("two acknowledgements were sent", 2, sink.count());
        Assert.equal("the chat read first is sent first", "user:10=500",
                sink.at(0));
        Assert.equal("and the chat read second is not lost", "chat:77=900",
                sink.at(1));
    }

    /**
     * Within one conversation the cursor only moves forward, so a second
     * acknowledgement replaces rather than follows - one round trip, not two.
     */
    private static void theSameChatCoalescesToTheHighestId()
    {
        ReadQueue queue = new ReadQueue();
        Peer anna = user(10, "Anna");

        queue.offer(anna, 0, 500);
        queue.offer(anna, 0, 700);
        // A later instance of the same conversation: Peer is mutable and arrives
        // fresh from every dialog page, so ownership is kind and id.
        queue.offer(user(10, "Anna Smith"), 0, 600);

        Assert.equal("one conversation is one entry", 1, queue.size());

        Sink sink = new Sink();
        queue.drainOne(sink);
        Assert.equal("coalesced to the highest id, not the last one offered",
                "user:10=700", sink.at(0));
        Assert.equal("and only once", 1, sink.count());
    }

    /**
     * Running dry is what ends the drain thread, and it has to be the same
     * decision as clearing the running flag or a producer can slip between the
     * two and leave a queue nobody is draining.
     */
    private static void theDrainStopsWhenItRunsDry()
    {
        ReadQueue queue = new ReadQueue();
        Sink sink = new Sink();

        queue.offer(user(10, "Anna"), 0, 500);
        Assert.isTrue("one entry drains", queue.drainOne(sink));
        Assert.isFalse("an empty queue ends the drain", queue.drainOne(sink));
        Assert.equal("nothing extra was sent", 1, sink.count());

        Assert.isTrue("and the next producer starts a new drain",
                queue.offer(user(11, "Boris"), 0, 600));
    }

    /** One drain thread, however many producers. */
    private static void onlyTheFirstProducerStartsTheDrain()
    {
        ReadQueue queue = new ReadQueue();

        Assert.isTrue("the first starts it", queue.offer(user(10, "Anna"), 0, 1));
        Assert.isFalse("the second does not", queue.offer(user(11, "B"), 0, 2));
        Assert.isFalse("nor does a third", queue.offer(user(12, "C"), 0, 3));

        Sink sink = new Sink();
        while (queue.drainOne(sink)) { }
        Assert.equal("all three were sent", 3, sink.count());
    }

    /**
     * The bound is real and small, so it can be reached. What is dropped is the
     * oldest - the conversation the reader has left - because the newest is the
     * one they are looking at, and a dropped entry is recoverable: opening that
     * chat again acknowledges it.
     */
    private static void aFullQueueDropsTheOldestAndSaysSo()
    {
        ReadQueue queue = new ReadQueue();
        for (int i = 0; i < ReadQueue.CAPACITY; i++)
        {
            queue.offer(user(100 + i, "chat " + i), 0, 10 + i);
        }
        Assert.equal("the queue is full", ReadQueue.CAPACITY, queue.size());
        Assert.equal("and has dropped nothing yet", 0, queue.dropped());

        queue.offer(user(999, "one too many"), 0, 999);
        Assert.equal("it is still bounded", ReadQueue.CAPACITY, queue.size());
        Assert.equal("and the drop is counted rather than silent", 1,
                queue.dropped());

        Sink sink = new Sink();
        while (queue.drainOne(sink)) { }
        Assert.equal("the oldest is the one that went", "user:101=11",
                sink.at(0));
        Assert.equal("and the newest is still there",
                "user:999=999", sink.at(sink.count() - 1));

        // Coalescing is what keeps the bound off the common path: reading the
        // same conversation a hundred times is one entry, not a hundred.
        ReadQueue busy = new ReadQueue();
        Peer anna = user(10, "Anna");
        for (int i = 1; i <= 100; i++) { busy.offer(anna, 0, i); }
        Assert.equal("a hundred reads of one chat is one entry", 1, busy.size());
        Assert.equal("and drops nothing", 0, busy.dropped());
    }

    /**
     * A failed RPC is best effort and always was - it is logged and dropped.
     * What must not happen is the failure taking the rest of the queue with it.
     */
    private static void aFailingSinkDoesNotStrandTheRest()
    {
        ReadQueue queue = new ReadQueue();
        queue.offer(user(10, "Anna"), 0, 500);
        queue.offer(user(11, "Boris"), 0, 600);

        Sink sink = new Sink();
        sink.failOn("user:10=500");

        Assert.isTrue("the failing entry is still consumed",
                queue.drainOne(sink));
        Assert.isTrue("and the next one is handed over", queue.drainOne(sink));
        Assert.isFalse("then the queue is dry", queue.drainOne(sink));

        Assert.equal("the chat behind the failure was still sent",
                "user:11=600", sink.at(sink.count() - 1));
    }

    /** Logging out empties it: a Peer here is a contact, and it is account data. */
    private static void clearForgetsEverything()
    {
        ReadQueue queue = new ReadQueue();
        queue.offer(user(10, "Anna"), 0, 500);
        queue.offer(user(11, "Boris"), 0, 600);

        queue.clear();
        Assert.equal("nothing is retained", 0, queue.size());

        Sink sink = new Sink();
        Assert.isFalse("and nothing is sent", queue.drainOne(sink));
        Assert.equal("nothing at all", 0, sink.count());

        Assert.isTrue("a cleared queue starts a fresh drain",
                queue.offer(user(12, "Vera"), 0, 700));
    }

    // ---------------------------------------------------------------- fixtures

    private static Peer user(long id, String title)
    {
        Peer p = new Peer(Peer.USER, id);
        p.accessHash = 0x5eed0000L + id;
        p.title = title;
        return p;
    }

    /** Records what reached {@code Telegram.markRead}, and can refuse one. */
    private static final class Sink implements ReadQueue.Sink
    {
        private final List<String> sent = new ArrayList<String>();
        private String failFor;

        void failOn(String entry) { failFor = entry; }

        int count() { return sent.size(); }

        String at(int index) { return sent.get(index); }

        public void markRead(Peer peer, int thread, int maxId)
        {
            String entry = kind(peer) + ":" + peer.id + "=" + maxId;
            sent.add(entry);
            if (entry.equals(failFor))
            {
                // What TgMidlet's sink swallows and logs: an IOException from a
                // readHistory that did not come back.
                throw new RuntimeException("mark-read failed");
            }
        }

        private static String kind(Peer peer)
        {
            if (peer.kind == Peer.USER) { return "user"; }
            return peer.kind == Peer.CHAT ? "chat" : "channel";
        }
    }
}
