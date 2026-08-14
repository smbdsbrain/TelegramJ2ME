package tgtest;

import java.io.IOException;

import tg.api.Api;
import tg.api.Dialog;
import tg.api.MemoryUpdateStateStore;
import tg.api.Message;
import tg.api.Peer;
import tg.api.PeerCache;
import tg.api.Requests;
import tg.api.UpdateBatch;
import tg.api.UpdateState;
import tg.api.UpdateStateCodec;
import tg.api.UpdateSync;
import tg.api.UpdateSyncHarness;
import tg.mt.Dc;
import tg.mt.RpcError;
import tg.tl.TlReader;
import tg.tl.TlWriter;

/** Deterministic Phase 3 cursor, dedupe, gap and bounded-state tests. */
public final class UpdateSyncTest implements Test
{
    public String name() { return "updates/state-sync"; }

    public void run() throws Exception
    {
        stateStoreAndBounds();
        requestEncoding();
        exactDuplicateGapAndOverflow();
        channelDifference();
        commonFailureRetries();
        channelFailureIsIsolated();
        terminalChannelFailureStopsPollingOnlyThatChannel();
        invalidCursorRebaselines();
        safetyPollRecoversAndPushDefers();
    }

    private static void stateStoreAndBounds() throws Exception
    {
        UpdateState state = new UpdateState();
        state.accountId = 42;
        state.pts = 7;
        for (int i = 1; i <= UpdateState.MAX_CHANNELS + 4; i++)
        {
            state.setChannelPts(i, i * 10);
        }
        Assert.equal("bounded channel count", UpdateState.MAX_CHANNELS,
                state.channelCount());
        Assert.equal("newest channel retained",
                (UpdateState.MAX_CHANNELS + 4) * 10,
                state.channelPts(UpdateState.MAX_CHANNELS + 4));
        Assert.equal("oldest channel evicted", -1, state.channelPts(1));

        MemoryUpdateStateStore store = new MemoryUpdateStateStore();
        store.save(state);
        UpdateState loaded = store.load(42, false);
        Assert.equal("stored pts", 7, loaded.pts);
        loaded.pts = 99;
        Assert.equal("store returns copy", 7, store.load(42, false).pts);
        Assert.isTrue("account mismatch", store.load(43, false) == null);

        byte[] encoded = UpdateStateCodec.encode(state);
        UpdateState decoded = UpdateStateCodec.decode(encoded, 42, false);
        Assert.equal("codec pts", 7, decoded.pts);
        Assert.equal("codec channel count", UpdateState.MAX_CHANNELS,
                decoded.channelCount());
        Assert.isTrue("codec account mismatch",
                UpdateStateCodec.decode(encoded, 43, false) == null);
        byte[] corrupt = (byte[]) encoded.clone();
        corrupt[0] ^= 1;
        try
        {
            UpdateStateCodec.decode(corrupt, 42, false);
            Assert.fail("corrupt state accepted");
        }
        catch (IOException expected) { }
        corrupt = (byte[]) encoded.clone();
        corrupt[4] = 99;
        try
        {
            UpdateStateCodec.decode(corrupt, 42, false);
            Assert.fail("unknown state version accepted");
        }
        catch (IOException expected) { }
    }

    private static void requestEncoding() throws Exception
    {
        UpdateState state = new UpdateState();
        state.pts = 11;
        state.date = 22;
        state.qts = 33;
        TlReader r = new TlReader(Requests.getDifference(state));
        Assert.equal("getDifference id", Api.UPDATES_GET_DIFFERENCE, r.readInt());
        Assert.equal("difference flags", 7, r.readInt());
        Assert.equal("difference pts", 11, r.readInt());
        Assert.equal("difference pts limit", 100, r.readInt());
        Assert.equal("difference total limit", 1000, r.readInt());
        Assert.equal("difference date", 22, r.readInt());
        Assert.equal("difference qts", 33, r.readInt());
        Assert.equal("difference qts limit", 100, r.readInt());

        Peer channel = new Peer(Peer.CHANNEL, 77);
        channel.accessHash = 88;
        r = new TlReader(Requests.getChannelDifference(channel, 9));
        Assert.equal("channel difference id",
                Api.UPDATES_GET_CHANNEL_DIFFERENCE, r.readInt());
        Assert.equal("channel flags", 0, r.readInt());
        Assert.equal("input channel", Api.INPUT_CHANNEL, r.readInt());
        Assert.equal("channel id", 77L, r.readLong());
        Assert.equal("channel hash", 88L, r.readLong());
        Assert.equal("channel filter", Api.CHANNEL_MESSAGES_FILTER_EMPTY, r.readInt());
        Assert.equal("channel pts", 9, r.readInt());
        Assert.equal("channel limit", 50, r.readInt());
    }

