package tg.mt;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

import tg.app.Secrets;
import tg.crypto.Rng;
import tg.diag.Diag;
import tg.io.Inflate;
import tg.io.Transport;
import tg.tl.Tl;
import tg.tl.TlException;
import tg.tl.TlReader;
import tg.tl.TlWriter;

/**
 * Asynchronous MTProto connection with a blocking RPC facade.
 *
 * One writer owns all encryption and outbound framing. One reader owns all
 * receive/decrypt work and routes rpc_result by request msg_id. Callers still
 * use invoke(), which blocks on a small wait/notify waiter.
 */
public final class MtClient
{
    private static final int REPLY_TIMEOUT_MS = 60000;
    private static final int MAX_PENDING_REQUESTS = 16;
    private static final int MAX_CONTAINER_MESSAGES = 1024;
    private static final int ACK_BATCH = 32;
    private static final int ACK_DELAY_MS = 250;
    private static final int RECENT_MESSAGE_IDS = 256;
    private static final int PING_INTERVAL_MS = 60000;
    private static final int PING_DISCONNECT_SECONDS = 75;
    private static final int PONG_TIMEOUT_MS = 75000;
    private static final int HTTP_MAX_DELAY_MS = 500;
    private static final int HTTP_WAIT_AFTER_MS = 150;
    private static final int HTTP_MAX_WAIT_MS = 5000;

    public interface Listener
    {
        void onUpdate(byte[] body);
        void onConnectionLost(IOException error);
    }

    private final MtLink link;
    private final Rng rng;
    private final Object stateLock = new Object();
    private final Object writerSignal = new Object();
    private final Vector outbound = new Vector();
    private final Hashtable waiters = new Hashtable();

    private MsgIdGen ids;
    private Session session;
    private AuthKey authKey;
    private Listener listener;
    private int dcId;
    private boolean media;
    private volatile boolean connected;
    private volatile boolean running;
    private boolean initConnectionSent;
    private boolean hasSentRequest;
    private int activeRequests;

    /** Protocol maximum; emitted in small GPRS-friendly batches. */
    private final long[] pendingAcks = new long[8192];
    private int pendingAckCount;
    private long ackDueAt;
    private final long[] recentMsgIds = new long[RECENT_MESSAGE_IDS];
    private int recentMsgCount;
    private int recentMsgAt;

    private long lastPingAt;
    private long lastPingId;
    private boolean awaitingPong;

    public MtClient(Transport transport, Rng rng)
    {
        this(new AbridgedLink(transport), rng, null);
    }

    public MtClient(Transport transport, Rng rng, MsgIdGen ids)
    {
        this(new AbridgedLink(transport), rng, ids);
    }

    public MtClient(MtLink link, Rng rng)
    {
        this(link, rng, null);
    }

    public MtClient(MtLink link, Rng rng, MsgIdGen ids)
    {
        this.link = link;
        this.rng = rng;
        this.ids = ids;
    }

    public void setListener(Listener listener) { this.listener = listener; }
    public boolean isConnected() { return connected && running; }
    public AuthKey authKey() { return authKey; }
    public int dcId() { return dcId; }
    public MsgIdGen ids() { return ids; }
    public Session session() { return session; }

    public void connect(int dcId, String host, int port, int timeoutMs) throws IOException
    {
        connect(dcId, host, port, timeoutMs, false);
    }

    public void connect(int dcId, String host, int port, int timeoutMs, boolean media)
            throws IOException
    {
        close();
        this.dcId = dcId;
        this.media = media;
        link.connect(host, port, timeoutMs);
        if (ids == null) { ids = new MsgIdGen(); }
        ids.resetSession();
        connected = true;
        initConnectionSent = false;
        Diag.info("connected to dc" + dcId + " " + host + ":" + port);
    }

    public void connect(int dcId) throws IOException
    {
        String host = Dc.address(dcId);
        if (host == null)
        {
            throw new IOException("no bootstrap address for dc" + dcId
                    + "; it must come from help.getConfig");
        }
        connect(dcId, host, Dc.PORT, 30000);
    }

    public AuthKey authenticate() throws IOException
    {
        requireConnected();
        MtPlain plain = new MtPlain(link, ids);
        Handshake.Result result =
                new Handshake(plain, rng, dcId, Dc.isTest(), media).run();
        adopt(result.authKey, result.serverSalt);
        return authKey;
    }

