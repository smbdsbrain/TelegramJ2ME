package tg.api;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Versioned binary codec shared by RMS and desktop persistence tests. */
public final class UpdateStateCodec
{
    private static final int MAGIC = 0x54475550;       // TGUP
    private static final int VERSION = 1;

    private UpdateStateCodec() { }

    public static byte[] encode(UpdateState state) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64
                + state.channelCount() * 12);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeByte(VERSION);
        out.writeLong(state.accountId);
        out.writeBoolean(state.testEnvironment);
        out.writeInt(state.pts);
        out.writeInt(state.qts);
        out.writeInt(state.date);
        out.writeInt(state.seq);
        out.writeShort(state.channelCount());
        for (int i = 0; i < state.channelCount(); i++)
        {
            out.writeLong(state.channelIdAt(i));
            out.writeInt(state.channelPtsAt(i));
        }
        out.flush();
        return bytes.toByteArray();
    }

    /**
     * @return decoded state, or null when the record belongs to another
     *         account/environment
     */
    public static UpdateState decode(byte[] raw, long accountId,
            boolean testEnvironment) throws IOException
    {
        if (raw == null) { return null; }
        ByteArrayInputStream bytes = new ByteArrayInputStream(raw);
        DataInputStream in = new DataInputStream(bytes);
        if (in.readInt() != MAGIC) { throw new IOException("bad update-state magic"); }
        int version = in.readUnsignedByte();
        if (version != VERSION)
        {
            throw new IOException("unsupported update-state version " + version);
        }
        UpdateState state = new UpdateState();
        state.accountId = in.readLong();
        state.testEnvironment = in.readBoolean();
        state.pts = in.readInt();
        state.qts = in.readInt();
        state.date = in.readInt();
        state.seq = in.readInt();
        int count = in.readUnsignedShort();
        if (count > UpdateState.MAX_CHANNELS)
        {
            throw new IOException("update state has " + count + " channels");
        }
        for (int i = 0; i < count; i++)
        {
            state.setChannelPts(in.readLong(), in.readInt());
        }
        if (bytes.available() != 0)
        {
            throw new IOException("trailing update-state bytes: " + bytes.available());
        }
        if (state.accountId != accountId
                || state.testEnvironment != testEnvironment)
        {
            return null;
        }
        return state;
    }
}
