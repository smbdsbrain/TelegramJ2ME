package tg.api;

import java.io.IOException;
import java.util.Vector;

import tg.diag.Diag;
import tg.mem.MemoryBudget;
import tg.mt.Dc;
import tg.tl.TlObj;
import tg.tl.TlParser;
import tg.tl.TlReader;

/**
 * Serialises pushed Updates, state recovery and channel differences.
 *
 * MtClient calls {@link #accept} on its reader thread. That method only queues
 * the body: invoking getDifference there would deadlock because the same reader
 * is needed to deliver its rpc_result.
 */
public final class UpdateSync
{
    public static final String STOPPED = "stopped";
    public static final String SYNCING = "syncing";
    public static final String LIVE = "live";
    public static final String DEGRADED = "degraded";

    private static final int MAX_QUEUE = 64;

    public interface Invoker
    {
        byte[] invoke(byte[] query) throws IOException;
    }

    public interface Listener
    {
        void onBatch(UpdateBatch batch);
    }

    private static final class Envelope
    {
        byte[] body;
        Peer sentPeer;
        String sentText;

        Envelope(byte[] body) { this.body = body; }
    }

    private static final class PendingBatch
    {
        final Vector messages = new Vector();
        final Vector reads = new Vector();
        final Vector reactions = new Vector();
        boolean fullRefresh;

        UpdateBatch freeze()
        {
            UpdateBatch out = new UpdateBatch();
            out.messages = new Message[messages.size()];
            messages.copyInto(out.messages);
            out.reads = new ReadState[reads.size()];
            reads.copyInto(out.reads);
            out.reactions = new ReactionUpdate[reactions.size()];
            reactions.copyInto(out.reactions);
            out.fullRefresh = fullRefresh;
            return out;
        }

        boolean isEmpty()
        {
            return messages.size() == 0 && reads.size() == 0
                    && reactions.size() == 0 && !fullRefresh;
        }
    }

    private final Invoker invoker;
    private final PeerCache peers;
    private final Object lock = new Object();
    private final Vector queue = new Vector();
    private final Vector channelRecovery = new Vector();

    private UpdateStateStore store = new MemoryUpdateStateStore();
    private Listener listener;
    private UpdateState state;
    private Thread worker;
    private boolean running = true;
    private boolean activated;
    private boolean online;
    private boolean recoveryNeeded;
    private int queueBytes;
    private String syncState = STOPPED;
    private String lastDetail = "";
    private Peer activePeer;
    private long nextChannelPoll;
    private int epoch;

    public UpdateSync(Invoker invoker, PeerCache peers)
    {
        this.invoker = invoker;
        this.peers = peers;
    }

    public void setStore(UpdateStateStore value)
    {
        synchronized (lock)
        {
            if (activated) { throw new IllegalStateException("updates already activated"); }
            store = value == null ? new MemoryUpdateStateStore() : value;
        }
    }

    public void setListener(Listener value)
    {
        synchronized (lock) { listener = value; }
    }

    /** Bind persisted state to the authenticated account and start catch-up. */
    public void activate(long accountId)
    {
        synchronized (lock)
        {
            epoch++;
            try { state = store.load(accountId, Dc.isTest()); }
            catch (IOException e)
            {
                Diag.error("update state load failed; starting fresh", e);
                state = null;
            }
            if (state == null)
            {
                state = new UpdateState();
                state.accountId = accountId;
                state.testEnvironment = Dc.isTest();
            }
            activated = true;
            recoveryNeeded = true;
            ensureWorker();
            lock.notifyAll();
        }
    }

    public void online()
    {
        synchronized (lock)
        {
            online = true;
            if (activated) { recoveryNeeded = true; }
            if (activePeer != null && activePeer.kind == Peer.CHANNEL)
            {
                nextChannelPoll = System.currentTimeMillis();
            }
            if (activated) { ensureWorker(); }
            lock.notifyAll();
        }
    }

    public void offline()
    {
        synchronized (lock)
        {
            online = false;
            nextChannelPoll = 0;
            lock.notifyAll();
        }
    }

    public void close()
    {
        Thread old;
        synchronized (lock)
        {
            running = false;
            online = false;
            epoch++;
            queue.removeAllElements();
            channelRecovery.removeAllElements();
            queueBytes = 0;
            old = worker;
            worker = null;
            lock.notifyAll();
        }
        if (old != null && old != Thread.currentThread())
        {
            try { old.join(1000); }
            catch (InterruptedException ignored) { }
        }
    }