    public void resume(AuthKey key, long salt) throws IOException
    {
        requireConnected();
        if (!key.matches(dcId, Dc.isTest()))
        {
            throw new IOException("stored auth_key is for dc" + key.dcId()
                    + (key.isTestEnvironment() ? " test" : " prod")
                    + ", we are on dc" + dcId
                    + (Dc.isTest() ? " test" : " prod"));
        }
        adopt(key, salt);
    }

    private void adopt(AuthKey key, long salt)
    {
        authKey = key;
        session = new Session(key, rng, ids, salt);
        synchronized (stateLock)
        {
            initConnectionSent = false;
            hasSentRequest = false;
            pendingAckCount = 0;
            recentMsgCount = 0;
            recentMsgAt = 0;
            activeRequests = 0;
            waiters.clear();
        }
        synchronized (writerSignal) { outbound.removeAllElements(); }
        running = true;
        connected = true;
        lastPingAt = System.currentTimeMillis();
        awaitingPong = false;
        new Thread(new Runnable()
        {
            public void run() { readerLoop(); }
        }).start();
        new Thread(new Runnable()
        {
            public void run() { writerLoop(); }
        }).start();
        Diag.info("session started: " + session.describe());
    }

    public byte[] invoke(byte[] query) throws IOException
    {
        requireSession();
        Request request = new Request(query);
        synchronized (stateLock)
        {
            if (!running) { throw new IOException("not connected"); }
            if (activeRequests >= MAX_PENDING_REQUESTS)
            {
                throw new IOException("too many pending MTProto requests");
            }
            activeRequests++;
        }
        enqueue(Outbound.forRequest(request));
        try
        {
            return request.await();
        }
        catch (RequestTimeout timeout)
        {
            cancelRequest(request);
            failConnection(timeout, true);
            throw timeout;
        }
    }

    public byte[] invokeWithSaltRetry(byte[] query) throws IOException
    {
        try { return invoke(query); }
        catch (SaltChanged e)
        {
            Diag.info("salt corrected, retrying the request");
            return invoke(query);
        }
        catch (TimeResynced e)
        {
            Diag.info("clock corrected, retrying the request");
            return invoke(query);
        }
    }

    private static final class SaltChanged extends IOException
    {
        SaltChanged() { super("server salt changed"); }
    }

    private static final class TimeResynced extends IOException
    {
        TimeResynced() { super("device clock corrected from the server"); }
    }

    private static final class RequestTimeout extends IOException
    {
        RequestTimeout(long msgId)
        {
            super("timed out waiting for a reply to msg " + msgId);
        }
    }

    private static final class Request
    {
        final byte[] query;
        long msgId;
        long sentAt;
        byte[] result;
        IOException error;
        boolean done;
        boolean wrappedInit;

        Request(byte[] query) { this.query = query; }

        synchronized void sent(long id)
        {
            msgId = id;
            sentAt = System.currentTimeMillis();
            notifyAll();
        }

        synchronized void complete(byte[] value, IOException failure)
        {
            if (done) { return; }
            result = value;
            error = failure;
            done = true;
            notifyAll();
        }

        synchronized byte[] await() throws IOException
        {
            while (!done)
            {
                long waitMs = sentAt == 0 ? REPLY_TIMEOUT_MS
                        : sentAt + REPLY_TIMEOUT_MS - System.currentTimeMillis();
                if (waitMs <= 0) { throw new RequestTimeout(msgId); }
                try { wait(waitMs); }
                catch (InterruptedException e)
                {
                    throw new IOException("interrupted while waiting for MTProto reply");
                }
                if (!done && sentAt == 0) { throw new RequestTimeout(0); }
            }
            if (error != null) { throw error; }
            return result;
        }
    }

    private static final class Outbound
    {
        Request request;
        byte[] body;

        static Outbound forRequest(Request request)
        {
            Outbound out = new Outbound();
            out.request = request;
            return out;
        }

        static Outbound service(byte[] body)
        {
            Outbound out = new Outbound();
            out.body = body;
            return out;
        }
    }

    private void enqueue(Outbound item)
    {
        synchronized (writerSignal)
        {
            outbound.addElement(item);
            writerSignal.notifyAll();
        }
    }

    private Outbound takeOutbound()
    {
        synchronized (writerSignal)
        {
            if (outbound.size() == 0) { return null; }
            Outbound item = (Outbound) outbound.elementAt(0);
            outbound.removeElementAt(0);
            return item;
        }
    }

