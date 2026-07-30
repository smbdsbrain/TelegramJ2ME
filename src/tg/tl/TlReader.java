package tg.tl;

/**
 * TL deserializer.
 *
 * <h3>Everything here is hostile input</h3>
 * Bytes reaching this class came off a socket. A length field is whatever the
 * peer chose to send, so every read validates before it allocates - a 4-byte
 * field claiming 2 GB must throw, not attempt {@code new byte[2000000000]} and
 * take the MIDlet down with an OutOfMemoryError on a 5 MiB heap.
 *
 * That is why {@link #readBytes()} takes its limit from {@link #remaining()}
 * rather than trusting the declared length, and why {@link #readVectorCount()}
 * refuses counts that could not possibly fit in the buffer.
 *
 * Failures are {@link TlException}, never a runtime exception, so the framing
 * layer can distinguish "this packet is malformed" from "we have a bug".
 */
public final class TlReader
{
    private final byte[] buf;
    private final int start;
    private final int end;
    private int pos;

    public TlReader(byte[] data)
    {
        this(data, 0, data.length);
    }

    public TlReader(byte[] data, int off, int len)
    {
        this.buf = data;
        this.start = off;
        this.pos = off;
        this.end = off + len;
    }

    public int remaining()
    {
        return end - pos;
    }

    public int position()
    {
        return pos - start;
    }

    public boolean hasMore()
    {
        return pos < end;
    }

    public void skip(int n) throws TlException
    {
        need(n);
        pos += n;
    }

    // --------------------------------------------------------- primitives

    public int readInt() throws TlException
    {
        need(4);
        return (buf[pos++] & 0xff)
             | ((buf[pos++] & 0xff) << 8)
             | ((buf[pos++] & 0xff) << 16)
             | ((buf[pos++] & 0xff) << 24);
    }

    public long readLong() throws TlException
    {
        need(8);
        return (buf[pos++] & 0xffL)
             | ((buf[pos++] & 0xffL) << 8)
             | ((buf[pos++] & 0xffL) << 16)
             | ((buf[pos++] & 0xffL) << 24)
             | ((buf[pos++] & 0xffL) << 32)
             | ((buf[pos++] & 0xffL) << 40)
             | ((buf[pos++] & 0xffL) << 48)
             | ((buf[pos++] & 0xffL) << 56);
    }

    public boolean readBool() throws TlException
    {
        int id = readInt();
        if (id == Tl.BOOL_TRUE)  { return true; }
        if (id == Tl.BOOL_FALSE) { return false; }
        throw new TlException("expected a Bool constructor, got 0x"
                              + Integer.toHexString(id));
    }

    public double readDouble() throws TlException
    {
        return Double.longBitsToDouble(readLong());
    }

    /** Fixed-width raw field: int128 (16) and int256 (32) nonces. */
    public byte[] readRaw(int len) throws TlException
    {
        need(len);
        byte[] out = new byte[len];
        System.arraycopy(buf, pos, out, 0, len);
        pos += len;
        return out;
    }

    /**
     * TL {@code string} / {@code bytes}.
     *
     * The declared length is checked against what is actually left in the
     * buffer before a single byte is allocated.
     */
    public byte[] readBytes() throws TlException
    {
        need(1);
        int first = buf[pos++] & 0xff;

        int len;
        int headerLen;
        if (first < 254)
        {
            len = first;
            headerLen = 1;
        }
        else
        {
            need(3);
            len = (buf[pos++] & 0xff)
                | ((buf[pos++] & 0xff) << 8)
                | ((buf[pos++] & 0xff) << 16);
            headerLen = 4;
        }

        if (len < 0 || len > remaining())
        {
            throw new TlException("bytes length " + len + " exceeds the "
                                  + remaining() + " bytes remaining");
        }

        byte[] out = new byte[len];
        System.arraycopy(buf, pos, out, 0, len);
        pos += len;

        int padding = (4 - ((headerLen + len) & 3)) & 3;
        if (padding > remaining())
        {
            throw new TlException("truncated padding after a bytes field");
        }
        pos += padding;
        return out;
    }

    public String readString() throws TlException
    {
        return Utf8.decode(readBytes());
    }

    /**
     * Vector header, returning the element count.
     *
     * The count is network-controlled and is the classic unbounded-allocation
     * vector: a claimed 100 million elements must be rejected here rather than
     * by an OutOfMemoryError later. Even a vector of the smallest possible
     * element needs 4 bytes per element, so anything above remaining()/4 is
     * impossible by construction.
     */
    public int readVectorCount() throws TlException
    {
        int id = readInt();
        if (id != Tl.VECTOR)
        {
            throw new TlException("expected vector 0x1cb5c415, got 0x"
                                  + Integer.toHexString(id));
        }
        return readVectorCountBare();
    }

    /** Element count without the constructor id, for `vector` used bare. */
    public int readVectorCountBare() throws TlException
    {
        int count = readInt();
        if (count < 0 || count > remaining() / 4)
        {
            throw new TlException("implausible vector length " + count
                                  + " with " + remaining() + " bytes remaining");
        }
        return count;
    }

    public long[] readLongVector() throws TlException
    {
        int n = readVectorCount();
        long[] out = new long[n];
        for (int i = 0; i < n; i++)
        {
            out[i] = readLong();
        }
        return out;
    }

    public int[] readIntVector() throws TlException
    {
        int n = readVectorCount();
        int[] out = new int[n];
        for (int i = 0; i < n; i++)
        {
            out[i] = readInt();
        }
        return out;
    }

    /** Peek the next constructor id without consuming it. */
    public int peekInt() throws TlException
    {
        need(4);
        return (buf[pos] & 0xff)
             | ((buf[pos + 1] & 0xff) << 8)
             | ((buf[pos + 2] & 0xff) << 16)
             | ((buf[pos + 3] & 0xff) << 24);
    }

    /** Assert the next constructor is the expected one. */
    public void expect(int constructorId, String what) throws TlException
    {
        int id = readInt();
        if (id != constructorId)
        {
            throw new TlException("expected " + what + " (0x"
                                  + Integer.toHexString(constructorId) + "), got 0x"
                                  + Integer.toHexString(id));
        }
    }

    // ------------------------------------------------------------ internal

    private void need(int n) throws TlException
    {
        if (n < 0 || pos + n > end)
        {
            throw new TlException("need " + n + " bytes, only "
                                  + remaining() + " remain at offset " + position());
        }
    }
}