    public void deactivate()
    {
        synchronized (lock)
        {
            activated = false;
            epoch++;
            recoveryNeeded = false;
            queue.removeAllElements();
            channelRecovery.removeAllElements();
            queueBytes = 0;
            state = null;
            activePeer = null;
            nextChannelPoll = 0;
            syncState = STOPPED;
            try { store.clear(); }
            catch (IOException e) { Diag.error("update state clear failed", e); }
            lock.notifyAll();
        }
    }

    /**
     * Erase the durable cursors, and report a failure instead of logging it.
     *
     * {@link #deactivate} clears them too, but it is a lifecycle step that must
     * not throw at its callers, so it can only log. The logout wipe needs the
     * opposite: a cursor table that survived is an account-bound record still
     * on the handset, and the user has to be told.
     */
    public void clearStore() throws IOException
    {
        synchronized (lock) { store.clear(); }
    }

    /** Reader-thread entry point. It never parses, writes RMS or performs RPC. */
    public void accept(byte[] body)
    {
        enqueue(new Envelope(body));
    }

    /** Feed a sendMessage Updates result and retain short-sent message context. */
    public void acceptSent(byte[] body, Peer peer, String text)
    {
        Envelope envelope = new Envelope(body);
        envelope.sentPeer = peer;
        envelope.sentText = text;
        enqueue(envelope);
    }

    /** Feed messages.affectedMessages from a local read operation. */
    public void acceptAffected(byte[] body)
    {
        enqueue(new Envelope(body));
    }

    public void seedDialogs(Dialog[] dialogs)
    {
        synchronized (lock)
        {
            if (!activated || state == null || dialogs == null) { return; }
            for (int i = 0; i < dialogs.length; i++)
            {
                Dialog d = dialogs[i];
                if (d != null && d.peer != null && d.peer.kind == Peer.CHANNEL
                        && d.channelPts >= 0)
                {
                    state.setChannelPts(d.peer.id, d.channelPts);
                }
            }
            saveState();
            lock.notifyAll();
        }
    }

    public void setActivePeer(Peer peer)
    {
        synchronized (lock)
        {
            activePeer = peer;
            nextChannelPoll = peer != null && peer.kind == Peer.CHANNEL
                    ? System.currentTimeMillis() : 0;
            lock.notifyAll();
        }
    }

    public UpdateState snapshot()
    {
        synchronized (lock) { return state == null ? null : state.copy(); }
    }

    public String syncState()
    {
        synchronized (lock) { return syncState; }
    }

    public String detail()
    {
        synchronized (lock) { return lastDetail; }
    }

    public int queued()
    {
        synchronized (lock) { return queue.size(); }
    }

    private void enqueue(Envelope envelope)
    {
        if (envelope == null || envelope.body == null) { return; }
        synchronized (lock)
        {
            if (!running || !activated) { return; }
            if (queue.size() >= MAX_QUEUE
                    || queueBytes + envelope.body.length > MemoryBudget.updateQueueBytes())
            {
                queue.removeAllElements();
                queueBytes = 0;
                recoveryNeeded = true;
                lastDetail = "update queue overflow";
                Diag.warn(lastDetail + "; requesting difference");
            }
            else
            {
                queue.addElement(envelope);
                queueBytes += envelope.body.length;
            }
            ensureWorker();
            lock.notifyAll();
        }
    }

    private void ensureWorker()
    {
        if (worker != null) { return; }
        running = true;
        worker = new Thread(new Runnable()
        {
            public void run() { loop(); }
        });
        worker.start();
    }