    private static void exactDuplicateGapAndOverflow() throws Exception
    {
        MemoryUpdateStateStore store = new MemoryUpdateStateStore();
        UpdateState initial = new UpdateState();
        initial.accountId = 100;
        initial.testEnvironment = Dc.isTest();
        initial.pts = 10;
        initial.date = 20;
        initial.seq = 3;
        store.save(initial);

        PeerCache peers = new PeerCache();
        Peer self = new Peer(Peer.USER, 100);
        self.self = true;
        self.title = "Me";
        self.accessHash = 1;
        peers.put(self);
        Peer other = new Peer(Peer.USER, 200);
        other.title = "Other";
        other.accessHash = 2;
        peers.put(other);

        FakeInvoker rpc = new FakeInvoker();
        Capture capture = new Capture();
        UpdateSync sync = new UpdateSync(rpc, peers);
        sync.setStore(store);
        sync.setListener(capture);
        sync.online();
        sync.activate(100);
        waitForState(sync, UpdateSync.LIVE);
        Assert.equal("initial recovery called", 1, rpc.differenceCalls);

        sync.accept(shortMessage(1, 200, "one", 11, 1, 21));
        capture.waitMessages(1);
        Assert.equal("exact text", "one", capture.lastMessage.text);
        Assert.equal("exact pts", 11, waitPts(sync, 11));

        sync.accept(shortMessage(1, 200, "duplicate", 11, 1, 21));
        Thread.sleep(100);
        Assert.equal("duplicate suppressed", 1, capture.messageCount);

        sync.accept(readOutbox(200, 1, 12, 1));
        capture.waitReads(1);
        Assert.equal("read outbox max", 1, capture.lastRead.outboxMaxId);
        Assert.equal("read pts", 12, waitPts(sync, 12));

        sync.accept(editedUpdate(false, 1, 200, "one edited", 22, 13, 1));
        capture.waitEdits(1);
        Assert.equal("edited text", "one edited", capture.lastEdit.text);
        Assert.equal("edited date", 22, capture.lastEdit.editDate);
        Assert.equal("edit pts", 13, waitPts(sync, 13));

        // On a real connection the unsolicited edit can win the race with the
        // rpc_result.  The cursor must stay deduplicated, while the edit
        // confirmed by the sender's RPC must still reach its open transcript.
        sync.acceptEdit(editedUpdate(false, 1, 200, "sender sees edit", 23,
                13, 1), other, 1);
        capture.waitEdits(2);
        Assert.equal("local edit result remains visible after duplicate pts",
                "sender sees edit", capture.lastEdit.text);
        Assert.equal("duplicate local edit leaves pts", 13, waitPts(sync, 13));

        sync.accept(shortMessage(2, 200, "gap", 15, 1, 23));
        waitDifferenceCalls(rpc, 2);
        Assert.equal("gap message not applied", 1, capture.messageCount);
        Assert.isTrue("gap diagnostic", sync.detail().indexOf("difference") >= 0
                || sync.detail().indexOf("gap") >= 0);

        sync.offline();
        for (int i = 0; i < 70; i++)
        {
            sync.accept(shortMessage(100 + i, 200, "queued", 12 + i, 1, 30 + i));
        }
        Assert.isTrue("queue remains bounded", sync.queued() <= 64);
        Assert.isTrue("overflow asks recovery",
                sync.detail().indexOf("overflow") >= 0);
        sync.close();
    }

