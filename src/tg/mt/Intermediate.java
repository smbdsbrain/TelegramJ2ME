package tg.mt;

import java.io.IOException;

import tg.crypto.Rng;
import tg.diag.Diag;
import tg.io.Transport;
import tg.mem.MemoryBudget;

/**
 * Intermediate and padded-intermediate MTProto packet framing.
 *
 * The packet ceiling comes from {@link tg.mem.MemoryBudget#packetBytes}, the
 * same number {@link Abridged} uses - both are the transport under one MTProto
 * session and a length either would refuse must be refused by both.
 */
public final class Intermediate implements PacketFrame
{
    /**
     * Padded intermediate documents 0-15 bytes of padding, and that is what we
     * emit. Received frames are held to a far looser bound: a live MTProxy was
     * measured appending 22 bytes, and since the real length is recoverable
     * from the MTProto envelope the tail is not ours to police. The cap only
     * has to keep a hostile length from driving an allocation.
     */
    public static final int MAX_PADDING = 1024;

    /**
     * An encrypted packet's length is only recoverable modulo the AES block, so
     * padding of 16 bytes or more is invisible to this layer. Two blocks covers
     * padding up to 47 bytes - twice what the worst measured carrier used - and
     * bounds the retry cost to two decrypts on a packet that already failed.
     */
    public static final int MAX_HIDDEN_BLOCKS = 2;

    private static final int MIN_PLAIN = 20;        // auth_key_id + msg_id + length
    private static final int MIN_ENCRYPTED = 40;    // key_id + msg_key + one block

    /** Above this a send buffer is used once and dropped rather than retained. */
    private static final int MAX_REUSED_SEND = 4096;

    private final Transport transport;
    private final Rng rng;
    private final boolean padded;
    private final boolean tagEmbedded;

    private final byte[] header = new byte[4];
    private byte[] buffer = new byte[512];
    private byte[] sendBuffer = new byte[512];
    private boolean tagSent;
    private boolean overPaddingReported;

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
        overPaddingReported = false;
    }

    public void send(byte[] payload, int off, int len) throws IOException
    {
        if ((len & 3) != 0 || len <= 0 || len > MemoryBudget.packetBytes())
        {
            throw new IOException("invalid intermediate payload length " + len);
        }
        int padding = padded ? rng.nextInt(16) : 0;
        int tag = tagSent ? 0 : 4;
        int total = tag + 4 + len + padding;

        // One write per packet rather than one per field. FakeTLS turns every
        // write into its own TLS record, and a 4-byte application-data record
        // cannot occur in real TLS 1.3 at all - the AEAD tag alone is 16 bytes.
        // Splitting also cost two extra TCP segments per packet on GPRS.
        byte[] out = sendBuffer;
        if (out.length < total)
        {
            // Large packets are file-upload parts; buffering one permanently
            // would be a heavy retention on a 5 MiB heap, so only small frames
            // keep their buffer.
            out = new byte[total];
            if (total <= MAX_REUSED_SEND) { sendBuffer = out; }
        }

        int p = 0;
        if (tag != 0)
        {
            putIntLe(out, 0, padded ? 0xdddddddd : 0xeeeeeeee);
            p = 4;
            tagSent = true;
        }
        putIntLe(out, p, len + padding);
        p += 4;
        System.arraycopy(payload, off, out, p, len);
        p += len;
        if (padding > 0) { rng.nextBytes(out, p, padding); }

        transport.write(out, 0, total);
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
        if (len <= 0 || len > MemoryBudget.packetBytes() + (padded ? MAX_PADDING : 0))
        {
            throw new IOException("declared intermediate length " + len + " is invalid");
        }
        if (buffer.length < len) { buffer = new byte[len]; }
        transport.readFully(buffer, 0, len);
        return padded ? payloadLength(buffer, len) : len;
    }

    public byte[] buffer() { return buffer; }

    private static void putIntLe(byte[] b, int off, int v)
    {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
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
     *
     * <p>The two branches are not equally strong, and the difference matters:
     *
     * <ul>
     * <li>A plaintext packet states its own length, so any number of trailing
     *     bytes can be discarded. Carriers do pad past the documented 15.</li>
     * <li>An encrypted packet only reveals its length modulo the AES block, so
     *     a carrier hiding a whole extra block is invisible here. That is not
     *     recoverable at this layer - the msg_key check in {@link Session} is
     *     what would notice.</li>
     * </ul>
     *
     * Invariants on return: {@code 4 <= payload <= total}, so the length never
     * exceeds what was read, and the encrypted branch still yields
     * {@code (payload - 24) % 16 == 0} as {@code Session.decrypt} requires.
     */
    private int payloadLength(byte[] packet, int total) throws IOException
    {
        if (total < 4) { throw new IOException("padded packet is only " + total + " bytes"); }

        boolean plain = total >= MIN_PLAIN;
        for (int i = 0; plain && i < 8; i++)
        {
            if (packet[i] != 0) { plain = false; }
        }
        if (!plain && total < MIN_ENCRYPTED)
        {
            // Too short to be either kind of message, so it is a bare 4-byte
            // transport error with padding behind it. Reporting -404 beats
            // reporting that the packet was an odd size.
            return 4;
        }

        int payload;
        if (plain)
        {
            int body = (packet[16] & 0xff) | ((packet[17] & 0xff) << 8)
                    | ((packet[18] & 0xff) << 16) | ((packet[19] & 0xff) << 24);
            if (body < 0 || body > total - MIN_PLAIN)
            {
                throw new IOException("padded plaintext claims " + body
                        + " body bytes inside " + total);
            }
            payload = MIN_PLAIN + body;
        }
        else
        {
            payload = total - ((total - 8) & 15);
        }

        if (payload < 4 || payload > total || total - payload > MAX_PADDING)
        {
            throw new IOException("invalid padded MTProto payload length " + payload
                    + " in " + total);
        }
        reportPadding(packet, payload, total, plain);
        return payload;
    }

    /**
     * Over-padding is a carrier quirk we tolerate but want to see, so it is
     * reported once per connection rather than per packet. The tail is
     * carrier-generated filler with no protocol content, which makes it the
     * one part of a frame that is always safe to dump.
     */
    private void reportPadding(byte[] packet, int payload, int total, boolean plain)
    {
        int pad = total - payload;
        if (pad <= 15 || overPaddingReported) { return; }
        overPaddingReported = true;
        Diag.warn("carrier padded a " + (plain ? "plaintext" : "encrypted")
                  + " frame with " + pad + " bytes, past the documented 15");
        Diag.hex("padding tail", packet, payload, pad > 64 ? 64 : pad);
    }
}