    private void loop()
    {
        while (true)
        {
            Envelope envelope = null;
            Peer pollPeer = null;
            boolean recover = false;
            synchronized (lock)
            {
                while (running)
                {
                    long now = System.currentTimeMillis();
                    boolean pollDue = online && activated
                            && activePeer != null && activePeer.kind == Peer.CHANNEL
                            && nextChannelPoll > 0 && nextChannelPoll <= now;
                    if (online && activated
                            && (recoveryNeeded || channelRecovery.size() > 0
                                    || queue.size() > 0 || pollDue))
                    {
                        if (recoveryNeeded)
                        {
                            recoveryNeeded = false;
                            recover = true;
                        }
                        else if (channelRecovery.size() > 0)
                        {
                            pollPeer = (Peer) channelRecovery.elementAt(0);
                            channelRecovery.removeElementAt(0);
                        }
                        else if (queue.size() > 0)
                        {
                            envelope = (Envelope) queue.elementAt(0);
                            queue.removeElementAt(0);
                            queueBytes -= envelope.body.length;
                        }
                        else
                        {
                            pollPeer = activePeer;
                            nextChannelPoll = 0;
                        }
                        break;
                    }
                    long wait = 0;
                    if (online && activePeer != null && nextChannelPoll > now)
                    {
                        wait = nextChannelPoll - now;
                    }
                    try
                    {
                        if (wait > 0) { lock.wait(wait); }
                        else { lock.wait(); }
                    }
                    catch (InterruptedException ignored) { }
                }
                if (!running) { return; }
            }

            try
            {
                if (recover) { recoverCommon(); }
                else if (pollPeer != null) { recoverChannel(pollPeer); }
                else if (envelope != null) { processEnvelope(envelope); }
            }
            catch (IOException e)
            {
                Diag.error("update synchronisation failed", e);
                synchronized (lock)
                {
                    syncState = DEGRADED;
                    lastDetail = e.getMessage();
                    recoveryNeeded = true;
                    // Let Telegram's lifecycle reconnect before another RPC.
                    online = false;
                }
                publishState(DEGRADED, e.getMessage());
            }
            catch (Throwable t)
            {
                Diag.error("update worker failed", t);
                synchronized (lock)
                {
                    syncState = DEGRADED;
                    lastDetail = t.getClass().getName() + ": " + t.getMessage();
                    recoveryNeeded = true;
                }
                publishState(DEGRADED, lastDetail);
            }
        }
    }

    private void recoverCommon() throws IOException
    {
        int token = currentEpoch();
        publishState(SYNCING, state.pts == 0 ? "initial state" : "getDifference");
        if (state.pts == 0 && state.date == 0 && state.seq == 0)
        {
            TlObj initial = parseInvoke(Requests.getUpdateState());
            if (!isCurrentOnline(token)) { return; }
            adoptState(initial);
            saveState();
            publishState(LIVE, "state ready");
            return;
        }

        while (isCurrentOnline(token))
        {
            TlObj result = parseInvoke(Requests.getDifference(state));
            if (!isCurrentOnline(token)) { return; }
            if (result.id == Api.UPDATES_DIFFERENCE_EMPTY)
            {
                state.date = result.intAt(Api.F_UPDATES_DIFFERENCE_EMPTY__DATE);
                state.seq = result.intAt(Api.F_UPDATES_DIFFERENCE_EMPTY__SEQ);
                saveState();
                publishState(LIVE, "difference empty");
                return;
            }
            if (result.id == Api.UPDATES_DIFFERENCE_TOO_LONG)
            {
                PendingBatch batch = new PendingBatch();
                batch.fullRefresh = true;
                TlObj fresh = parseInvoke(Requests.getUpdateState());
                if (!isCurrentOnline(token)) { return; }
                adoptState(fresh);
                saveState();
                publish(batch);
                publishState(LIVE, "difference too long; snapshots refreshed");
                return;
            }
            if (result.id != Api.UPDATES_DIFFERENCE
                    && result.id != Api.UPDATES_DIFFERENCE_SLICE)
            {
                throw new IOException("unexpected updates.getDifference result 0x"
                        + Integer.toHexString(result.id));
            }

            boolean slice = result.id == Api.UPDATES_DIFFERENCE_SLICE;
            int fMessages = slice ? Api.F_UPDATES_DIFFERENCE_SLICE__NEW_MESSAGES
                    : Api.F_UPDATES_DIFFERENCE__NEW_MESSAGES;
            int fUpdates = slice ? Api.F_UPDATES_DIFFERENCE_SLICE__OTHER_UPDATES
                    : Api.F_UPDATES_DIFFERENCE__OTHER_UPDATES;
            int fChats = slice ? Api.F_UPDATES_DIFFERENCE_SLICE__CHATS
                    : Api.F_UPDATES_DIFFERENCE__CHATS;
            int fUsers = slice ? Api.F_UPDATES_DIFFERENCE_SLICE__USERS
                    : Api.F_UPDATES_DIFFERENCE__USERS;
            int fState = slice ? Api.F_UPDATES_DIFFERENCE_SLICE__INTERMEDIATE_STATE
                    : Api.F_UPDATES_DIFFERENCE__STATE;

            peers.absorb(result.vec(fUsers), result.vec(fChats));
            PendingBatch batch = new PendingBatch();
            appendMessages(result.vec(fMessages), batch);
            TlObj[] updates = result.vec(fUpdates);
            for (int i = 0; i < updates.length; i++)
            {
                applyEffect(updates[i], null, batch, true);
            }
            adoptState(result.obj(fState));
            saveState();                    // restart resumes this exact slice
            publish(batch);
            if (!slice)
            {
                publishState(LIVE, "difference applied");
                return;
            }
        }
    }