    private static void channelDifference() throws Exception
    {
        MemoryUpdateStateStore store = new MemoryUpdateStateStore();
        UpdateState initial = new UpdateState();
        initial.accountId = 100;
        initial.testEnvironment = Dc.isTest();
        initial.pts = 10;
        initial.date = 20;
        initial.seq = 3;
        initial.setChannelPts(300, 5);
        store.save(initial);

        PeerCache peers = new PeerCache();
        Peer channel = new Peer(Peer.CHANNEL, 300);
        channel.accessHash = 44;
        channel.title = "Channel";
        peers.put(channel);

        ChannelInvoker rpc = new ChannelInvoker();
        Capture capture = new Capture();
        UpdateSync sync = new UpdateSync(rpc, peers);
        sync.setStore(store);
        sync.setListener(capture);
        sync.online();
        sync.activate(100);
        waitForState(sync, UpdateSync.LIVE);
        sync.setActivePeer(channel);
        capture.waitMessages(1);
        sync.setActivePeer(null);
        Assert.equal("channel message", "channel live",
                capture.lastMessage.text);
        Assert.equal("channel peer", 300L, capture.lastMessage.peer.id);
        long until = System.currentTimeMillis() + 2000;
        while (sync.snapshot().channelPts(300) != 6
                && System.currentTimeMillis() < until)
        {
            Thread.sleep(20);
        }
        Assert.equal("channel pts", 6, sync.snapshot().channelPts(300));
        Assert.equal("channel difference calls", 1, rpc.channelCalls);

        sync.accept(editedUpdate(true, 9, 300, "channel edited", 31, 7, 1));
        capture.waitEdits(1);
        Assert.equal("channel edited text", "channel edited",
                capture.lastEdit.text);
        Assert.equal("channel edited peer", 300L, capture.lastEdit.peer.id);
        long editUntil = System.currentTimeMillis() + 2000;
        while (sync.snapshot().channelPts(300) != 7
                && System.currentTimeMillis() < editUntil)
        {
            Thread.sleep(20);
        }
        Assert.equal("channel edit pts", 7,
                sync.snapshot().channelPts(300));
        sync.close();
    }

    private static void commonFailureRetries() throws Exception
    {
        MemoryUpdateStateStore store = storedState(100, 10);
        PeerCache peers = peers();
        RetryInvoker rpc = new RetryInvoker();
        Capture capture = new Capture();
        UpdateSync sync = UpdateSyncHarness.create(rpc, peers, 1000,
                new int[] { 200, 300 });
        sync.setStore(store);
        sync.setListener(capture);
        sync.online();
        sync.activate(100);
        waitForState(sync, UpdateSync.DEGRADED);
        for (int i = 0; i < 70; i++)
        {
            sync.accept(shortMessage(100 + i, 200, "during retry", 10,
                    1, 30 + i));
        }
        Assert.isTrue("queue stays bounded during common retry",
                sync.queued() <= 64);
        Assert.isTrue("queue size is diagnostic",
                sync.detail().indexOf("queue ") >= 0);
        waitForState(sync, UpdateSync.LIVE);
        Assert.equal("temporary common error retried", 2, rpc.differenceCalls);
        Assert.isTrue("retry delay was published", capture.sawRetry);

        sync.accept(shortMessage(1, 200, "after retry", 11, 1, 21));
        capture.waitMessages(1);
        Assert.equal("push still processed after retry", "after retry",
                capture.lastMessage.text);
        sync.close();
    }

    private static void channelFailureIsIsolated() throws Exception
    {
        MemoryUpdateStateStore store = storedState(100, 10);
        UpdateState state = store.load(100, Dc.isTest());
        state.setChannelPts(300, 5);
        store.save(state);
        PeerCache peers = peers();
        Peer channel = new Peer(Peer.CHANNEL, 300);
        channel.accessHash = 44;
        peers.put(channel);

        FailingChannelInvoker rpc = new FailingChannelInvoker();
        Capture capture = new Capture();
        UpdateSync sync = UpdateSyncHarness.create(rpc, peers, 1000,
                new int[] { 100, 150 });
        sync.setStore(store);
        sync.setListener(capture);
        sync.online();
        sync.activate(100);
        waitForState(sync, UpdateSync.LIVE);
        sync.setActivePeer(channel);
        waitForState(sync, UpdateSync.DEGRADED);

        sync.accept(shortMessage(7, 200, "private while channel retries",
                11, 1, 21));
        capture.waitMessages(1);
        Assert.equal("channel failure did not block private push",
                "private while channel retries", capture.lastMessage.text);
        waitForChannelCalls(rpc, 2);
        sync.close();
    }

