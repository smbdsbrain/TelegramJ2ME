package tg.mt;

import java.io.IOException;

import tg.diag.Diag;
import tg.io.Transport;

/**
 * MTProto abridged transport framing.
 *
 * Chosen because it has the smallest per-packet overhead of the documented
 * transports - one byte for anything under 508 bytes - which matters on
 * GPRS where every byte is billed and latency is measured in seconds.
 *
 * <pre>
 *   connection opens  -> send 0xef once, before anything else
 *   each packet       -> length in 4-byte words, then the payload
 *                        words &lt; 127 : one byte
 *                        otherwise    : 0x7f, then a 24-bit little-endian count
 * </pre>
 *
 * See <a href="https://core.telegram.org/mtproto/mtproto-transports">mtproto-transports</a>.
 *
 * <h3>Bounds</h3>
 * The length prefix is the first thing a hostile or broken peer controls, and a
 * 24-bit word count can claim 64 MB. On a 5 MiB heap that allocation is fatal,
 * so it is rejected against {@link #MAX_PACKET} before any array is created.
 *
 * The receive buffer is reused across packets. Nothing here is thread safe;
 * MTProto connections are single-reader, single-writer by design.
 */
public final class Abridged implements PacketFrame
{
    /**
     * Largest packet we will accept. Comfortably above the ~1 MB upload/download
     * part limit and far below anything that would threaten the heap.
     */
    public static final int MAX_PACKET = 1024 * 1024;

    private static final int TAG = 0xef;
    private static final int LONG_LENGTH_MARKER = 0x7f;

    private final Transport transport;
    private final byte[] header = new byte[4];

    private byte[] rxBuffer = new byte[512];
    private boolean tagSent;
    private final boolean tagEmbedded;

    public Abridged(Transport transport)
    {
        this(transport, false);
    }

    public Abridged(Transport transport, boolean tagEmbedded)
    {
        this.transport = transport;
        this.tagEmbedded = tagEmbedded;
    }

    /**
     * Reset the framing state. Call after every reconnect: the 0xef tag is
     * per-connection, and re-sending it on an existing connection - or omitting
     * it on a new one - makes the server drop us with no error.
     */
    public void reset()
    {
        tagSent = tagEmbedded;
    }

    public void send(byte[] payload, int off, int len) throws IOException
    {
        if ((len & 3) != 0)
        {
            // Would be silently truncated by the word-count encoding.
            throw new IOException("abridged payload must be a multiple of 4, got " + len);
        }
        if (len > MAX_PACKET)
        {
            throw new IOException("packet of " + len + " bytes exceeds MAX_PACKET");
        }

        if (!tagSent)
        {
            header[0] = (byte) TAG;
            transport.write(header, 0, 1);
            tagSent = true;
        }

        int words = len >> 2;
        if (words < LONG_LENGTH_MARKER)
        {
            header[0] = (byte) words;
            transport.write(header, 0, 1);
        }
        else
        {
            header[0] = (byte) LONG_LENGTH_MARKER;
            header[1] = (byte) words;
            header[2] = (byte) (words >>> 8);
            header[3] = (byte) (words >>> 16);
            transport.write(header, 0, 4);
        }

        transport.write(payload, off, len);
        transport.flush();
    }

    public void send(byte[] payload) throws IOException
    {
        send(payload, 0, payload.length);
    }

    /**
     * Read one packet.
     *
     * @return the number of valid bytes in {@link #buffer()}
     */
    public int receive() throws IOException
    {
        transport.readFully(header, 0, 1);
        int words = header[0] & 0xff;

        // Bit 7 marks a quick-ack response. We never request quick acks, so
        // seeing one means the stream is desynchronised - fail loudly rather
        // than misparse everything that follows.
        if ((words & 0x80) != 0)
        {
            throw new IOException("unexpected quick-ack flag in the length byte: 0x"
                                  + Integer.toHexString(words));
        }

        if (words == LONG_LENGTH_MARKER)
        {
            transport.readFully(header, 0, 3);
            words = (header[0] & 0xff)
                  | ((header[1] & 0xff) << 8)
                  | ((header[2] & 0xff) << 16);
        }

        int len = words << 2;
        if (len <= 0 || len > MAX_PACKET)
        {
            throw new IOException("declared packet length " + len
                                  + " is outside 1.." + MAX_PACKET);
        }

        if (rxBuffer.length < len)
        {
            // Grow to exactly what is needed rather than doubling: packets this
            // large are file parts, and a doubled buffer would be dead weight
            // for the rest of the session.
            rxBuffer = new byte[len];
        }
        transport.readFully(rxBuffer, 0, len);
        return len;
    }

    /** Valid up to the length returned by the last {@link #receive()}. */
    public byte[] buffer()
    {
        return rxBuffer;
    }

    /**
     * Read a packet and return it as its own array. Convenient, but allocates -
     * prefer {@link #receive()} plus {@link #buffer()} on the hot path.
     */
    public byte[] receiveCopy() throws IOException
    {
        int len = receive();
        byte[] out = new byte[len];
        System.arraycopy(rxBuffer, 0, out, 0, len);
        return out;
    }

    /**
     * Some MTProto errors arrive as a bare 4-byte negative int rather than a
     * message - most usefully -404 when the auth_key is unknown to this DC.
     * Recognising them turns a baffling parse failure into a clear diagnosis.
     */
    public static int asTransportError(byte[] packet, int len)
    {
        if (len != 4)
        {
            return 0;
        }
        int code = (packet[0] & 0xff)
                 | ((packet[1] & 0xff) << 8)
                 | ((packet[2] & 0xff) << 16)
                 | ((packet[3] & 0xff) << 24);
        return code < 0 ? code : 0;
    }

    public static String describeTransportError(int code)
    {
        switch (code)
        {
            case -404:
                return "-404: the auth_key is not known to this data centre "
                       + "(wrong DC, or a key from the other environment)";
            case -429:
                return "-429: transport flood, too many connections";
            case -444:
                return "-444: invalid DC";
            default:
                return String.valueOf(code);
        }
    }

    public void logState(String tag)
    {
        Diag.info("abridged " + tag + " tagSent=" + tagSent
                  + " rx=" + transport.bytesRead() + " tx=" + transport.bytesWritten());
    }
}