    private void writerLoop()
    {
        try
        {
            while (running)
            {
                Outbound item = takeOutbound();
                if (item != null)
                {
                    if (item.request != null) { sendRequest(item.request); }
                    else { sendService(item.body); }
                    continue;
                }
                long now = System.currentTimeMillis();
                byte[] ack = takeDueAcks(now);
                if (ack != null)
                {
                    sendService(ack);
                    continue;
                }
                if (link.isRequestResponse() && hasSentRequest)
                {
                    sendService(httpWaitBody());
                    continue;
                }
                if (!link.isRequestResponse())
                {
                    if (awaitingPong && now - lastPingAt >= PONG_TIMEOUT_MS)
                    {
                        throw new IOException("keepalive pong timed out");
                    }
                    if (now - lastPingAt >= PING_INTERVAL_MS)
                    {
                        sendPing();
                        continue;
                    }
                }
                waitForWriterWork(now);
            }
        }
        catch (Throwable t)
        {
            failConnection(asIo("writer failed", t), true);
        }
    }

    private void sendRequest(Request request) throws IOException
    {
        byte[] query;
        synchronized (stateLock)
        {
            request.wrappedInit = !initConnectionSent;
            query = request.wrappedInit ? wrapInitConnection(request.query)
                    : request.query;
            hasSentRequest = true;
        }
        long[] id = new long[1];
        byte[] packet = session.encryptRpc(query, id);
        Long key = new Long(id[0]);
        synchronized (stateLock) { waiters.put(key, request); }
        request.sent(id[0]);
        try
        {
            link.send(packet, 0, packet.length);
        }
        catch (IOException e)
        {
            synchronized (stateLock) { waiters.remove(key); }
            finishRequest(request, null, e);
            throw e;
        }
    }

    private void sendService(byte[] body) throws IOException
    {
        byte[] packet = session.encrypt(body, 0, body.length, false, null);
        link.send(packet, 0, packet.length);
    }

    private void sendPing() throws IOException
    {
        long pingId = rng.nextLong();
        TlWriter writer = new TlWriter(16);
        writer.writeInt(Tl.PING_DELAY_DISCONNECT);
        writer.writeLong(pingId);
        writer.writeInt(PING_DISCONNECT_SECONDS);
        sendService(writer.toByteArray());
        lastPingId = pingId;
        lastPingAt = System.currentTimeMillis();
        awaitingPong = true;
        Diag.info("keepalive ping " + pingId);
    }

    private void waitForWriterWork(long now) throws IOException
    {
        long waitMs = 1000;
        synchronized (stateLock)
        {
            if (pendingAckCount > 0)
            {
                waitMs = ackDueAt - now;
                if (waitMs < 1) { waitMs = 1; }
            }
        }
        if (!link.isRequestResponse())
        {
            long pingWait = (awaitingPong ? PONG_TIMEOUT_MS : PING_INTERVAL_MS)
                    - (now - lastPingAt);
            if (pingWait < waitMs) { waitMs = pingWait; }
            if (waitMs < 1) { waitMs = 1; }
        }
        synchronized (writerSignal)
        {
            if (outbound.size() == 0 && running)
            {
                try { writerSignal.wait(waitMs); }
                catch (InterruptedException e) { throw new IOException("writer interrupted"); }
            }
        }
    }

    private static byte[] httpWaitBody()
    {
        TlWriter writer = new TlWriter(16);
        writer.writeInt(0x9299359f);
        writer.writeInt(HTTP_MAX_DELAY_MS);
        writer.writeInt(HTTP_WAIT_AFTER_MS);
        writer.writeInt(HTTP_MAX_WAIT_MS);
        return writer.toByteArray();
    }

    private void readerLoop()
    {
        try
        {
            while (running)
            {
                int len = link.receive();
                byte[] packet = link.buffer();
                int transportError = Abridged.asTransportError(packet, len);
                if (transportError != 0)
                {
                    throw new IOException("transport error "
                            + Abridged.describeTransportError(transportError));
                }
                Session.Incoming incoming = decryptPacket(packet, len);
                processIncoming(incoming.msgId, incoming.seqNo, incoming.body);
            }
        }
        catch (Throwable t)
        {
            failConnection(asIo("reader failed", t), true);
        }
    }

