package tg.api;

import java.io.IOException;

import tg.tl.TlReader;
import tg.tl.TlWriter;
import tg.tl.Utf8;

/** One versioned outbox record. */
public final class OutgoingMessage
{
    private static final int MAGIC = 0x54474f32; // TGO2
    private static final int VERSION = 3;
    private static final int MAX_TEXT_BYTES = 4096;
    private static final int MAX_ERROR_BYTES = 1024;

    public static final int QUEUED = 0;
    public static final int SENDING = 1;
    public static final int FAILED = 2;

    public int localId;
    public int state = QUEUED;
    public int peerKind;
    public long peerId;
    public long accessHash;
    public String peerTitle = "";
    public String text = "";
    public int replyToMessageId;

    /**
     * Topic or comment thread the message sends into, 0 for none. Stored
     * before the first send: a power cut between enqueue and send must not
     * demote a topic message to General on the retry.
     */
    public int threadRootId;

    public long randomId;
    public long createdAt;
    public int attempts;
    public long nextAttemptAt;
    public String lastError = "";

    public Peer peer()
    {
        Peer peer = new Peer(peerKind, peerId);
        peer.accessHash = accessHash;
        peer.title = peerTitle;
        return peer;
    }

    public String stateName()
    {
        switch (state)
        {
            case SENDING: return "sending";
            case FAILED: return "failed";
            default: return "queued";
        }
    }

    public static byte[] encode(OutgoingMessage message) throws IOException
    {
        byte[] title = Utf8.encode(message.peerTitle == null ? "" : message.peerTitle);
        byte[] text = Utf8.encode(message.text == null ? "" : message.text);
        byte[] error = Utf8.encode(message.lastError == null ? "" : message.lastError);
        if (title.length > 1024) { throw new IOException("outbox peer title is too large"); }
        if (text.length > MAX_TEXT_BYTES) { throw new IOException("outbox text is too large"); }
        if (error.length > MAX_ERROR_BYTES)
        {
            byte[] trimmed = new byte[MAX_ERROR_BYTES];
            System.arraycopy(error, 0, trimmed, 0, trimmed.length);
            error = trimmed;
        }

        TlWriter out = new TlWriter(80 + title.length + text.length + error.length);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(message.state);
        out.writeInt(message.peerKind);
        out.writeLong(message.peerId);
        out.writeLong(message.accessHash);
        out.writeString(message.peerTitle == null ? "" : message.peerTitle);
        out.writeString(message.text == null ? "" : message.text);
        out.writeInt(message.replyToMessageId);
        out.writeInt(message.threadRootId);
        out.writeLong(message.randomId);
        out.writeLong(message.createdAt);
        out.writeInt(message.attempts);
        out.writeLong(message.nextAttemptAt);
        out.writeString(Utf8.decode(error));
        return out.toByteArray();
    }

    public static OutgoingMessage decode(int localId, byte[] raw) throws IOException
    {
        TlReader in = new TlReader(raw);
        if (in.readInt() != MAGIC) { throw new IOException("bad outbox record magic"); }
        int version = in.readInt();
        if (version < 1 || version > VERSION)
        {
            throw new IOException("unsupported outbox version");
        }
        OutgoingMessage message = new OutgoingMessage();
        message.localId = localId;
        message.state = in.readInt();
        message.peerKind = in.readInt();
        message.peerId = in.readLong();
        message.accessHash = in.readLong();
        message.peerTitle = in.readString();
        message.text = in.readString();
        if (version >= 2) { message.replyToMessageId = in.readInt(); }
        if (version >= 3) { message.threadRootId = in.readInt(); }
        message.randomId = in.readLong();
        message.createdAt = in.readLong();
        message.attempts = in.readInt();
        message.nextAttemptAt = in.readLong();
        message.lastError = in.readString();
        if (Utf8.encode(message.peerTitle).length > 1024
                || Utf8.encode(message.text).length > MAX_TEXT_BYTES
                || Utf8.encode(message.lastError).length > MAX_ERROR_BYTES)
        {
            throw new IOException("outbox string exceeds its bound");
        }
        if (message.state < QUEUED || message.state > FAILED)
        {
            throw new IOException("invalid outbox state " + message.state);
        }
        return message;
    }

}