    private static void invalidCursorRebaselines() throws Exception
    {
        MemoryUpdateStateStore store = storedState(100, 10);
        PeerCache peers = peers();
        InvalidCursorInvoker rpc = new InvalidCursorInvoker();
        Capture capture = new Capture();
        UpdateSync sync = UpdateSyncHarness.create(rpc, peers, 1000,
                new int[] { 20 });
        sync.setStore(store);
        sync.setListener(capture);
        sync.online();
        sync.activate(100);
        waitForState(sync, UpdateSync.LIVE);
        Assert.equal("invalid cursor fetched fresh state", 1, rpc.stateCalls);
        Assert.equal("fresh pts persisted", 20, waitPts(sync, 20));
        Assert.equal("invalid cursor requested one snapshot", 1,
                capture.fullRefreshCount);
        sync.close();
    }

    private static void terminalChannelFailureStopsPollingOnlyThatChannel()
            throws Exception
    {
        MemoryUpdateStateStore store = storedState(100, 10);
        UpdateState state = store.load(100, Dc.isTest());
        state.setChannelPts(300, 5);
        store.save(state);
        PeerCache peers = peers();
        Peer channel = new Peer(Peer.CHANNEL, 300);
        channel.accessHash = 44;
        peers.put(channel);

        TerminalChannelInvoker rpc = new TerminalChannelInvoker();
        Capture capture = new Capture();
        UpdateSync sync = UpdateSyncHarness.create(rpc, peers, 1000,
                new int[] { 20 });
        sync.setStore(store);
        sync.setListener(capture);
        sync.online();
        sync.activate(100);
        waitForState(sync, UpdateSync.LIVE);
        sync.setActivePeer(channel);
        capture.waitFullRefreshes(1);
        Thread.sleep(80);
        Assert.equal("terminal channel error did not tight-loop", 1,
                rpc.channelCalls);
        sync.setActivePeer(channel);
        Thread.sleep(80);
        Assert.equal("same open channel remains blocked", 1,
                rpc.channelCalls);
        sync.accept(shortMessage(10, 200, "private after invalid channel",
                11, 1, 21));
        capture.waitMessages(1);
        Assert.equal("terminal channel error kept global push live",
                "private after invalid channel", capture.lastMessage.text);
        sync.setActivePeer(null);
        sync.setActivePeer(channel);
        waitForChannelCalls(rpc, 2);
        Assert.equal("reopening channel permits one fresh attempt", 2,
                rpc.channelCalls);
        sync.close();
    }

    private static void safetyPollRecoversAndPushDefers() throws Exception
    {
        MemoryUpdateStateStore store = storedState(100, 10);
        PeerCache peers = peers();
        PollInvoker rpc = new PollInvoker(true);
        Capture capture = new Capture();
        UpdateSync sync = UpdateSyncHarness.create(rpc, peers, 60,
                new int[] { 20 });
        sync.setStore(store);
        sync.setListener(capture);
        sync.online();
        sync.activate(100);
        waitForState(sync, UpdateSync.LIVE);
        capture.waitMessages(1);
        Assert.equal("watchdog recovered missed message", "from poll",
                capture.lastMessage.text);
        Assert.isTrue("poll source is diagnostic",
                sync.detail().indexOf("source poll") >= 0);
        sync.close();

        store = storedState(100, 10);
        rpc = new PollInvoker(false);
        capture = new Capture();
        sync = UpdateSyncHarness.create(rpc, peers(), 100,
                new int[] { 20 });
        sync.setStore(store);
        sync.setListener(capture);
        sync.online();
        sync.activate(100);
        waitForState(sync, UpdateSync.LIVE);
        Thread.sleep(60);
        sync.accept(shortMessage(8, 200, "push defers", 11, 1, 21));
        capture.waitMessages(1);
        Thread.sleep(60);
        Assert.equal("push postponed watchdog", 1, rpc.differenceCalls);
        waitDifferenceCalls(rpc, 2);
        int beforePause = rpc.differenceCalls;
        sync.offline();
        Thread.sleep(140);
        Assert.equal("offline cancelled watchdog", beforePause,
                rpc.differenceCalls);
        sync.online();
        waitDifferenceCalls(rpc, beforePause + 1);
        sync.close();
    }

    private static final class FakeInvoker implements UpdateSync.Invoker
    {
        volatile int differenceCalls;

        public synchronized byte[] invoke(byte[] query) throws IOException
        {
            TlReader r = new TlReader(query);
            int id = r.readInt();
            if (id == Api.UPDATES_GET_STATE)
            {
                return state(10, 0, 20, 3);
            }
            if (id == Api.UPDATES_GET_DIFFERENCE)
            {
                differenceCalls++;
                return differenceEmpty(20, 3);
            }
            throw new IOException("unexpected update RPC 0x"
                    + Integer.toHexString(id));
        }
    }