    /**
     * Decrypt one packet, allowing for a carrier that hides whole AES blocks of
     * transport padding behind the framing (see
     * {@link MtLink#hiddenPaddingBlocks}).
     *
     * The retry is safe because it changes nothing about how a message is
     * authenticated: every candidate still has to reproduce the 128-bit msg_key
     * over its own plaintext, so a wrong length is rejected exactly as a forged
     * packet would be. The search stays out of {@link Session} deliberately -
     * that class must keep failing closed - and costs nothing unless a packet
     * has already failed to decrypt.
     */
    private Session.Incoming decryptPacket(byte[] packet, int len) throws IOException
    {
        try
        {
            return session.decrypt(packet, len);
        }
        catch (IOException first)
        {
            int blocks = link.hiddenPaddingBlocks();
            for (int i = 1; i <= blocks; i++)
            {
                int candidate = len - (i * 16);
                if (candidate < 40) { break; }
                try
                {
                    Session.Incoming in = session.decrypt(packet, candidate);
                    Diag.warn("carrier hid " + (i * 16) + " padding bytes inside a "
                              + len + "-byte encrypted frame");
                    return in;
                }
                catch (IOException ignored) { }
            }
            throw first;
        }
    }

    private void processIncoming(long msgId, int seqNo, byte[] body) throws IOException
    {
        rememberAck(msgId, seqNo);
        if (seenMessage(msgId))
        {
            Diag.info("duplicate incoming msg " + msgId + " ignored");
            return;
        }
        dispatch(body, msgId);
    }

    private void dispatch(byte[] body, long incomingMsgId) throws IOException
    {
        TlReader reader = new TlReader(body);
        int id = reader.peekInt();
        if (id == Tl.MSG_CONTAINER)
        {
            reader.readInt();
            int count = reader.readInt();
            if (count < 0 || count > MAX_CONTAINER_MESSAGES)
            {
                throw new TlException("implausible container size " + count);
            }
            for (int i = 0; i < count; i++)
            {
                long innerMsgId = reader.readLong();
                int innerSeq = reader.readInt();
                int innerLen = reader.readInt();
                if (innerLen < 0 || innerLen > reader.remaining())
                {
                    throw new TlException("container element " + i + " claims "
                            + innerLen + " bytes, " + reader.remaining() + " remain");
                }
                processIncoming(innerMsgId, innerSeq, reader.readRaw(innerLen));
            }
            return;
        }
        if (id == Tl.RPC_RESULT)
        {
            reader.readInt();
            long replyTo = reader.readLong();
            byte[] payload = readMaybeGzipped(reader);
            Request request = removeWaiter(replyTo);
            if (request == null)
            {
                Diag.warn("late or unknown rpc_result for msg " + replyTo);
                return;
            }
            if (request.wrappedInit)
            {
                synchronized (stateLock) { initConnectionSent = true; }
            }
            TlReader resultReader = new TlReader(payload);
            if (resultReader.remaining() >= 4
                    && resultReader.peekInt() == Tl.RPC_ERROR)
            {
                resultReader.readInt();
                int code = resultReader.readInt();
                String type = resultReader.readString();
                Diag.warn("rpc_error " + code + " " + type);
                finishRequest(request, null, new RpcError(code, type));
            }
            else
            {
                finishRequest(request, payload, null);
            }
            return;
        }
        if (id == Tl.BAD_SERVER_SALT)
        {
            reader.readInt();
            long badMsgId = reader.readLong();
            reader.readInt();
            int errorCode = reader.readInt();
            session.setSalt(reader.readLong());
            Diag.warn("bad_server_salt (" + errorCode + ") for msg "
                    + badMsgId + ", adopted new salt");
            finishBadRequest(badMsgId, new SaltChanged());
            return;
        }
        if (id == Tl.BAD_MSG_NOTIFICATION)
        {
            reader.readInt();
            long badMsgId = reader.readLong();
            int badSeq = reader.readInt();
            int errorCode = reader.readInt();
            if (errorCode == 16 || errorCode == 17 || errorCode == 20)
            {
                long before = ids.timeOffsetSeconds();
                ids.applyServerTime(incomingMsgId);
                Diag.warn("bad_msg_notification " + errorCode + " ("
                        + describeBadMsg(errorCode) + "); clock offset "
                        + before + "s -> " + ids.timeOffsetSeconds() + "s");
                finishBadRequest(badMsgId, new TimeResynced());
            }
            else
            {
                finishBadRequest(badMsgId, new IOException(
                        "bad_msg_notification " + errorCode + " ("
                        + describeBadMsg(errorCode) + ") for msg "
                        + badMsgId + " seq " + badSeq));
            }
            return;
        }
        if (id == Tl.NEW_SESSION_CREATED)
        {
            reader.readInt();
            reader.readLong();
            reader.readLong();
            session.setSalt(reader.readLong());
            Diag.info("new_session_created, salt adopted");
            return;
        }
        if (id == Tl.PONG)
        {
            reader.readInt();
            reader.readLong();
            long pingId = reader.readLong();
            if (pingId == lastPingId)
            {
                awaitingPong = false;
                Diag.info("keepalive pong " + pingId);
            }
            return;
        }
        if (id == Tl.PING || id == Tl.PING_DELAY_DISCONNECT)
        {
            reader.readInt();
            long pingId = reader.readLong();
            TlWriter pong = new TlWriter(20);
            pong.writeInt(Tl.PONG);
            pong.writeLong(incomingMsgId);
            pong.writeLong(pingId);
            enqueue(Outbound.service(pong.toByteArray()));
            return;
        }
        if (id == Tl.MSGS_ACK
            || id == Tl.MSG_DETAILED_INFO || id == Tl.MSG_NEW_DETAILED_INFO
            || id == Tl.FUTURE_SALTS || id == Tl.MSGS_STATE_INFO
            || id == Tl.MSGS_ALL_INFO)
        {
            return;
        }
        if (id == Tl.GZIP_PACKED)
        {
            reader.readInt();
            dispatch(Inflate.gunzip(reader.readBytes()), incomingMsgId);
            return;
        }
        Listener current = listener;
        if (current != null)
        {
            try { current.onUpdate(body); }
            catch (Throwable t) { Diag.error("update listener failed", t); }
        }
        else
        {
            Diag.info("unhandled update " + Tl.name(id));
        }
    }

