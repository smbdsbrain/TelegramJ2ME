package tg.tl;

/**
 * TL serializer.
 *
 * TL is little-endian throughout, which is the opposite of every java.io
 * DataOutput method, so none of those are usable here.
 *
 * Grows its buffer geometrically and hands out the exact bytes at the end. A
 * caller that knows the size should say so in the constructor - on a 5 MiB heap
 * a resize during a large upload is worth avoiding.
 *
 * See <a href="https://core.telegram.org/mtproto/TL">core.telegram.org/mtproto/TL</a>.
 */
public final class TlWriter
{
    private byte[] buf;
    private int pos;

    public TlWriter()
    {
        this(256);
    }

    public TlWriter(int initialCapacity)
    {
        buf = new byte[initialCapacity < 16 ? 16 : initialCapacity];
    }

    public int size()
    {
        return pos;
    }

    /** Copy of exactly the bytes written. */
    public byte[] toByteArray()
    {
        byte[] out = new byte[pos];
        System.arraycopy(buf, 0, out, 0, pos);
        return out;
    }

    /**
     * The live buffer, valid up to {@link #size()}. Avoids a copy on the hot
     * send path; do not retain it across further writes.
     */
    public byte[] buffer()
    {
        return buf;
    }

    public void reset()
    {
        pos = 0;
    }

    // --------------------------------------------------------- primitives

    public void writeInt(int v)
    {
        ensure(4);
        buf[pos++] = (byte) v;
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) (v >>> 16);
        buf[pos++] = (byte) (v >>> 24);
    }

    public void writeLong(long v)
    {
        ensure(8);
        buf[pos++] = (byte) v;
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) (v >>> 16);
        buf[pos++] = (byte) (v >>> 24);
        buf[pos++] = (byte) (v >>> 32);
        buf[pos++] = (byte) (v >>> 40);
        buf[pos++] = (byte) (v >>> 48);
        buf[pos++] = (byte) (v >>> 56);
    }

    /** TL has no bool primitive; it uses two constructors. */
    public void writeBool(boolean v)
    {
        writeInt(v ? Tl.BOOL_TRUE : Tl.BOOL_FALSE);
    }

    public void writeDouble(double v)
    {
        writeLong(Double.doubleToLongBits(v));
    }

    /**
     * int128 / int256: raw fixed-width byte sequences, NOT length-prefixed and
     * not byte-swapped. Nonces are of this shape.
     */
    public void writeRaw(byte[] data)
    {
        writeRaw(data, 0, data.length);
    }

    public void writeRaw(byte[] data, int off, int len)
    {
        ensure(len);
        System.arraycopy(data, off, buf, pos, len);
        pos += len;
    }

    /**
     * TL {@code string} / {@code bytes}.
     *
     * Under 254 bytes: a single length byte. Otherwise 0xFE and a 24-bit
     * little-endian length. Either way the whole field is zero-padded to a
     * multiple of four - the 253/254 boundary is the classic place for a
     * serializer to be off by one, so it has dedicated tests.
     */
    public void writeBytes(byte[] data)
    {
        writeBytes(data, 0, data.length);
    }

    public void writeBytes(byte[] data, int off, int len)
    {
        int start = pos;
        if (len < 254)
        {
            ensure(1);
            buf[pos++] = (byte) len;
        }
        else
        {
            ensure(4);
            buf[pos++] = (byte) 254;
            buf[pos++] = (byte) len;
            buf[pos++] = (byte) (len >>> 8);
            buf[pos++] = (byte) (len >>> 16);
        }
        writeRaw(data, off, len);

        int padding = (4 - ((pos - start) & 3)) & 3;
        ensure(padding);
        for (int i = 0; i < padding; i++)
        {
            buf[pos++] = 0;
        }
    }

    /** UTF-8, which is what TL strings carry. */
    public void writeString(String s)
    {
        writeBytes(Utf8.encode(s));
    }

    /** Vector header: constructor id then the element count. */
    public void writeVectorHeader(int count)
    {
        writeInt(Tl.VECTOR);
        writeInt(count);
    }

    public void writeLongVector(long[] values)
    {
        writeVectorHeader(values.length);
        for (int i = 0; i < values.length; i++)
        {
            writeLong(values[i]);
        }
    }

    public void writeIntVector(int[] values)
    {
        writeVectorHeader(values.length);
        for (int i = 0; i < values.length; i++)
        {
            writeInt(values[i]);
        }
    }

    // ------------------------------------------------------------ internal

    private void ensure(int extra)
    {
        int needed = pos + extra;
        if (needed <= buf.length)
        {
            return;
        }
        int cap = buf.length << 1;
        while (cap < needed)
        {
            cap <<= 1;
        }
        byte[] bigger = new byte[cap];
        System.arraycopy(buf, 0, bigger, 0, pos);
        buf = bigger;
    }
}