    private static final class ChannelInvoker implements UpdateSync.Invoker
    {
        volatile int channelCalls;

        public synchronized byte[] invoke(byte[] query) throws IOException
        {
            int id = new TlReader(query).readInt();
            if (id == Api.UPDATES_GET_DIFFERENCE)
            {
                return differenceEmpty(20, 3);
            }
            if (id == Api.UPDATES_GET_CHANNEL_DIFFERENCE)
            {
                channelCalls++;
                return channelDifferenceResult();
            }
            throw new IOException("unexpected channel test RPC");
        }
    }

    private static final class RetryInvoker implements UpdateSync.Invoker
    {
        volatile int differenceCalls;

        public synchronized byte[] invoke(byte[] query) throws IOException
        {
            int id = new TlReader(query).readInt();
            if (id != Api.UPDATES_GET_DIFFERENCE)
            {
                throw new IOException("unexpected retry RPC");
            }
            differenceCalls++;
            if (differenceCalls == 1)
            {
                throw new RpcError(500, "PERSISTENT_TIMESTAMP_OUTDATED");
            }
            return differenceEmpty(20, 3);
        }
    }

    private static final class FailingChannelInvoker implements UpdateSync.Invoker
    {
        volatile int channelCalls;

        public synchronized byte[] invoke(byte[] query) throws IOException
        {
            int id = new TlReader(query).readInt();
            if (id == Api.UPDATES_GET_DIFFERENCE)
            {
                return differenceEmpty(20, 3);
            }
            if (id == Api.UPDATES_GET_CHANNEL_DIFFERENCE)
            {
                channelCalls++;
                if (channelCalls == 1)
                {
                    throw new RpcError(500, "PERSISTENT_TIMESTAMP_OUTDATED");
                }
                return channelDifferenceResult();
            }
            throw new IOException("unexpected isolated channel RPC");
        }
    }

    private static final class InvalidCursorInvoker implements UpdateSync.Invoker
    {
        volatile int stateCalls;

        public synchronized byte[] invoke(byte[] query) throws IOException
        {
            int id = new TlReader(query).readInt();
            if (id == Api.UPDATES_GET_DIFFERENCE)
            {
                throw new RpcError(400, "PERSISTENT_TIMESTAMP_INVALID");
            }
            if (id == Api.UPDATES_GET_STATE)
            {
                stateCalls++;
                return state(20, 0, 30, 4);
            }
            throw new IOException("unexpected cursor reset RPC");
        }
    }

    private static final class TerminalChannelInvoker implements UpdateSync.Invoker
    {
        volatile int channelCalls;

        public synchronized byte[] invoke(byte[] query) throws IOException
        {
            int id = new TlReader(query).readInt();
            if (id == Api.UPDATES_GET_DIFFERENCE)
            {
                return differenceEmpty(20, 3);
            }
            if (id == Api.UPDATES_GET_CHANNEL_DIFFERENCE)
            {
                channelCalls++;
                throw new RpcError(400, "CHANNEL_INVALID");
            }
            throw new IOException("unexpected terminal channel RPC");
        }
    }

    private static final class PollInvoker implements UpdateSync.Invoker
    {
        volatile int differenceCalls;
        private final boolean deliver;

        PollInvoker(boolean deliver) { this.deliver = deliver; }

        public synchronized byte[] invoke(byte[] query) throws IOException
        {
            int id = new TlReader(query).readInt();
            if (id != Api.UPDATES_GET_DIFFERENCE)
            {
                throw new IOException("unexpected poll RPC");
            }
            differenceCalls++;
            if (deliver && differenceCalls == 2)
            {
                return differenceWithMessage();
            }
            return differenceEmpty(20, 3);
        }
    }

    private static final class Capture implements UpdateSync.Listener
    {
        volatile int messageCount;
        volatile int editCount;
        volatile int readCount;
        volatile UpdateBatch last;
        volatile Message lastMessage;
        volatile Message lastEdit;
        volatile tg.api.ReadState lastRead;
        volatile boolean sawRetry;
        volatile int fullRefreshCount;