    private static byte[] readMaybeGzipped(TlReader reader) throws IOException
    {
        if (reader.remaining() >= 4 && reader.peekInt() == Tl.GZIP_PACKED)
        {
            reader.readInt();
            byte[] compressed = reader.readBytes();
            byte[] out = Inflate.gunzip(compressed);
            Diag.info("gzip_packed " + compressed.length + " -> "
                    + out.length + " bytes");
            return out;
        }
        return reader.readRaw(reader.remaining());
    }

    private Request removeWaiter(long msgId)
    {
        synchronized (stateLock)
        {
            return (Request) waiters.remove(new Long(msgId));
        }
    }

    private void finishBadRequest(long msgId, IOException error)
    {
        Request request = removeWaiter(msgId);
        if (request == null)
        {
            Diag.warn("service error for unknown msg " + msgId);
            return;
        }
        finishRequest(request, null, error);
    }

    private void finishRequest(Request request, byte[] result, IOException error)
    {
        synchronized (stateLock)
        {
            if (activeRequests > 0) { activeRequests--; }
        }
        request.complete(result, error);
    }

    private void cancelRequest(Request request)
    {
        synchronized (stateLock)
        {
            if (request.msgId != 0) { waiters.remove(new Long(request.msgId)); }
            if (activeRequests > 0) { activeRequests--; }
        }
        request.complete(null, new RequestTimeout(request.msgId));
    }

    private void failAll(IOException error)
    {
        Vector requests = new Vector();
        synchronized (stateLock)
        {
            Enumeration values = waiters.elements();
            while (values.hasMoreElements())
            {
                requests.addElement(values.nextElement());
            }
            waiters.clear();
            activeRequests = 0;
        }
        synchronized (writerSignal)
        {
            for (int i = 0; i < outbound.size(); i++)
            {
                Outbound item = (Outbound) outbound.elementAt(i);
                if (item.request != null && !requests.contains(item.request))
                {
                    requests.addElement(item.request);
                }
            }
            outbound.removeAllElements();
            writerSignal.notifyAll();
        }
        for (int i = 0; i < requests.size(); i++)
        {
            ((Request) requests.elementAt(i)).complete(null, error);
        }
    }

