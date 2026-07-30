package tg.mt;

import java.io.IOException;

import tg.crypto.Rng;
import tg.io.Transport;

/** Intermediate and padded-intermediate MTProto packet framing. */
public final class Intermediate implements PacketFrame
{
    public static final int MAX_PACKET = Abridged.MAX_PACKET;

    private final Transport transport;
    private final Rng rng;
    private final boolean padded;
    private final boolean tagEmbedded;
    private final byte[] header = new byte[4];
    private byte[] buffer = new byte[512];
    private boolean tagSent;

    public Intermediate(Transport transport, Rng rng, boolean padded, boolean tagEmbedded)
    {
        this.transport = transport;
        this.rng = rng;
        this.padded = padded;
        this.tagEmbedded = tagEmbedded;
        reset();
    }

    public void reset()
    {
        tagSent = tagEmbedded;
    }

    public void send(byte[] payload, int off, int len) throws IOException
    {
        if ((len & 3) != 0 || len <= 0 || len > MAX_PACKET)
        {
            throw new IOException("invalid intermediate payload length " + len);
        }
        if (!tagSent)
        {
            writeInt(padded ? 0xdddddddd : 0xeeeeeeee);
            transport.write(header, 0, 4);
            tagSent = true;
        }
        int padding = padded ? rng.nextInt(16) : 0;
        writeInt(len + padding);
        transport.write(header, 0, 4);
        transport.write(payload, off, len);
        if (padding > 0)
        {
            byte[] pad = rng.nextBytes(padding);
            transport.write(pad, 0, pad.length);
        }
        transport.flush();
    }

    public int receive() throws IOException
    {
        transport.readFully(header, 0, 4);
        int len = readInt();
        if ((len & 0x80000000) != 0)
        {
            throw new IOException("unexpected intermediate quick ack");
        }
        if (len <= 0 || len > MAX_PACKET + 15)
        {
            throw new IOException("declared intermediate length " + len + " is invalid");
        }
        if (buffer.length < len) { buffer = new byte[len]; }
        transport.readFully(buffer, 0, len);
        return padded ? payloadLength(buffer, len) : len;
    }

    public byte[] buffer() { return buffer; }

    private void writeInt(int v)
    {
        header[0] = (byte) v;
        header[1] = (byte) (v >>> 8);
        header[2] = (byte) (v >>> 16);
        header[3] = (byte) (v >>> 24);
    }

    private int readInt()
    {
        return (header[0] & 0xff) | ((header[1] & 0xff) << 8)
                | ((header[2] & 0xff) << 16) | ((header[3] & 0xff) << 24);
    }

    /**
     * Padded-intermediate carries no explicit padding length. MTProto's outer
     * envelope makes it recoverable: plaintext packets carry body_length at
     * offset 16, while encrypted packets are 24 bytes plus 16-byte blocks.
     */
    private static int payloadLength(byte[] packet, int total) throws IOException
    {
        if (total == 4) { return total; }              // transport error
        if (total < 20) { throw new IOException("padded packet is too short"); }
        boolean plain = true;
        for (int i = 0; i < 8; i++)
        {
            if (packet[i] != 0) { plain = false; break; }
        }
        int payload;
        if (plain)
        {
            int body = (packet[16] & 0xff) | ((packet[17] & 0xff) << 8)
                    | ((packet[18] & 0xff) << 16) | ((packet[19] & 0xff) << 24);
            payload = 20 + body;
        }
        else
        {
            payload = total - ((total - 8) & 15);
        }
        if (payload <= 0 || payload > total || total - payload > 15)
        {
            throw new IOException("invalid padded MTProto payload length " + payload
                    + " in " + total);
        }
        return payload;
    }
}