        public synchronized void onBatch(UpdateBatch batch)
        {
            last = batch;
            messageCount += batch.messages.length;
            editCount += batch.edits.length;
            readCount += batch.reads.length;
            if (batch.retrySeconds >= 0) { sawRetry = true; }
            if (batch.fullRefresh) { fullRefreshCount++; }
            if (batch.messages.length > 0)
            {
                lastMessage = batch.messages[batch.messages.length - 1];
            }
            if (batch.edits.length > 0)
            {
                lastEdit = batch.edits[batch.edits.length - 1];
            }
            if (batch.reads.length > 0)
            {
                lastRead = batch.reads[batch.reads.length - 1];
            }
            notifyAll();
        }

        synchronized void waitReads(int expected) throws Exception
        {
            long until = System.currentTimeMillis() + 3000;
            while (readCount < expected && System.currentTimeMillis() < until)
            {
                wait(50);
            }
            Assert.equal("read callback count", expected, readCount);
        }

        synchronized void waitMessages(int expected) throws Exception
        {
            long until = System.currentTimeMillis() + 3000;
            while (messageCount < expected && System.currentTimeMillis() < until)
            {
                wait(50);
            }
            Assert.equal("message callback count", expected, messageCount);
        }

        synchronized void waitEdits(int expected) throws Exception
        {
            long until = System.currentTimeMillis() + 3000;
            while (editCount < expected && System.currentTimeMillis() < until)
            {
                wait(50);
            }
            Assert.equal("edit callback count", expected, editCount);
        }

        synchronized void waitFullRefreshes(int expected) throws Exception
        {
            long until = System.currentTimeMillis() + 3000;
            while (fullRefreshCount < expected && System.currentTimeMillis() < until)
            {
                wait(20);
            }
            Assert.equal("full refresh callback count", expected,
                    fullRefreshCount);
        }
    }

    private static byte[] state(int pts, int qts, int date, int seq)
    {
        TlWriter w = new TlWriter(32);
        w.writeInt(Api.UPDATES_STATE);
        w.writeInt(pts);
        w.writeInt(qts);
        w.writeInt(date);
        w.writeInt(seq);
        w.writeInt(0);                       // unread_count
        return w.toByteArray();
    }

    private static byte[] differenceEmpty(int date, int seq)
    {
        TlWriter w = new TlWriter(16);
        w.writeInt(Api.UPDATES_DIFFERENCE_EMPTY);
        w.writeInt(date);
        w.writeInt(seq);
        return w.toByteArray();
    }

    private static byte[] differenceWithMessage()
    {
        TlWriter w = new TlWriter(160);
        w.writeInt(Api.UPDATES_DIFFERENCE);
        w.writeVectorHeader(1);               // new_messages
        w.writeInt(Api.MESSAGE);
        w.writeInt(0);                        // flags
        w.writeInt(0);                        // flags2
        w.writeInt(9);                        // id
        w.writeInt(Api.PEER_USER);
        w.writeLong(200);
        w.writeInt(21);                       // date
        w.writeString("from poll");
        w.writeVectorHeader(0);               // new_encrypted_messages
        w.writeVectorHeader(0);               // other_updates
        w.writeVectorHeader(0);               // chats
        w.writeVectorHeader(0);               // users
        byte[] fresh = state(11, 0, 21, 3);
        w.writeRaw(fresh, 0, fresh.length);    // state
        return w.toByteArray();
    }

    private static byte[] shortMessage(int id, long userId, String text,
            int pts, int count, int date)
    {
        TlWriter w = new TlWriter(64);
        w.writeInt(Api.UPDATE_SHORT_MESSAGE);
        w.writeInt(0);                       // flags: incoming plain text
        w.writeInt(id);
        w.writeLong(userId);
        w.writeString(text);
        w.writeInt(pts);
        w.writeInt(count);
        w.writeInt(date);
        return w.toByteArray();
    }

    private static byte[] readOutbox(long userId, int maxId, int pts, int count)
    {
        TlWriter w = new TlWriter(40);
        w.writeInt(Api.UPDATE_READ_HISTORY_OUTBOX);
        w.writeInt(Api.PEER_USER);
        w.writeLong(userId);
        w.writeInt(maxId);
        w.writeInt(pts);
        w.writeInt(count);
        return w.toByteArray();
    }