    private void recoverChannel(Peer requested) throws IOException
    {
        int token = currentEpoch();
        Peer channel = peers.resolve(requested);
        if (channel == null || channel.kind != Peer.CHANNEL || channel.accessHash == 0)
        {
            PendingBatch batch = new PendingBatch();
            batch.fullRefresh = true;
            publish(batch);
            scheduleChannelPoll(requested, 5);
            return;
        }
        int pts = state.channelPts(channel.id);
        if (pts < 0)
        {
            PendingBatch batch = new PendingBatch();
            batch.fullRefresh = true;
            publish(batch);
            scheduleChannelPoll(channel, 5);
            return;
        }

        publishState(SYNCING, "channel " + channel.id + " difference");
        while (isCurrentOnline(token))
        {
            TlObj result = parseInvoke(Requests.getChannelDifference(channel, pts));
            if (!isCurrentOnline(token)) { return; }
            PendingBatch batch = new PendingBatch();
            int timeout = 1;
            boolean done = true;

            if (result.id == Api.UPDATES_CHANNEL_DIFFERENCE_EMPTY)
            {
                pts = result.intAt(Api.F_UPDATES_CHANNEL_DIFFERENCE_EMPTY__PTS);
                timeout = optionalTimeout(result,
                        Api.F_UPDATES_CHANNEL_DIFFERENCE_EMPTY__TIMEOUT);
                done = result.num(Api.F_UPDATES_CHANNEL_DIFFERENCE_EMPTY__FINAL) != 0;
            }
            else if (result.id == Api.UPDATES_CHANNEL_DIFFERENCE)
            {
                peers.absorb(
                        result.vec(Api.F_UPDATES_CHANNEL_DIFFERENCE__USERS),
                        result.vec(Api.F_UPDATES_CHANNEL_DIFFERENCE__CHATS));
                appendMessages(result.vec(Api.F_UPDATES_CHANNEL_DIFFERENCE__NEW_MESSAGES),
                        batch);
                TlObj[] updates = result.vec(
                        Api.F_UPDATES_CHANNEL_DIFFERENCE__OTHER_UPDATES);
                for (int i = 0; i < updates.length; i++)
                {
                    applyEffect(updates[i], channel, batch, true);
                }
                pts = result.intAt(Api.F_UPDATES_CHANNEL_DIFFERENCE__PTS);
                timeout = optionalTimeout(result,
                        Api.F_UPDATES_CHANNEL_DIFFERENCE__TIMEOUT);
                done = result.num(Api.F_UPDATES_CHANNEL_DIFFERENCE__FINAL) != 0;
            }
            else if (result.id == Api.UPDATES_CHANNEL_DIFFERENCE_TOO_LONG)
            {
                peers.absorb(
                        result.vec(Api.F_UPDATES_CHANNEL_DIFFERENCE_TOO_LONG__USERS),
                        result.vec(Api.F_UPDATES_CHANNEL_DIFFERENCE_TOO_LONG__CHATS));
                appendMessages(
                        result.vec(Api.F_UPDATES_CHANNEL_DIFFERENCE_TOO_LONG__MESSAGES),
                        batch);
                TlObj dialog = result.obj(
                        Api.F_UPDATES_CHANNEL_DIFFERENCE_TOO_LONG__DIALOG);
                if (dialog == null)
                {
                    throw new IOException("channelDifferenceTooLong has no dialog");
                }
                pts = dialog.intAt(Api.F_DIALOG__PTS);
                timeout = optionalTimeout(result,
                        Api.F_UPDATES_CHANNEL_DIFFERENCE_TOO_LONG__TIMEOUT);
                batch.fullRefresh = true;
                done = true;
            }
            else
            {
                throw new IOException("unexpected channel difference 0x"
                        + Integer.toHexString(result.id));
            }

            state.setChannelPts(channel.id, pts);
            saveState();
            publish(batch);
            if (done)
            {
                scheduleChannelPoll(channel, timeout);
                publishState(LIVE, "channel " + channel.id + " current");
                return;
            }
        }
    }