    private void rememberAck(long msgId, int seqNo)
    {
        if ((seqNo & 1) == 0) { return; }
        synchronized (stateLock)
        {
            for (int i = 0; i < pendingAckCount; i++)
            {
                if (pendingAcks[i] == msgId) { return; }
            }
            if (pendingAckCount < pendingAcks.length)
            {
                pendingAcks[pendingAckCount++] = msgId;
                if (pendingAckCount == 1)
                {
                    ackDueAt = System.currentTimeMillis() + ACK_DELAY_MS;
                }
                if (pendingAckCount >= ACK_BATCH)
                {
                    ackDueAt = System.currentTimeMillis();
                }
            }
        }
        synchronized (writerSignal) { writerSignal.notifyAll(); }
    }

    private byte[] takeDueAcks(long now)
    {
        synchronized (stateLock)
        {
            if (pendingAckCount == 0 || now < ackDueAt) { return null; }
            int count = Math.min(ACK_BATCH, pendingAckCount);
            TlWriter writer = new TlWriter(16 + count * 8);
            writer.writeInt(Tl.MSGS_ACK);
            writer.writeVectorHeader(count);
            for (int i = 0; i < count; i++)
            {
                writer.writeLong(pendingAcks[i]);
            }
            pendingAckCount -= count;
            if (pendingAckCount > 0)
            {
                System.arraycopy(pendingAcks, count, pendingAcks, 0, pendingAckCount);
                ackDueAt = System.currentTimeMillis();
            }
            else { ackDueAt = 0; }
            return writer.toByteArray();
        }
    }

    private boolean seenMessage(long msgId)
    {
        synchronized (stateLock)
        {
            for (int i = 0; i < recentMsgCount; i++)
            {
                if (recentMsgIds[i] == msgId) { return true; }
            }
            recentMsgIds[recentMsgAt] = msgId;
            recentMsgAt = (recentMsgAt + 1) % recentMsgIds.length;
            if (recentMsgCount < recentMsgIds.length) { recentMsgCount++; }
            return false;
        }
    }

    private byte[] wrapInitConnection(byte[] query)
    {
        TlWriter init = new TlWriter(query.length + 128);
        init.writeInt(Tl.INIT_CONNECTION);
        init.writeInt(0);
        init.writeInt(Secrets.API_ID);
        init.writeString(Layer.DEVICE_MODEL);
        init.writeString(Layer.SYSTEM_VERSION);
        init.writeString(Layer.APP_VERSION);
        init.writeString(Layer.SYSTEM_LANG_CODE);
        init.writeString("");
        init.writeString(Layer.LANG_CODE);
        init.writeRaw(query);
        TlWriter writer = new TlWriter(init.size() + 8);
        writer.writeInt(Tl.INVOKE_WITH_LAYER);
        writer.writeInt(Layer.LAYER);
        writer.writeRaw(init.buffer(), 0, init.size());
        return writer.toByteArray();
    }

    private static String describeBadMsg(int code)
    {
        switch (code)
        {
            case 16: return "msg_id too low - the device clock is behind";
            case 17: return "msg_id too high - the device clock is ahead";
            case 18: return "msg_id is not divisible by 4";
            case 19: return "duplicate msg_id";
            case 20: return "msg_id too old";
            case 32: return "seq_no too low";
            case 33: return "seq_no too high";
            case 34: return "even seq_no on a content-related message";
            case 35: return "odd seq_no on a non-content message";
            case 48: return "bad server salt";
            case 64: return "invalid container";
            default: return "unknown";
        }
    }

    public void close()
    {
        failConnection(new IOException("connection closed"), false);
    }

    private void failConnection(IOException error, boolean notify)
    {
        boolean changed;
        synchronized (stateLock)
        {
            changed = running || connected;
            running = false;
            connected = false;
            initConnectionSent = false;
            hasSentRequest = false;
            pendingAckCount = 0;
        }
        link.close();
        synchronized (writerSignal) { writerSignal.notifyAll(); }
        if (!changed) { return; }
        failAll(error);
        if (notify)
        {
            Listener current = listener;
            if (current != null)
            {
                try { current.onConnectionLost(error); }
                catch (Throwable t) { Diag.error("connection listener failed", t); }
            }
        }
    }

    private static IOException asIo(String prefix, Throwable t)
    {
        if (t instanceof IOException) { return (IOException) t; }
        return new IOException(prefix + ": " + t.getClass().getName()
                + ": " + String.valueOf(t.getMessage()));
    }

    private void requireConnected() throws IOException
    {
        if (!connected) { throw new IOException("not connected"); }
    }

    private void requireSession() throws IOException
    {
        requireConnected();
        if (session == null)
        {
            throw new IOException("no encrypted session - call authenticate() or resume()");
        }
    }
}
