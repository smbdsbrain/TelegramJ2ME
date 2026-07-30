package tg.mt;

import java.io.IOException;

import tg.diag.Diag;
import tg.tl.TlException;
import tg.tl.TlReader;
import tg.tl.TlWriter;

/**
 * Unencrypted MTProto messages.
 *
 * Only the authorization-key exchange uses these - there is no key yet to
 * encrypt with. Everything afterwards goes through {@link Session}.
 *
 * <pre>
 *   auth_key_id         int64 = 0     (zero is what marks it unencrypted)
 *   message_id          int64
 *   message_data_length int32
 *   message_data        raw bytes
 * </pre>
 *
 * The server refuses to answer an unencrypted message carrying anything other
 * than the handshake constructors, so this class deliberately has no way to
 * send a general RPC.
 */
public final class MtPlain
{
    /**
     * Nothing in the handshake is remotely this large; resPQ and
     * server_DH_params_ok are a few hundred bytes. A larger claim means we are
     * misreading the stream.
     */
    private static final int MAX_BODY = 64 * 1024;

    private final PacketFrame frame;
    private final MsgIdGen ids;

    /** msg_id of the most recent server message, used to sync the clock. */
    private long lastServerMsgId;

    public MtPlain(PacketFrame frame, MsgIdGen ids)
    {
        this.frame = frame;
        this.ids = ids;
    }

    public long lastServerMsgId()
    {
        return lastServerMsgId;
    }

    public void send(byte[] body) throws IOException
    {
        TlWriter w = new TlWriter(body.length + 24);
        w.writeLong(0L);                 // auth_key_id: unencrypted
        w.writeLong(ids.next());
        w.writeInt(body.length);
        w.writeRaw(body);
        frame.send(w.buffer(), 0, w.size());
    }

    /**
     * Read one unencrypted message and return its body.
     *
     * A bare negative int in place of a message is a transport-level error;
     * -404 in particular means the data centre does not recognise our key,
     * which is what happens when a production key is used against a test DC.
     * Translating it here saves a long hunt later.
     */
    public byte[] receive() throws IOException
    {
        int len = frame.receive();
        byte[] packet = frame.buffer();

        int transportError = Abridged.asTransportError(packet, len);
        if (transportError != 0)
        {
            throw new IOException("transport error "
                                  + Abridged.describeTransportError(transportError));
        }

        TlReader r = new TlReader(packet, 0, len);
        long authKeyId = r.readLong();
        if (authKeyId != 0)
        {
            throw new TlException("expected an unencrypted message, got auth_key_id "
                                  + authKeyId);
        }

        long msgId = r.readLong();
        int bodyLen = r.readInt();
        if (bodyLen < 0 || bodyLen > MAX_BODY || bodyLen > r.remaining())
        {
            throw new TlException("implausible unencrypted body length " + bodyLen
                                  + " with " + r.remaining() + " bytes available");
        }

        lastServerMsgId = msgId;
        if (!ids.isTimeSynced())
        {
            // First contact: adopt the server's clock before generating any
            // further msg_id, or every subsequent message risks being rejected
            // as too far in the past or future.
            ids.applyServerTime(msgId);
            Diag.info("clock synced to server, offset " + ids.timeOffsetSeconds() + "s");
        }

        return r.readRaw(bodyLen);
    }
}