    private void processEnvelope(Envelope envelope) throws IOException
    {
        TlObj obj = TlParser.parse(new TlReader(envelope.body));
        if (obj == null) { return; }
        PendingBatch batch = new PendingBatch();
        boolean changed = false;

        if (obj.id == Api.MESSAGES_AFFECTED_MESSAGES)
        {
            changed = applyCommonPts(
                    obj.intAt(Api.F_MESSAGES_AFFECTED_MESSAGES__PTS),
                    obj.intAt(Api.F_MESSAGES_AFFECTED_MESSAGES__PTS_COUNT));
        }
        else if (obj.id == Api.UPDATE_SHORT_MESSAGE)
        {
            if (applyCommonPts(obj.intAt(Api.F_UPDATE_SHORT_MESSAGE__PTS),
                    obj.intAt(Api.F_UPDATE_SHORT_MESSAGE__PTS_COUNT)))
            {
                batch.messages.addElement(shortUserMessage(obj));
                changed = true;
            }
        }
        else if (obj.id == Api.UPDATE_SHORT_CHAT_MESSAGE)
        {
            if (applyCommonPts(obj.intAt(Api.F_UPDATE_SHORT_CHAT_MESSAGE__PTS),
                    obj.intAt(Api.F_UPDATE_SHORT_CHAT_MESSAGE__PTS_COUNT)))
            {
                batch.messages.addElement(shortChatMessage(obj));
                changed = true;
            }
        }
        else if (obj.id == Api.UPDATE_SHORT_SENT_MESSAGE)
        {
            if (applyCommonPts(obj.intAt(Api.F_UPDATE_SHORT_SENT_MESSAGE__PTS),
                    obj.intAt(Api.F_UPDATE_SHORT_SENT_MESSAGE__PTS_COUNT)))
            {
                if (envelope.sentPeer != null)
                {
                    Message message = new Message();
                    message.id = obj.intAt(Api.F_UPDATE_SHORT_SENT_MESSAGE__ID);
                    message.date = obj.intAt(Api.F_UPDATE_SHORT_SENT_MESSAGE__DATE);
                    message.outgoing = true;
                    message.peer = envelope.sentPeer;
                    message.text = envelope.sentText == null ? "" : envelope.sentText;
                    batch.messages.addElement(message);
                }
                changed = true;
            }
        }
        else if (obj.id == Api.UPDATE_SHORT)
        {
            changed = applyEffect(obj.obj(Api.F_UPDATE_SHORT__UPDATE), null,
                    batch, false);
        }
        else if (obj.id == Api.UPDATES || obj.id == Api.UPDATES_COMBINED)
        {
            boolean combined = obj.id == Api.UPDATES_COMBINED;
            peers.absorb(
                    obj.vec(combined ? Api.F_UPDATES_COMBINED__USERS
                            : Api.F_UPDATES__USERS),
                    obj.vec(combined ? Api.F_UPDATES_COMBINED__CHATS
                            : Api.F_UPDATES__CHATS));
            TlObj[] updates = obj.vec(combined ? Api.F_UPDATES_COMBINED__UPDATES
                    : Api.F_UPDATES__UPDATES);
            for (int i = 0; i < updates.length; i++)
            {
                changed |= applyEffect(updates[i], null, batch, false);
            }
            int seqStart = combined
                    ? obj.intAt(Api.F_UPDATES_COMBINED__SEQ_START)
                    : obj.intAt(Api.F_UPDATES__SEQ);
            int remoteSeq = combined
                    ? obj.intAt(Api.F_UPDATES_COMBINED__SEQ)
                    : obj.intAt(Api.F_UPDATES__SEQ);
            if (seqStart == 0 || state.seq + 1 == seqStart)
            {
                if (remoteSeq != 0) { state.seq = remoteSeq; }
                state.date = obj.intAt(combined
                        ? Api.F_UPDATES_COMBINED__DATE : Api.F_UPDATES__DATE);
                changed = true;
            }
            else if (state.seq + 1 < seqStart)
            {
                requestRecovery("seq gap " + state.seq + " -> " + seqStart);
            }
        }
        else if (obj.id == Api.UPDATES_TOO_LONG)
        {
            requestRecovery("updatesTooLong");
        }
        else
        {
            changed = applyEffect(obj, envelope.sentPeer, batch, false);
        }

        if (changed) { saveState(); }
        publish(batch);
    }

