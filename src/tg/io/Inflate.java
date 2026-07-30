package tg.io;

import java.io.IOException;

/**
 * DEFLATE (RFC 1951) and gzip (RFC 1952) decompression.
 *
 * <h3>Why this has to exist</h3>
 * Telegram wraps large RPC results in {@code gzip_packed}. Anything of
 * interesting size - the dialog list, message history, help.getConfig - arrives
 * compressed, so a client that cannot inflate cannot read them. CLDC 1.1 has no
 * {@code java.util.zip} at all: no Inflater, no GZIPInputStream, nothing. There
 * is no way around writing it.
 *
 * Decompression only. We never compress outgoing messages - our requests are
 * small, and DEFLATE encoding would be pure cost.
 *
 * <h3>Bounds</h3>
 * The compressed data comes from the network and its expansion ratio is
 * unbounded in principle - a few KB can inflate to hundreds of MB. On a 5 MiB
 * heap that is fatal, so the caller supplies a hard output limit and the
 * decompressor stops rather than allocating past it.
 *
 * The implementation is the straightforward one: stored, fixed-Huffman and
 * dynamic-Huffman blocks, with canonical Huffman decoding via per-length count
 * tables. It is checked byte-for-byte against java.util.zip on the desktop.
 */
public final class Inflate
{
    /** Default ceiling on output. Well above any TL response we expect. */
    public static final int DEFAULT_MAX_OUTPUT = 2 * 1024 * 1024;

    private static final int[] LENGTH_BASE = {
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258
    };
    private static final int[] LENGTH_EXTRA = {
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0
    };
    private static final int[] DIST_BASE = {
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
        8193, 12289, 16385, 24577
    };
    private static final int[] DIST_EXTRA = {
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13
    };
    /** Order in which code lengths for the code-length alphabet are stored. */
    private static final int[] CLEN_ORDER = {
        16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
    };

    private final byte[] input;
    private final int inputEnd;
    private int inputPos;
    private int bitBuffer;
    private int bitCount;

    private byte[] output;
    private int outputPos;
    private final int maxOutput;

    private Inflate(byte[] data, int off, int len, int maxOutput, int initialCapacity)
    {
        this.input = data;
        this.inputPos = off;
        this.inputEnd = off + len;
        this.maxOutput = maxOutput;
        this.output = new byte[initialCapacity];
    }

    /**
     * Inflate a gzip stream - the shape {@code gzip_packed} carries.
     *
     * @param maxOutput hard ceiling; exceeding it throws rather than allocating
     */
    public static byte[] gunzip(byte[] data, int off, int len, int maxOutput)
            throws IOException
    {
        if (len < 18)
        {
            throw new IOException("gzip stream is only " + len + " bytes");
        }
        if ((data[off] & 0xff) != 0x1f || (data[off + 1] & 0xff) != 0x8b)
        {
            throw new IOException("not a gzip stream: magic is 0x"
                                  + Integer.toHexString(data[off] & 0xff)
                                  + Integer.toHexString(data[off + 1] & 0xff));
        }
        if ((data[off + 2] & 0xff) != 8)
        {
            throw new IOException("gzip compression method "
                                  + (data[off + 2] & 0xff) + " is not DEFLATE");
        }

        int flags = data[off + 3] & 0xff;
        int p = off + 10;                       // fixed header

        if ((flags & 0x04) != 0)                // FEXTRA
        {
            if (p + 2 > off + len) { throw new IOException("truncated gzip FEXTRA"); }
            int xlen = (data[p] & 0xff) | ((data[p + 1] & 0xff) << 8);
            p += 2 + xlen;
        }
        if ((flags & 0x08) != 0)                // FNAME
        {
            while (p < off + len && data[p] != 0) { p++; }
            p++;
        }
        if ((flags & 0x10) != 0)                // FCOMMENT
        {
            while (p < off + len && data[p] != 0) { p++; }
            p++;
        }
        if ((flags & 0x02) != 0)                // FHCRC
        {
            p += 2;
        }
        if (p >= off + len)
        {
            throw new IOException("truncated gzip header");
        }

        // The trailer's ISIZE is the uncompressed length modulo 2^32; using it
        // to size the buffer avoids repeated growth for the common case.
        int trailer = off + len - 4;
        long isize = (data[trailer] & 0xffL)
                   | ((data[trailer + 1] & 0xffL) << 8)
                   | ((data[trailer + 2] & 0xffL) << 16)
                   | ((data[trailer + 3] & 0xffL) << 24);
        int hint = (isize > 0 && isize <= maxOutput) ? (int) isize : 1024;

        // The deflate stream ends 8 bytes before the end (CRC32 + ISIZE).
        int deflateLen = (off + len - 8) - p;
        if (deflateLen <= 0)
        {
            throw new IOException("gzip stream has no compressed payload");
        }
        return inflateRaw(data, p, deflateLen, maxOutput, hint);
    }