    private static byte[] editedUpdate(boolean channel, int id, long peerId,
            String text, int editDate, int pts, int count)
    {
        TlWriter w = new TlWriter(160);
        w.writeInt(Api.UPDATES);
        w.writeVectorHeader(1);
        w.writeInt(channel ? Api.UPDATE_EDIT_CHANNEL_MESSAGE
                : Api.UPDATE_EDIT_MESSAGE);
        w.writeInt(Api.MESSAGE);
        w.writeInt((1 << 1) | (1 << 15));    // out, edit_date
        w.writeInt(0);                       // flags2
        w.writeInt(id);
        w.writeInt(channel ? Api.PEER_CHANNEL : Api.PEER_USER);
        w.writeLong(peerId);
        w.writeInt(editDate - 1);             // date
        w.writeString(text);
        w.writeInt(editDate);
        w.writeInt(pts);
        w.writeInt(count);
        w.writeVectorHeader(0);              // users
        w.writeVectorHeader(0);              // chats
        w.writeInt(editDate);                // updates date
        w.writeInt(0);                       // seq
        return w.toByteArray();
    }

    private static byte[] channelDifferenceResult()
    {
        TlWriter w = new TlWriter(128);
        w.writeInt(Api.UPDATES_CHANNEL_DIFFERENCE);
        w.writeInt(1);                       // final
        w.writeInt(6);                       // pts
        w.writeVectorHeader(1);
        w.writeInt(Api.MESSAGE);
        w.writeInt(0);                       // flags
        w.writeInt(0);                       // flags2
        w.writeInt(9);                       // message id
        w.writeInt(Api.PEER_CHANNEL);
        w.writeLong(300);
        w.writeInt(30);                      // date
        w.writeString("channel live");
        w.writeVectorHeader(0);              // other_updates
        w.writeVectorHeader(0);              // chats
        w.writeVectorHeader(0);              // users
        return w.toByteArray();
    }

    private static void waitForState(UpdateSync sync, String expected) throws Exception
    {
        long until = System.currentTimeMillis() + 3000;
        while (!expected.equals(sync.syncState()) && System.currentTimeMillis() < until)
        {
            Thread.sleep(20);
        }
        Assert.equal("sync state", expected, sync.syncState());
    }

    private static int waitPts(UpdateSync sync, int expected) throws Exception
    {
        long until = System.currentTimeMillis() + 3000;
        while (sync.snapshot().pts != expected && System.currentTimeMillis() < until)
        {
            Thread.sleep(20);
        }
        return sync.snapshot().pts;
    }

    private static void waitDifferenceCalls(FakeInvoker rpc, int expected)
            throws Exception
    {
        long until = System.currentTimeMillis() + 3000;
        while (rpc.differenceCalls < expected && System.currentTimeMillis() < until)
        {
            Thread.sleep(20);
        }
        Assert.equal("difference calls", expected, rpc.differenceCalls);
    }

    private static void waitDifferenceCalls(PollInvoker rpc, int expected)
            throws Exception
    {
        long until = System.currentTimeMillis() + 3000;
        while (rpc.differenceCalls < expected && System.currentTimeMillis() < until)
        {
            Thread.sleep(10);
        }
        Assert.equal("poll difference calls", expected, rpc.differenceCalls);
    }

    private static void waitForChannelCalls(FailingChannelInvoker rpc, int expected)
            throws Exception
    {
        long until = System.currentTimeMillis() + 3000;
        while (rpc.channelCalls < expected && System.currentTimeMillis() < until)
        {
            Thread.sleep(10);
        }
        Assert.equal("isolated channel calls", expected, rpc.channelCalls);
    }

    private static void waitForChannelCalls(TerminalChannelInvoker rpc,
                                            int expected) throws Exception
    {
        long until = System.currentTimeMillis() + 3000;
        while (rpc.channelCalls < expected && System.currentTimeMillis() < until)
        {
            Thread.sleep(10);
        }
        Assert.equal("terminal channel calls", expected, rpc.channelCalls);
    }

    private static MemoryUpdateStateStore storedState(long accountId, int pts)
            throws Exception
    {
        MemoryUpdateStateStore store = new MemoryUpdateStateStore();
        UpdateState initial = new UpdateState();
        initial.accountId = accountId;
        initial.testEnvironment = Dc.isTest();
        initial.pts = pts;
        initial.date = 20;
        initial.seq = 3;
        store.save(initial);
        return store;
    }

    private static PeerCache peers()
    {
        PeerCache peers = new PeerCache();
        Peer self = new Peer(Peer.USER, 100);
        self.self = true;
        self.accessHash = 1;
        peers.put(self);
        Peer other = new Peer(Peer.USER, 200);
        other.accessHash = 2;
        peers.put(other);
        return peers;
    }
}