    /**
     * Apply the visible effect of one Update. In authoritative difference mode
     * cursor checks are skipped because its returned State supersedes them.
     */
    private boolean applyEffect(TlObj update, Peer channelHint,
            PendingBatch batch, boolean authoritative)
    {
        if (update == null) { return false; }
        if (update.id == Api.UPDATE_NEW_MESSAGE)
        {
            if (authoritative || applyCommonPts(
                    update.intAt(Api.F_UPDATE_NEW_MESSAGE__PTS),
                    update.intAt(Api.F_UPDATE_NEW_MESSAGE__PTS_COUNT)))
            {
                Message message = Message.from(
                        update.obj(Api.F_UPDATE_NEW_MESSAGE__MESSAGE), peers);
                if (message != null) { batch.messages.addElement(message); }
                return true;
            }
            return false;
        }
        if (update.id == Api.UPDATE_NEW_CHANNEL_MESSAGE)
        {
            TlObj raw = update.obj(Api.F_UPDATE_NEW_CHANNEL_MESSAGE__MESSAGE);
            Message message = Message.from(raw, peers);
            Peer channel = message == null ? channelHint : message.peer;
            int remotePts = update.intAt(Api.F_UPDATE_NEW_CHANNEL_MESSAGE__PTS);
            int count = update.intAt(Api.F_UPDATE_NEW_CHANNEL_MESSAGE__PTS_COUNT);
            if (authoritative || applyChannelPts(channel, remotePts, count))
            {
                if (message != null) { batch.messages.addElement(message); }
                return true;
            }
            return false;
        }
        if (update.id == Api.UPDATE_READ_HISTORY_INBOX
                || update.id == Api.UPDATE_READ_HISTORY_OUTBOX)
        {
            boolean inbox = update.id == Api.UPDATE_READ_HISTORY_INBOX;
            int pts = update.intAt(inbox
                    ? Api.F_UPDATE_READ_HISTORY_INBOX__PTS
                    : Api.F_UPDATE_READ_HISTORY_OUTBOX__PTS);
            int count = update.intAt(inbox
                    ? Api.F_UPDATE_READ_HISTORY_INBOX__PTS_COUNT
                    : Api.F_UPDATE_READ_HISTORY_OUTBOX__PTS_COUNT);
            if (!authoritative && !applyCommonPts(pts, count)) { return false; }
            ReadState read = new ReadState();
            read.peer = peers.resolve(Peer.fromPeerObj(update.obj(inbox
                    ? Api.F_UPDATE_READ_HISTORY_INBOX__PEER
                    : Api.F_UPDATE_READ_HISTORY_OUTBOX__PEER)));
            if (inbox)
            {
                read.inboxMaxId = update.intAt(
                        Api.F_UPDATE_READ_HISTORY_INBOX__MAX_ID);
                read.unreadCount = update.intAt(
                        Api.F_UPDATE_READ_HISTORY_INBOX__STILL_UNREAD_COUNT);
            }
            else
            {
                read.outboxMaxId = update.intAt(
                        Api.F_UPDATE_READ_HISTORY_OUTBOX__MAX_ID);
            }
            batch.reads.addElement(read);
            return true;
        }
        if (update.id == Api.UPDATE_READ_CHANNEL_INBOX)
        {
            long id = update.num(Api.F_UPDATE_READ_CHANNEL_INBOX__CHANNEL_ID);
            int pts = update.intAt(Api.F_UPDATE_READ_CHANNEL_INBOX__PTS);
            int local = state.channelPts(id);
            if (!authoritative && local >= 0 && pts < local) { return false; }
            state.setChannelPts(id, pts);
            ReadState read = new ReadState();
            read.peer = peers.resolve(new Peer(Peer.CHANNEL, id));
            read.inboxMaxId = update.intAt(Api.F_UPDATE_READ_CHANNEL_INBOX__MAX_ID);
            read.unreadCount = update.intAt(
                    Api.F_UPDATE_READ_CHANNEL_INBOX__STILL_UNREAD_COUNT);
            batch.reads.addElement(read);
            return true;
        }
        if (update.id == Api.UPDATE_READ_CHANNEL_OUTBOX)
        {
            ReadState read = new ReadState();
            read.peer = peers.resolve(new Peer(Peer.CHANNEL,
                    update.num(Api.F_UPDATE_READ_CHANNEL_OUTBOX__CHANNEL_ID)));
            read.outboxMaxId = update.intAt(
                    Api.F_UPDATE_READ_CHANNEL_OUTBOX__MAX_ID);
            batch.reads.addElement(read);
            return true;
        }
        if (update.id == Api.UPDATE_CHANNEL_TOO_LONG)
        {
            long id = update.num(Api.F_UPDATE_CHANNEL_TOO_LONG__CHANNEL_ID);
            int supplied = update.intAt(Api.F_UPDATE_CHANNEL_TOO_LONG__PTS);
            if (state.channelPts(id) < 0 && supplied > 0)
            {
                state.setChannelPts(id, supplied);
            }
            Peer channel = peers.get(Peer.CHANNEL, id);
            requestChannelRecovery(channel == null
                    ? new Peer(Peer.CHANNEL, id) : channel);
            return true;
        }
        if (update.id == Api.UPDATE_MESSAGE_REACTIONS)
        {
            ReactionUpdate changed = new ReactionUpdate();
            changed.peer = peers.resolve(Peer.fromPeerObj(
                    update.obj(Api.F_UPDATE_MESSAGE_REACTIONS__PEER)));
            changed.messageId = update.intAt(
                    Api.F_UPDATE_MESSAGE_REACTIONS__MSG_ID);
            changed.reactions = ReactionSummary.from(update.obj(
                    Api.F_UPDATE_MESSAGE_REACTIONS__REACTIONS));
            batch.reactions.addElement(changed);
            return true;
        }
        if (update.id == Api.UPDATE_EDIT_MESSAGE
                || update.id == Api.UPDATE_EDIT_CHANNEL_MESSAGE
                || update.id == Api.UPDATE_DELETE_MESSAGES
                || update.id == Api.UPDATE_DELETE_CHANNEL_MESSAGES)
        {
            batch.fullRefresh = true;
            if (!authoritative) { requestRecovery("unsupported message mutation"); }
            return false;
        }
        return false;
    }