    public static byte[] gunzip(byte[] data) throws IOException
    {
        return gunzip(data, 0, data.length, DEFAULT_MAX_OUTPUT);
    }

    /** Raw DEFLATE, no gzip or zlib wrapper. */
    public static byte[] inflateRaw(byte[] data, int off, int len, int maxOutput,
                                    int initialCapacity) throws IOException
    {
        Inflate inf = new Inflate(data, off, len, maxOutput,
                                  initialCapacity < 64 ? 64 : initialCapacity);
        inf.run();
        return inf.result();
    }

    public static byte[] inflateRaw(byte[] data) throws IOException
    {
        return inflateRaw(data, 0, data.length, DEFAULT_MAX_OUTPUT, 1024);
    }

    // ------------------------------------------------------------ internal

    private byte[] result()
    {
        if (outputPos == output.length)
        {
            return output;
        }
        byte[] out = new byte[outputPos];
        System.arraycopy(output, 0, out, 0, outputPos);
        return out;
    }

    private void run() throws IOException
    {
        boolean finalBlock = false;
        while (!finalBlock)
        {
            finalBlock = readBits(1) != 0;
            int type = readBits(2);

            if (type == 0)
            {
                storedBlock();
            }
            else if (type == 1)
            {
                Huffman[] fixed = fixedTables();
                compressedBlock(fixed[0], fixed[1]);
            }
            else if (type == 2)
            {
                Huffman[] dynamic = dynamicTables();
                compressedBlock(dynamic[0], dynamic[1]);
            }
            else
            {
                throw new IOException("invalid DEFLATE block type 3");
            }
        }
    }

    private void storedBlock() throws IOException
    {
        bitBuffer = 0;
        bitCount = 0;                            // stored blocks are byte-aligned
        if (inputPos + 4 > inputEnd)
        {
            throw new IOException("truncated stored block header");
        }
        int lenLow = input[inputPos++] & 0xff;
        int lenHigh = input[inputPos++] & 0xff;
        int nlenLow = input[inputPos++] & 0xff;
        int nlenHigh = input[inputPos++] & 0xff;

        int blockLen = lenLow | (lenHigh << 8);
        int nlen = nlenLow | (nlenHigh << 8);
        if ((blockLen ^ 0xffff) != nlen)
        {
            throw new IOException("stored block length check failed");
        }
        if (inputPos + blockLen > inputEnd)
        {
            throw new IOException("stored block runs past the end of input");
        }

        ensureOutput(blockLen);
        System.arraycopy(input, inputPos, output, outputPos, blockLen);
        inputPos += blockLen;
        outputPos += blockLen;
    }

    private void compressedBlock(Huffman literals, Huffman distances) throws IOException
    {
        for (;;)
        {
            int symbol = literals.decode(this);
            if (symbol < 256)
            {
                ensureOutput(1);
                output[outputPos++] = (byte) symbol;
            }
            else if (symbol == 256)
            {
                return;                          // end of block
            }
            else
            {
                int index = symbol - 257;
                if (index >= LENGTH_BASE.length)
                {
                    throw new IOException("invalid length symbol " + symbol);
                }
                int length = LENGTH_BASE[index] + readBits(LENGTH_EXTRA[index]);

                int distSymbol = distances.decode(this);
                if (distSymbol >= DIST_BASE.length)
                {
                    throw new IOException("invalid distance symbol " + distSymbol);
                }
                int distance = DIST_BASE[distSymbol] + readBits(DIST_EXTRA[distSymbol]);
                if (distance > outputPos)
                {
                    throw new IOException("back-reference " + distance
                                          + " before the start of output");
                }

                ensureOutput(length);
                int from = outputPos - distance;
                // Byte at a time on purpose: overlapping copies are legal and
                // common in DEFLATE (a run of one repeated byte), and
                // arraycopy's behaviour there would be wrong.
                for (int i = 0; i < length; i++)
                {
                    output[outputPos++] = output[from++];
                }
            }
        }
    }

