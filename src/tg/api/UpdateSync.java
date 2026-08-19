package tg.api;

import java.io.IOException;
import java.util.Vector;

import tg.diag.Diag;
import tg.mem.MemoryBudget;
import tg.mt.Dc;
import tg.mt.RpcError;
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
    private static final long MAX_WAIT_MS = 30000L;
    private static final long AUDIT_INTERVAL_MS = 30000L;
    private static final int[] RETRY_DELAYS_MS = {
        1000, 2000, 4000, 8000, 15000, 30000
    };

    private static final int TASK_NONE = 0;
    private static final int TASK_COMMON = 1;
    private static final int TASK_CHANNEL = 2;
    private static final int TASK_ENVELOPE = 3;
    private static final int TASK_AUDIT = 4;

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
        boolean pushed;
        Peer sentPeer;
        String sentText;
        Peer editedPeer;
        int editedMessageId;

        Envelope(byte[] body) { this.body = body; }
    }

    /** One independently retryable channel difference. */
    private static final class ChannelJob
    {
        Peer peer;
        long dueAt;
        int failures;

        ChannelJob(Peer peer, long dueAt, int failures)
        {
            this.peer = peer;
            this.dueAt = dueAt;
            this.failures = failures;
        }
    }

    private static final class PendingBatch
    {
        final Vector messages = new Vector();
        final Vector edits = new Vector();
        final Vector reads = new Vector();
        final Vector reactions = new Vector();
        boolean fullRefresh;

        UpdateBatch freeze()
        {
            UpdateBatch out = new UpdateBatch();
            out.messages = new Message[messages.size()];
            messages.copyInto(out.messages);
            out.edits = new Message[edits.size()];
            edits.copyInto(out.edits);
            out.reads = new ReadState[reads.size()];
            reads.copyInto(out.reads);
            out.reactions = new ReactionUpdate[reactions.size()];
            reactions.copyInto(out.reactions);
            out.fullRefresh = fullRefresh;
            return out;
        }

        boolean isEmpty()
        {
            return messages.size() == 0 && edits.size() == 0 && reads.size() == 0
                    && reactions.size() == 0 && !fullRefresh;
        }
    }

    private final Invoker invoker;
    private final PeerCache peers;
    private final long auditIntervalMs;
    private final int[] retryDelaysMs;
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
    private Peer blockedChannel;
    private long commonRetryAt;
    private int commonFailures;
    private long nextAuditAt;
    private int auditFailures;
    private long lastSuccessAt;
    private String lastSource = "none";
    private int epoch;

    public UpdateSync(Invoker invoker, PeerCache peers)
    {
        this(invoker, peers, AUDIT_INTERVAL_MS, RETRY_DELAYS_MS);
    }

    /** Package-private timing seam used by deterministic desktop tests. */
    UpdateSync(Invoker invoker, PeerCache peers, long auditInterval,
               int[] retryDelays)
    {
        this.invoker = invoker;
        this.peers = peers;
        auditIntervalMs = auditInterval > 0 ? auditInterval : AUDIT_INTERVAL_MS;
        int[] source = retryDelays == null || retryDelays.length == 0
                ? RETRY_DELAYS_MS : retryDelays;
        retryDelaysMs = new int[source.length];
        System.arraycopy(source, 0, retryDelaysMs, 0, source.length);
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
            commonRetryAt = 0;
            commonFailures = 0;
            nextAuditAt = 0;
            auditFailures = 0;
            ensureWorker();
            lock.notifyAll();
        }
    }

    public void online()
    {
        synchronized (lock)
        {
            online = true;
            if (activated)
            {
                recoveryNeeded = true;
                commonRetryAt = 0;
                commonFailures = 0;
            }
            if (activePeer != null && activePeer.kind == Peer.CHANNEL)
            {
                nextChannelPoll = samePeer(blockedChannel, activePeer)
                        ? 0 : System.currentTimeMillis();
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
            nextAuditAt = 0;
            commonRetryAt = 0;
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
            commonRetryAt = 0;
            nextAuditAt = 0;
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
            blockedChannel = null;
            commonRetryAt = 0;
            commonFailures = 0;
            nextAuditAt = 0;
            auditFailures = 0;
            lastSuccessAt = 0;
            lastSource = "none";
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
        Envelope envelope = new Envelope(body);
        envelope.pushed = true;
        enqueue(envelope);
    }

    /** Feed a sendMessage Updates result and retain short-sent message context. */
    public void acceptSent(byte[] body, Peer peer, String text)
    {
        Envelope envelope = new Envelope(body);
        envelope.sentPeer = peer;
        envelope.sentText = text;
        enqueue(envelope);
    }

    /**
     * Feed an editMessage Updates result and retain which visible edit the RPC
     * itself confirmed.
     *
     * The same update can arrive unsolicited just before the rpc_result.  Its
     * pts is then correctly rejected as a duplicate, but the sender still
     * needs the authoritative message carried by its own RPC response.  The
     * cursor remains deduplicated; only that matching edit is republished.
     */
    public void acceptEdit(byte[] body, Peer peer, int messageId)
    {
        Envelope envelope = new Envelope(body);
        envelope.editedPeer = peer;
        envelope.editedMessageId = messageId;
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

    /**
     * Seed one channel's pts cursor from a response that carried it.
     *
     * Only fills an absent cursor: a present one is maintained by the update
     * stream, and a snapshot's pts taken later must not walk it backwards.
     * Exists for channels outside the dialog window - a discussion group, a
     * freshly opened forum - whose recovery would otherwise start from a full
     * snapshot every time.
     */
    public void seedChannelPts(long channelId, int pts)
    {
        if (pts <= 0) { return; }
        synchronized (lock)
        {
            if (!activated || state == null
                    || state.channelPts(channelId) >= 0) { return; }
            state.setChannelPts(channelId, pts);
            saveState();
        }
    }

    public void setActivePeer(Peer peer)
    {
        synchronized (lock)
        {
            boolean changed = !samePeer(activePeer, peer);
            if (peer == null || changed) { blockedChannel = null; }
            activePeer = peer;
            nextChannelPoll = peer != null && peer.kind == Peer.CHANNEL
                    && !samePeer(blockedChannel, peer)
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
        synchronized (lock)
        {
            long now = System.currentTimeMillis();
            String out = lastDetail;
            if (out == null) { out = ""; }
            out += (out.length() == 0 ? "" : "; ") + "source " + lastSource;
            if (lastSuccessAt > 0)
            {
                long age = now - lastSuccessAt;
                if (age < 0) { age = 0; }
                out += ", success " + (age / 1000L) + "s ago";
            }
            long next = nextActionAt(now);
            if (next > 0)
            {
                long left = next - now;
                if (left < 0) { left = 0; }
                out += ", next " + ((left + 999L) / 1000L) + "s";
            }
            out += ", queue " + queue.size();
            return out;
        }
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
            int channelFailures = 0;
            int task = TASK_NONE;
            synchronized (lock)
            {
                while (running)
                {
                    long now = System.currentTimeMillis();
                    boolean channelPollDue = online && activated
                            && activePeer != null && activePeer.kind == Peer.CHANNEL
                            && !samePeer(blockedChannel, activePeer)
                            && nextChannelPoll > 0 && nextChannelPoll <= now;
                    int dueChannel = dueChannelJob(now);
                    boolean commonDue = recoveryNeeded
                            && (commonRetryAt == 0 || commonRetryAt <= now);
                    boolean auditDue = nextAuditAt > 0 && nextAuditAt <= now;
                    if (online && activated && commonDue)
                    {
                        recoveryNeeded = false;
                        commonRetryAt = 0;
                        task = TASK_COMMON;
                        break;
                    }
                    // A required common recovery gates envelopes: applying them
                    // against a cursor with a known hole would turn a gap into
                    // apparently valid state. Channel work is independent and
                    // may continue while that retry waits.
                    if (online && activated && dueChannel >= 0)
                    {
                        ChannelJob job = (ChannelJob) channelRecovery.elementAt(
                                dueChannel);
                        channelRecovery.removeElementAt(dueChannel);
                        pollPeer = job.peer;
                        channelFailures = job.failures;
                        task = TASK_CHANNEL;
                        break;
                    }
                    if (online && activated && !recoveryNeeded && queue.size() > 0)
                    {
                        envelope = (Envelope) queue.elementAt(0);
                        queue.removeElementAt(0);
                        queueBytes -= envelope.body.length;
                        task = TASK_ENVELOPE;
                        break;
                    }
                    if (online && activated && !recoveryNeeded && auditDue)
                    {
                        nextAuditAt = 0;
                        task = TASK_AUDIT;
                        break;
                    }
                    if (online && activated && !recoveryNeeded && channelPollDue)
                    {
                        pollPeer = activePeer;
                        nextChannelPoll = 0;
                        task = TASK_CHANNEL;
                        break;
                    }
                    long next = nextActionAt(now);
                    long wait = next > 0 ? next - now : 0;
                    if (wait > MAX_WAIT_MS) { wait = MAX_WAIT_MS; }
                    if (wait < 0) { wait = 1; }
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
                if (task == TASK_COMMON) { recoverCommon(false); }
                else if (task == TASK_AUDIT) { recoverCommon(true); }
                else if (task == TASK_CHANNEL) { recoverChannel(pollPeer); }
                else if (task == TASK_ENVELOPE)
                {
                    processEnvelope(envelope);
                    if (envelope.pushed) { notePushSuccess(); }
                }
            }
            catch (IOException e)
            {
                Diag.error("update synchronisation failed", e);
                if (task == TASK_CHANNEL)
                {
                    handleChannelFailure(pollPeer, channelFailures, e);
                }
                else if (task == TASK_AUDIT) { handleAuditFailure(e); }
                else { handleCommonFailure(e); }
            }
            catch (Throwable t)
            {
                Diag.error("update worker failed", t);
                IOException failure = new IOException(t.getClass().getName()
                        + ": " + t.getMessage());
                if (task == TASK_CHANNEL)
                {
                    handleChannelFailure(pollPeer, channelFailures, failure);
                }
                else if (task == TASK_AUDIT) { handleAuditFailure(failure); }
                else { handleCommonFailure(failure); }
            }
        }
    }

    private void recoverCommon(boolean audit) throws IOException
    {
        int token = currentEpoch();
        if (!audit)
        {
            publishState(SYNCING, state.pts == 0
                    ? "initial state" : "getDifference");
        }
        if (state.pts == 0 && state.date == 0 && state.seq == 0)
        {
            TlObj initial = parseInvoke(Requests.getUpdateState());
            if (!isCurrentOnline(token)) { return; }
            adoptState(initial);
            saveState();
            completeCommon(audit, "state ready");
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
                completeCommon(audit, audit ? "poll empty" : "difference empty");
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
                completeCommon(audit,
                        "difference too long; snapshots refreshed");
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
                completeCommon(audit, audit
                        ? "poll difference applied" : "difference applied");
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
                noteSuccess("difference", "channel " + channel.id + " current",
                        false, false);
                return;
            }
        }
    }

    /** A required common retry failed; preserve the gate and try later. */
    private void handleCommonFailure(IOException error)
    {
        if (isPersistentTimestampInvalid(error))
        {
            try
            {
                rebaselineCommon();
                return;
            }
            catch (IOException resetError)
            {
                Diag.error("update state rebaseline failed", resetError);
                error = resetError;
            }
        }
        long delay = retryDelay(commonFailures, error);
        synchronized (lock)
        {
            commonFailures++;
            recoveryNeeded = true;
            commonRetryAt = System.currentTimeMillis() + delay;
            lock.notifyAll();
        }
        publishState(DEGRADED, failureDetail("difference", error, delay),
                seconds(delay));
    }

    /** A safety audit may fail without gating pushed envelopes. */
    private void handleAuditFailure(IOException error)
    {
        if (isPersistentTimestampInvalid(error))
        {
            try
            {
                rebaselineCommon();
                return;
            }
            catch (IOException resetError)
            {
                Diag.error("poll rebaseline failed", resetError);
                error = resetError;
            }
        }
        long delay = retryDelay(auditFailures, error);
        synchronized (lock)
        {
            auditFailures++;
            nextAuditAt = System.currentTimeMillis() + delay;
            lock.notifyAll();
        }
        publishState(DEGRADED, failureDetail("poll", error, delay),
                seconds(delay));
    }

    /** A channel failure is isolated from the common cursor and push queue. */
    private void handleChannelFailure(Peer peer, int failures, IOException error)
    {
        if (isTerminalChannelError(error))
        {
            synchronized (lock)
            {
                if (samePeer(activePeer, peer))
                {
                    blockedChannel = peer;
                    nextChannelPoll = 0;
                }
            }
            PendingBatch refresh = new PendingBatch();
            refresh.fullRefresh = true;
            publish(refresh);
            completeTerminalChannel("channel poll unavailable: "
                    + error.getMessage());
            return;
        }

        long delay = retryDelay(failures, error);
        scheduleChannelJob(peer, failures + 1, delay);
        publishState(DEGRADED, failureDetail("channel", error, delay),
                seconds(delay));
    }

    /** Replace an unusable common cursor and repair visible snapshots once. */
    private void rebaselineCommon() throws IOException
    {
        int token = currentEpoch();
        TlObj fresh = parseInvoke(Requests.getUpdateState());
        if (!isCurrentOnline(token)) { return; }
        adoptState(fresh);
        saveState();
        PendingBatch refresh = new PendingBatch();
        refresh.fullRefresh = true;
        publish(refresh);
        synchronized (lock)
        {
            commonRetryAt = 0;
            commonFailures = 0;
        }
        noteSuccess("difference", "invalid cursor reset; snapshots refreshed",
                true, true);
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

        appendConfirmedEdit(obj, envelope, batch);
        if (changed) { saveState(); }
        publish(batch);
    }

    /** Publish the edit confirmed by a local RPC even when its pts is stale. */
    private void appendConfirmedEdit(TlObj obj, Envelope envelope,
            PendingBatch batch)
    {
        if (obj == null || envelope.editedPeer == null
                || envelope.editedMessageId <= 0)
        {
            return;
        }
        if (obj.id == Api.UPDATE_SHORT)
        {
            appendConfirmedEdit(obj.obj(Api.F_UPDATE_SHORT__UPDATE), envelope,
                    batch);
            return;
        }
        if (obj.id == Api.UPDATES || obj.id == Api.UPDATES_COMBINED)
        {
            TlObj[] items = obj.vec(obj.id == Api.UPDATES
                    ? Api.F_UPDATES__UPDATES
                    : Api.F_UPDATES_COMBINED__UPDATES);
            for (int i = 0; i < items.length; i++)
            {
                appendConfirmedEdit(items[i], envelope, batch);
            }
            return;
        }
        if (obj.id != Api.UPDATE_EDIT_MESSAGE
                && obj.id != Api.UPDATE_EDIT_CHANNEL_MESSAGE)
        {
            return;
        }
        Message message = Message.from(obj.obj(obj.id == Api.UPDATE_EDIT_MESSAGE
                ? Api.F_UPDATE_EDIT_MESSAGE__MESSAGE
                : Api.F_UPDATE_EDIT_CHANNEL_MESSAGE__MESSAGE), peers);
        if (message == null || message.peer == null
                || message.id != envelope.editedMessageId
                || message.peer.kind != envelope.editedPeer.kind
                || message.peer.id != envelope.editedPeer.id)
        {
            return;
        }
        for (int i = 0; i < batch.edits.size(); i++)
        {
            Message existing = (Message) batch.edits.elementAt(i);
            if (existing != null && existing.id == message.id
                    && existing.peer != null
                    && existing.peer.kind == message.peer.kind
                    && existing.peer.id == message.peer.id)
            {
                return;
            }
        }
        batch.edits.addElement(message);
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
        if (update.id == Api.UPDATE_EDIT_MESSAGE)
        {
            if (authoritative || applyCommonPts(
                    update.intAt(Api.F_UPDATE_EDIT_MESSAGE__PTS),
                    update.intAt(Api.F_UPDATE_EDIT_MESSAGE__PTS_COUNT)))
            {
                Message message = Message.from(
                        update.obj(Api.F_UPDATE_EDIT_MESSAGE__MESSAGE), peers);
                if (message != null) { batch.edits.addElement(message); }
                return true;
            }
            return false;
        }
        if (update.id == Api.UPDATE_EDIT_CHANNEL_MESSAGE)
        {
            Message message = Message.from(update.obj(
                    Api.F_UPDATE_EDIT_CHANNEL_MESSAGE__MESSAGE), peers);
            Peer channel = message == null ? channelHint : message.peer;
            int remotePts = update.intAt(
                    Api.F_UPDATE_EDIT_CHANNEL_MESSAGE__PTS);
            int count = update.intAt(
                    Api.F_UPDATE_EDIT_CHANNEL_MESSAGE__PTS_COUNT);
            if (authoritative || applyChannelPts(channel, remotePts, count))
            {
                if (message != null) { batch.edits.addElement(message); }
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
        if (update.id == Api.UPDATE_READ_CHANNEL_DISCUSSION_INBOX)
        {
            // The one live carrier of per-thread read state: a topic or a
            // comment thread read on another device. No pts, so it is applied
            // unconditionally like the outbox tick above - and it carries no
            // unread count, which is why that field stays at its "not carried"
            // default rather than zeroing a badge.
            ReadState read = new ReadState();
            read.peer = peers.resolve(new Peer(Peer.CHANNEL, update.num(
                    Api.F_UPDATE_READ_CHANNEL_DISCUSSION_INBOX__CHANNEL_ID)));
            read.threadRootId = update.intAt(
                    Api.F_UPDATE_READ_CHANNEL_DISCUSSION_INBOX__TOP_MSG_ID);
            read.inboxMaxId = update.intAt(
                    Api.F_UPDATE_READ_CHANNEL_DISCUSSION_INBOX__READ_MAX_ID);
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
        if (update.id == Api.UPDATE_DELETE_MESSAGES
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
        message.entities = MessageEntity.fromOrDetect(
                obj.vec(Api.F_UPDATE_SHORT_MESSAGE__ENTITIES), message.text,
                MemoryBudget.messageEntityLimit());
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
        message.entities = MessageEntity.fromOrDetect(
                obj.vec(Api.F_UPDATE_SHORT_CHAT_MESSAGE__ENTITIES), message.text,
                MemoryBudget.messageEntityLimit());
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
                    && activePeer.id == channel.id
                    && !samePeer(blockedChannel, channel))
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
            // A terminal channel error is sticky for this open-chat session.
            // Only leaving and reopening the peer clears it in setActivePeer;
            // otherwise each pushed hint could restart a futile RPC loop.
            if (samePeer(blockedChannel, channel)) { return; }
            for (int i = 0; i < channelRecovery.size(); i++)
            {
                ChannelJob queued = (ChannelJob) channelRecovery.elementAt(i);
                if (samePeer(queued.peer, channel)) { return; }
            }
            channelRecovery.addElement(new ChannelJob(channel, 0, 0));
            lock.notifyAll();
        }
    }

    private void scheduleChannelJob(Peer channel, int failures, long delay)
    {
        if (channel == null) { return; }
        synchronized (lock)
        {
            long due = System.currentTimeMillis() + (delay > 0 ? delay : 0);
            for (int i = 0; i < channelRecovery.size(); i++)
            {
                ChannelJob queued = (ChannelJob) channelRecovery.elementAt(i);
                if (!samePeer(queued.peer, channel)) { continue; }
                if (due < queued.dueAt) { queued.dueAt = due; }
                if (failures > queued.failures) { queued.failures = failures; }
                lock.notifyAll();
                return;
            }
            channelRecovery.addElement(new ChannelJob(channel, due, failures));
            lock.notifyAll();
        }
    }

    /** Index of the earliest channel job that is due, or -1. */
    private int dueChannelJob(long now)
    {
        int found = -1;
        long earliest = Long.MAX_VALUE;
        for (int i = 0; i < channelRecovery.size(); i++)
        {
            ChannelJob job = (ChannelJob) channelRecovery.elementAt(i);
            if (job.dueAt <= now && job.dueAt < earliest)
            {
                earliest = job.dueAt;
                found = i;
            }
        }
        return found;
    }

    private int optionalTimeout(TlObj obj, int field)
    {
        int value = obj.intAt(field);
        return value > 0 ? value : 1;
    }

    /** Earliest deadline the one update worker should wake for. Lock held. */
    private long nextActionAt(long now)
    {
        if (!online || !activated) { return 0; }
        long next = Long.MAX_VALUE;
        if (recoveryNeeded)
        {
            next = commonRetryAt > 0 ? commonRetryAt : now;
        }
        for (int i = 0; i < channelRecovery.size(); i++)
        {
            ChannelJob job = (ChannelJob) channelRecovery.elementAt(i);
            if (job.dueAt < next) { next = job.dueAt; }
        }
        if (!recoveryNeeded)
        {
            if (queue.size() > 0) { return now; }
            if (nextAuditAt > 0 && nextAuditAt < next) { next = nextAuditAt; }
            if (activePeer != null && activePeer.kind == Peer.CHANNEL
                    && !samePeer(blockedChannel, activePeer)
                    && nextChannelPoll > 0 && nextChannelPoll < next)
            {
                next = nextChannelPoll;
            }
        }
        return next == Long.MAX_VALUE ? 0 : next;
    }

    private long retryDelay(int failures, IOException error)
    {
        if (error instanceof RpcError)
        {
            int flood = ((RpcError) error).floodWaitSeconds();
            if (flood >= 0)
            {
                long delay = (long) flood * 1000L;
                return delay > 0 ? delay : 1000L;
            }
        }
        int at = failures;
        if (at < 0) { at = 0; }
        if (at >= retryDelaysMs.length) { at = retryDelaysMs.length - 1; }
        int delay = retryDelaysMs[at];
        return delay > 0 ? delay : 1L;
    }

    private static int seconds(long delay)
    {
        long value = (delay + 999L) / 1000L;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static String failureDetail(String operation, IOException error,
                                        long delay)
    {
        String message = error == null ? "unknown failure" : error.getMessage();
        if (message == null || message.length() == 0)
        {
            message = error == null ? "unknown failure"
                    : error.getClass().getName();
        }
        return operation + " failed: " + message + "; retry "
                + seconds(delay) + "s";
    }

    private static boolean isPersistentTimestampInvalid(IOException error)
    {
        if (!(error instanceof RpcError)) { return false; }
        String type = ((RpcError) error).type();
        return "PERSISTENT_TIMESTAMP_INVALID".equals(type)
                || "PERSISTENT_TIMESTAMP_EMPTY".equals(type);
    }

    private static boolean isTerminalChannelError(IOException error)
    {
        if (!(error instanceof RpcError)) { return false; }
        String type = ((RpcError) error).type();
        return "CHANNEL_INVALID".equals(type)
                || "CHANNEL_PRIVATE".equals(type)
                || "PUBLIC_GROUP_NA".equals(type)
                || "CHANNEL_PUBLIC_GROUP_NA".equals(type)
                || "USER_BANNED_IN_CHANNEL".equals(type);
    }

    private void completeCommon(boolean audit, String detail)
    {
        synchronized (lock)
        {
            commonRetryAt = 0;
            commonFailures = 0;
            auditFailures = 0;
        }
        noteSuccess(audit ? "poll" : "difference", detail, !audit, true);
    }

    /** A parsed unsolicited body proves that the push path is alive. */
    private void notePushSuccess()
    {
        boolean recovered;
        synchronized (lock)
        {
            lastSource = "push";
            lastSuccessAt = System.currentTimeMillis();
            nextAuditAt = lastSuccessAt + auditIntervalMs;
            auditFailures = 0;
            recovered = DEGRADED.equals(syncState) && lastDetail != null
                    && lastDetail.indexOf("poll failed:") == 0;
            if (recovered)
            {
                syncState = LIVE;
                lastDetail = "push resumed";
            }
            lock.notifyAll();
        }
        if (recovered) { emitState(LIVE, "push resumed", -1); }
    }

    private void noteSuccess(String source, String detail, boolean announce,
                             boolean resetAudit)
    {
        boolean notify;
        synchronized (lock)
        {
            String previous = syncState;
            lastSource = source == null ? "none" : source;
            lastSuccessAt = System.currentTimeMillis();
            if (resetAudit)
            {
                nextAuditAt = lastSuccessAt + auditIntervalMs;
                auditFailures = 0;
            }
            // A successful channel RPC repairs only that channel. It must not
            // paint over a common/audit degraded state whose retry is still
            // pending. A channel's own retry is identifiable by its detail and
            // may return the header to live.
            if (!resetAudit && DEGRADED.equals(previous)
                    && (lastDetail == null
                            || lastDetail.indexOf("channel failed:") != 0))
            {
                lock.notifyAll();
                return;
            }
            syncState = LIVE;
            lastDetail = detail == null ? "" : detail;
            notify = announce || !LIVE.equals(previous);
            lock.notifyAll();
        }
        if (notify) { emitState(LIVE, detail, -1); }
    }

    /** A terminal channel result is not allowed to clear another retry. */
    private void completeTerminalChannel(String detail)
    {
        synchronized (lock)
        {
            if (DEGRADED.equals(syncState) && (lastDetail == null
                    || lastDetail.indexOf("channel failed:") != 0))
            {
                return;
            }
        }
        publishState(LIVE, detail);
    }

    private static boolean samePeer(Peer a, Peer b)
    {
        return a == b || (a != null && b != null
                && a.kind == b.kind && a.id == b.id);
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
        publishState(value, detail, -1);
    }

    private void publishState(String value, String detail, int retrySeconds)
    {
        synchronized (lock)
        {
            syncState = value;
            lastDetail = detail == null ? "" : detail;
        }
        emitState(value, detail, retrySeconds);
    }

    private void emitState(String value, String detail, int retrySeconds)
    {
        Listener current;
        synchronized (lock) { current = listener; }
        UpdateBatch batch = new UpdateBatch();
        batch.syncState = value;
        batch.detail = detail == null ? "" : detail;
        batch.retrySeconds = retrySeconds;
        if (current != null)
        {
            try { current.onBatch(batch); }
            catch (Throwable t) { Diag.error("update state listener failed", t); }
        }
    }
}