    private boolean applyCommonPts(int remotePts, int count)
    {
        int expected = state.pts + count;
        if (expected == remotePts)
        {
            state.pts = remotePts;
            return true;
        }
        if (expected > remotePts) { return false; }
        requestRecovery("pts gap " + state.pts + " +" + count + " < " + remotePts);
        return false;
    }

    private boolean applyChannelPts(Peer channel, int remotePts, int count)
    {
        if (channel == null || channel.kind != Peer.CHANNEL)
        {
            requestRecovery("channel update without peer");
            return false;
        }
        int local = state.channelPts(channel.id);
        if (local < 0)
        {
            // Start at the state immediately before this update and fetch it
            // through the authoritative channel difference path.
            state.setChannelPts(channel.id, remotePts - count);
            requestChannelRecovery(channel);
            return false;
        }
        int expected = local + count;
        if (expected == remotePts)
        {
            state.setChannelPts(channel.id, remotePts);
            return true;
        }
        if (expected > remotePts) { return false; }
        requestChannelRecovery(channel);
        return false;
    }

    private Message shortUserMessage(TlObj obj)
    {
        Message message = new Message();
        message.id = obj.intAt(Api.F_UPDATE_SHORT_MESSAGE__ID);
        message.date = obj.intAt(Api.F_UPDATE_SHORT_MESSAGE__DATE);
        message.outgoing = obj.num(Api.F_UPDATE_SHORT_MESSAGE__OUT) != 0;
        message.peer = peers.resolve(new Peer(Peer.USER,
                obj.num(Api.F_UPDATE_SHORT_MESSAGE__USER_ID)));
        message.sender = message.outgoing ? peers.self() : message.peer;
        message.text = obj.strOrEmpty(Api.F_UPDATE_SHORT_MESSAGE__MESSAGE);
        return message;
    }