    private Huffman[] dynamicTables() throws IOException
    {
        int hlit = readBits(5) + 257;
        int hdist = readBits(5) + 1;
        int hclen = readBits(4) + 4;

        int[] clenLengths = new int[19];
        for (int i = 0; i < hclen; i++)
        {
            clenLengths[CLEN_ORDER[i]] = readBits(3);
        }
        Huffman clenTable = new Huffman(clenLengths, 19);

        int[] lengths = new int[hlit + hdist];
        int i = 0;
        while (i < lengths.length)
        {
            int symbol = clenTable.decode(this);
            if (symbol < 16)
            {
                lengths[i++] = symbol;
            }
            else if (symbol == 16)
            {
                if (i == 0) { throw new IOException("repeat code with no previous length"); }
                int prev = lengths[i - 1];
                int repeat = 3 + readBits(2);
                while (repeat-- > 0 && i < lengths.length) { lengths[i++] = prev; }
            }
            else if (symbol == 17)
            {
                int repeat = 3 + readBits(3);
                while (repeat-- > 0 && i < lengths.length) { lengths[i++] = 0; }
            }
            else
            {
                int repeat = 11 + readBits(7);
                while (repeat-- > 0 && i < lengths.length) { lengths[i++] = 0; }
            }
        }

        int[] litLengths = new int[hlit];
        int[] distLengths = new int[hdist];
        System.arraycopy(lengths, 0, litLengths, 0, hlit);
        System.arraycopy(lengths, hlit, distLengths, 0, hdist);

        return new Huffman[] {
            new Huffman(litLengths, hlit),
            new Huffman(distLengths, hdist)
        };
    }

    private static Huffman[] fixedCache;

    private static synchronized Huffman[] fixedTables()
    {
        if (fixedCache == null)
        {
            int[] lit = new int[288];
            for (int i = 0; i < 144; i++) { lit[i] = 8; }
            for (int i = 144; i < 256; i++) { lit[i] = 9; }
            for (int i = 256; i < 280; i++) { lit[i] = 7; }
            for (int i = 280; i < 288; i++) { lit[i] = 8; }

            int[] dist = new int[30];
            for (int i = 0; i < 30; i++) { dist[i] = 5; }

            fixedCache = new Huffman[] { new Huffman(lit, 288), new Huffman(dist, 30) };
        }
        return fixedCache;
    }

    private void ensureOutput(int extra) throws IOException
    {
        int needed = outputPos + extra;
        if (needed <= output.length)
        {
            return;
        }
        if (needed > maxOutput)
        {
            throw new IOException("decompressed size would exceed the "
                                  + maxOutput + " byte limit - refusing to allocate");
        }
        int cap = output.length << 1;
        while (cap < needed) { cap <<= 1; }
        if (cap > maxOutput) { cap = maxOutput; }
        byte[] bigger = new byte[cap];
        System.arraycopy(output, 0, bigger, 0, outputPos);
        output = bigger;
    }

    /** DEFLATE packs bits least-significant first within each byte. */
    int readBits(int count) throws IOException
    {
        while (bitCount < count)
        {
            if (inputPos >= inputEnd)
            {
                throw new IOException("ran out of input while reading " + count + " bits");
            }
            bitBuffer |= (input[inputPos++] & 0xff) << bitCount;
            bitCount += 8;
        }
        int value = bitBuffer & ((1 << count) - 1);
        bitBuffer >>>= count;
        bitCount -= count;
        return value;
    }

    /**
     * Canonical Huffman decoding via per-length counts and offsets - the
     * approach from RFC 1951 section 3.2.2. Slower per symbol than a lookup
     * table, but the tables are rebuilt for every dynamic block and a 15-bit
     * lookup table would cost far more to construct than it saves.
     */
    private static final class Huffman
    {
        private final int[] count = new int[16];
        private final int[] symbols;

        Huffman(int[] lengths, int n)
        {
            for (int i = 0; i < n; i++)
            {
                count[lengths[i]]++;
            }
            count[0] = 0;

            int[] offsets = new int[16];
            for (int len = 1; len < 16; len++)
            {
                offsets[len] = offsets[len - 1] + count[len - 1];
            }

            symbols = new int[n];
            for (int i = 0; i < n; i++)
            {
                if (lengths[i] != 0)
                {
                    symbols[offsets[lengths[i]]++] = i;
                }
            }
        }

        int decode(Inflate in) throws IOException
        {
            int code = 0;
            int first = 0;
            int index = 0;
            for (int len = 1; len < 16; len++)
            {
                code |= in.readBits(1);
                int n = count[len];
                if (code - first < n)
                {
                    return symbols[index + (code - first)];
                }
                index += n;
                first = (first + n) << 1;
                code <<= 1;
            }
            throw new IOException("invalid Huffman code");
        }
    }
}