    private Message shortChatMessage(TlObj obj)
    {
        Message message = new Message();
        message.id = obj.intAt(Api.F_UPDATE_SHORT_CHAT_MESSAGE__ID);
        message.date = obj.intAt(Api.F_UPDATE_SHORT_CHAT_MESSAGE__DATE);
        message.outgoing = obj.num(Api.F_UPDATE_SHORT_CHAT_MESSAGE__OUT) != 0;
        message.peer = peers.resolve(new Peer(Peer.CHAT,
                obj.num(Api.F_UPDATE_SHORT_CHAT_MESSAGE__CHAT_ID)));
        message.sender = peers.resolve(new Peer(Peer.USER,
                obj.num(Api.F_UPDATE_SHORT_CHAT_MESSAGE__FROM_ID)));
        message.text = obj.strOrEmpty(Api.F_UPDATE_SHORT_CHAT_MESSAGE__MESSAGE);
        return message;
    }

    private void appendMessages(TlObj[] raw, PendingBatch batch)
    {
        for (int i = 0; i < raw.length; i++)
        {
            Message message = Message.from(raw[i], peers);
            if (message != null) { batch.messages.addElement(message); }
        }
    }

    private TlObj parseInvoke(byte[] query) throws IOException
    {
        byte[] result = invoker.invoke(query);
        TlObj obj = TlParser.parse(new TlReader(result));
        if (obj == null) { throw new IOException("empty update-state response"); }
        return obj;
    }

    private void adoptState(TlObj source) throws IOException
    {
        if (source == null || source.id != Api.UPDATES_STATE)
        {
            throw new IOException("expected updates.state, got "
                    + (source == null ? "null" : Integer.toHexString(source.id)));
        }
        state.pts = source.intAt(Api.F_UPDATES_STATE__PTS);
        state.qts = source.intAt(Api.F_UPDATES_STATE__QTS);
        state.date = source.intAt(Api.F_UPDATES_STATE__DATE);
        state.seq = source.intAt(Api.F_UPDATES_STATE__SEQ);
    }

    private void requestRecovery(String detail)
    {
        synchronized (lock)
        {
            recoveryNeeded = true;
            lastDetail = detail;
            lock.notifyAll();
        }
        Diag.warn(detail + "; scheduling getDifference");
    }

    private void scheduleChannelPoll(Peer channel, int seconds)
    {
        if (channel == null) { return; }
        synchronized (lock)
        {
            if (activePeer != null && activePeer.kind == Peer.CHANNEL
                    && activePeer.id == channel.id)
            {
                nextChannelPoll = System.currentTimeMillis()
                        + Math.max(0, seconds) * 1000L;
                lock.notifyAll();
            }
        }
    }

    private void requestChannelRecovery(Peer channel)
    {
        if (channel == null) { return; }
        synchronized (lock)
        {
            for (int i = 0; i < channelRecovery.size(); i++)
            {
                Peer queued = (Peer) channelRecovery.elementAt(i);
                if (queued.kind == channel.kind && queued.id == channel.id) { return; }
            }
            channelRecovery.addElement(channel);
            lock.notifyAll();
        }
    }

    private int optionalTimeout(TlObj obj, int field)
    {
        int value = obj.intAt(field);
        return value > 0 ? value : 1;
    }

    private int currentEpoch()
    {
        synchronized (lock) { return epoch; }
    }

    private boolean isCurrentOnline(int token)
    {
        synchronized (lock) { return online && epoch == token; }
    }

    private void saveState()
    {
        try { store.save(state.copy()); }
        catch (IOException e) { Diag.error("update state save failed", e); }
    }

    private void publish(PendingBatch pending)
    {
        if (pending == null || pending.isEmpty()) { return; }
        UpdateBatch batch = pending.freeze();
        Listener current;
        synchronized (lock) { current = listener; }
        if (current != null)
        {
            try { current.onBatch(batch); }
            catch (Throwable t) { Diag.error("update batch listener failed", t); }
        }
    }

    private void publishState(String value, String detail)
    {
        Listener current;
        synchronized (lock)
        {
            syncState = value;
            lastDetail = detail == null ? "" : detail;
            current = listener;
        }
        UpdateBatch batch = new UpdateBatch();
        batch.syncState = value;
        batch.detail = lastDetail;
        if (current != null)
        {
            try { current.onBatch(batch); }
            catch (Throwable t) { Diag.error("update state listener failed", t); }
        }
    }
}
