package tg.ui;

import java.io.IOException;
import java.io.InputStream;

import javax.microedition.lcdui.Image;

import tg.api.DownloadToken;
import tg.mem.MemoryBudget;

/**
 * Bounded pure-Java JPEG decoder for the image formats produced by Telegram.
 *
 * Supports 8-bit Huffman SOF0/SOF2, grayscale and YCbCr 4:4:4, 4:2:2 and
 * 4:2:0, including restart markers. Arithmetic, lossless and four-component
 * images are rejected before coefficient/image allocation.
 *
 * The entropy/progressive and fixed-point IDCT algorithms are derived from
 * Mozilla pdf.js' Apache-2.0 licensed JPEG decoder.
 */
public final class JpegDecoder
{
    public static final class Decoded
    {
        public final int width;
        public final int height;
        public final int[] rgb;

        Decoded(int width, int height, int[] rgb)
        {
            this.width = width;
            this.height = height;
            this.rgb = rgb;
        }
    }

    private static final int[] ZIGZAG = {
         0,
         1,  8,
        16,  9,  2,
         3, 10, 17, 24,
        32, 25, 18, 11,  4,
         5, 12, 19, 26, 33, 40,
        48, 41, 34, 27, 20, 13,  6,
         7, 14, 21, 28, 35, 42, 49, 56,
        57, 50, 43, 36, 29, 22, 15,
        23, 30, 37, 44, 51, 58,
        59, 52, 45, 38, 31,
        39, 46, 53, 60,
        61, 54, 47,
        55, 62,
        63
    };

    private JpegDecoder() { }

    public static Image decode(InputStream in, DownloadToken token)
            throws IOException
    {
        Decoded decoded = decodePixels(in, token);
        return Image.createRGBImage(decoded.rgb, decoded.width,
                decoded.height, false);
    }

    public static Image decode(byte[] data, DownloadToken token)
            throws IOException
    {
        if (data == null) { throw new IOException("null JPEG data"); }
        return decode(new java.io.ByteArrayInputStream(data), token);
    }

    /** Read one bounded compressed JPEG for a small persistent preview cache. */
    public static byte[] read(InputStream in, DownloadToken token)
            throws IOException
    {
        return readCompressed(in, token);
    }

    public static Decoded decodePixels(InputStream in, DownloadToken token)
            throws IOException
    {
        byte[] data = readCompressed(in, token);
        Decoder decoder = new Decoder(data, token);
        int[] rgb = decoder.decode();
        return new Decoded(decoder.frame.width, decoder.frame.height, rgb);
    }

    private static byte[] readCompressed(InputStream in, DownloadToken token)
            throws IOException
    {
        if (in == null) { throw new IOException("null JPEG stream"); }
        // Read once: the loop must not change its mind about the ceiling
        // halfway through if the measurement lands on another thread.
        final int limit = MemoryBudget.photoCompressedBytes();
        byte[] out = new byte[Math.min(limit, 32768)];
        int used = 0;
        while (true)
        {
            checkCancelled(token);
            if (used == out.length)
            {
                if (used >= limit)
                {
                    if (in.read() < 0) { break; }
                    throw new IOException("JPEG exceeds the " + limit
                                          + " byte compressed limit");
                }
                int next = Math.min(limit, used << 1);
                byte[] grown = new byte[next];
                System.arraycopy(out, 0, grown, 0, used);
                out = grown;
            }
            int count = in.read(out, used, out.length - used);
            if (count < 0) { break; }
            if (count == 0)
            {
                int one = in.read();
                if (one < 0) { break; }
                out[used++] = (byte) one;
            }
            else
            {
                used += count;
            }
        }
        if (used < 4) { throw new IOException("truncated JPEG"); }
        byte[] exact = new byte[used];
        System.arraycopy(out, 0, exact, 0, used);
        return exact;
    }

    private static void checkCancelled(DownloadToken token) throws IOException
    {
        if (token != null && token.isCancelled())
        {
            throw new IOException("photo download cancelled");
        }
    }

    private static final class Decoder
    {
        final byte[] data;
        final DownloadToken token;
        final int[][] quant = new int[4][];
        final Huffman[] dc = new Huffman[4];
        final Huffman[] ac = new Huffman[4];
        Frame frame;
        int restartInterval;
        int scanEobRun;
        int scanAcState;
        int scanAcNext;

        Decoder(byte[] data, DownloadToken token)
        {
            this.data = data;
            this.token = token;
        }

        int[] decode() throws IOException
        {
            if (u16(0) != 0xffd8) { fail("SOI not found"); }
            int pos = 2;
            boolean ended = false;
            while (pos < data.length)
            {
                checkCancelled(token);
                while (pos < data.length && u8(pos) != 0xff) { pos++; }
                if (pos >= data.length) { break; }
                while (pos < data.length && u8(pos) == 0xff) { pos++; }
                if (pos >= data.length) { fail("truncated marker"); }
                int marker = 0xff00 | u8(pos++);
                if (marker == 0xffd9) { ended = true; break; }
                if (marker >= 0xffd0 && marker <= 0xffd7) { continue; }
                if (marker == 0xff01) { continue; }
                if (marker == 0xffda)
                {
                    pos = scan(pos);
                    continue;
                }
                int length = segmentLength(pos);
                int start = pos + 2;
                int end = pos + length;
                if (marker == 0xffdb) { parseQuant(start, end); }
                else if (marker == 0xffc4) { parseHuffman(start, end); }
                else if (marker == 0xffdd)
                {
                    if (length != 4) { fail("invalid DRI"); }
                    restartInterval = u16(start);
                }
                else if (marker == 0xffc0 || marker == 0xffc2)
                {
                    parseFrame(marker, start, end);
                }
                else if (marker == 0xffc1)
                {
                    fail("extended sequential JPEG is unsupported");
                }
                else if (marker == 0xffc3)
                {
                    fail("lossless JPEG is unsupported");
                }
                else if ((marker >= 0xffc5 && marker <= 0xffcf)
                        && marker != 0xffc8 && marker != 0xffcc)
                {
                    fail("arithmetic/differential JPEG is unsupported");
                }
                else if (marker == 0xffc8 || marker == 0xffcc)
                {
                    fail("arithmetic JPEG is unsupported");
                }
                pos = end;
            }
            if (!ended) { fail("EOI not found"); }
            if (frame == null) { fail("SOF not found"); }
            for (int i = 0; i < frame.components.length; i++)
            {
                Component c = frame.components[i];
                c.quant = c.quantId < quant.length ? quant[c.quantId] : null;
                if (c.quant == null) { fail("missing quantization table"); }
                inverseAll(c);
            }
            return render();
        }

        private int scan(int pos) throws IOException
        {
            if (frame == null) { fail("SOS before SOF"); }
            int length = segmentLength(pos);
            int end = pos + length;
            int at = pos + 2;
            int count = u8(at++);
            if (count < 1 || count > frame.components.length) {
                fail("invalid scan component count");
            }
            Component[] selected = new Component[count];
            for (int i = 0; i < count; i++)
            {
                int id = u8(at++);
                Component c = frame.component(id);
                if (c == null) { fail("unknown scan component"); }
                int tables = u8(at++);
                c.dc = dc[tables >> 4];
                c.ac = ac[tables & 15];
                selected[i] = c;
            }
            int spectralStart = u8(at++);
            int spectralEnd = u8(at++);
            int approximation = u8(at++);
            if (at != end) { fail("invalid SOS length"); }
            return decodeScan(end, selected, spectralStart, spectralEnd,
                    approximation >> 4, approximation & 15);
        }

        private int decodeScan(int pos, Component[] selected, int ss, int se,
                               int prev, int successive) throws IOException
        {
            if (!frame.progressive && (ss != 0 || se != 63 || prev != 0
                    || successive != 0))
            {
                fail("invalid baseline scan");
            }
            if (frame.progressive)
            {
                if (ss == 0 && se != 0) { fail("invalid progressive DC scan"); }
                if (ss > se || se > 63) { fail("invalid spectral selection"); }
                if (ss > 0 && selected.length != 1)
                {
                    fail("progressive AC scan must have one component");
                }
            }
            ScanBits bits = new ScanBits(data, pos);
            int expected = selected.length == 1
                    ? selected[0].blocksPerLine * selected[0].blocksPerColumn
                    : frame.mcusPerLine * frame.mcusPerColumn;
            int mcu = 0;
            int restart = 0;
            while (mcu < expected)
            {
                int interval = restartInterval == 0
                        ? expected - mcu : Math.min(restartInterval, expected - mcu);
                for (int i = 0; i < selected.length; i++) { selected[i].pred = 0; }
                scanEobRun = 0;
                scanAcState = 0;
                for (int n = 0; n < interval; n++, mcu++)
                {
                    checkCancelled(token);
                    if (selected.length == 1)
                    {
                        Component c = selected[0];
                        int row = mcu / c.blocksPerLine;
                        int col = mcu % c.blocksPerLine;
                        int off = c.blockOffset(row, col);
                        decodeBlock(bits, c, off, ss, se, prev, successive);
                    }
                    else
                    {
                        for (int i = 0; i < selected.length; i++)
                        {
                            Component c = selected[i];
                            for (int y = 0; y < c.v; y++)
                            {
                                for (int x = 0; x < c.h; x++)
                                {
                                    int row = (mcu / frame.mcusPerLine) * c.v + y;
                                    int col = (mcu % frame.mcusPerLine) * c.h + x;
                                    decodeBlock(bits, c, c.blockOffset(row, col),
                                            ss, se, prev, successive);
                                }
                            }
                        }
                    }
                }
                bits.align();
                if (mcu < expected)
                {
                    int marker = bits.readMarker();
                    if (marker < 0xffd0 || marker > 0xffd7)
                    {
                        fail("restart marker expected");
                    }
                    if ((marker & 7) != (restart & 7))
                    {
                        fail("out-of-order restart marker");
                    }
                    restart++;
                }
            }
            bits.align();
            return bits.position();
        }

        private void decodeBlock(ScanBits bits, Component c, int off,
                                  int ss, int se, int prev, int successive)
                throws IOException
        {
            if (!frame.progressive)
            {
                if (c.dc == null || c.ac == null) { fail("missing Huffman table"); }
                int t = c.dc.decode(bits);
                int diff = t == 0 ? 0 : bits.receiveExtend(t);
                c.pred += diff;
                c.coeff[off] = checkedShort(c.pred);
                int k = 1;
                while (k < 64)
                {
                    int rs = c.ac.decode(bits);
                    int s = rs & 15;
                    int r = rs >> 4;
                    if (s == 0)
                    {
                        if (r < 15) { break; }
                        k += 16;
                    }
                    else
                    {
                        k += r;
                        if (k >= 64) { fail("bad AC run"); }
                        c.coeff[off + ZIGZAG[k]] = checkedShort(bits.receiveExtend(s));
                        k++;
                    }
                }
                return;
            }
            if (ss == 0)
            {
                if (prev == 0)
                {
                    if (c.dc == null) { fail("missing DC Huffman table"); }
                    int t = c.dc.decode(bits);
                    int diff = t == 0 ? 0 : bits.receiveExtend(t);
                    c.pred += diff << successive;
                    c.coeff[off] = checkedShort(c.pred);
                }
                else
                {
                    c.coeff[off] = (short) (c.coeff[off]
                            | (bits.bit() << successive));
                }
                return;
            }
            if (c.ac == null) { fail("missing AC Huffman table"); }
            if (prev == 0)
            {
                if (scanEobRun > 0)
                {
                    scanEobRun--;
                    return;
                }
                int k = ss;
                while (k <= se)
                {
                    int rs = c.ac.decode(bits);
                    int s = rs & 15;
                    int r = rs >> 4;
                    if (s == 0)
                    {
                        if (r < 15)
                        {
                            scanEobRun = bits.receive(r) + (1 << r) - 1;
                            break;
                        }
                        k += 16;
                    }
                    else
                    {
                        k += r;
                        if (k > se) { fail("bad progressive AC run"); }
                        c.coeff[off + ZIGZAG[k]] =
                                checkedShort(bits.receiveExtend(s) << successive);
                        k++;
                    }
                }
                return;
            }

            int k = ss;
            int run = 0;
            while (k <= se)
            {
                int z = off + ZIGZAG[k];
                int value = c.coeff[z];
                int sign = value < 0 ? -1 : 1;
                switch (scanAcState)
                {
                    case 0:
                        int rs = c.ac.decode(bits);
                        int s = rs & 15;
                        run = rs >> 4;
                        if (s == 0)
                        {
                            if (run < 15)
                            {
                                scanEobRun = bits.receive(run) + (1 << run);
                                scanAcState = 4;
                            }
                            else
                            {
                                run = 16;
                                scanAcState = 1;
                            }
                        }
                        else
                        {
                            if (s != 1) { fail("invalid AC refinement"); }
                            scanAcNext = bits.receiveExtend(1);
                            scanAcState = run == 0 ? 3 : 2;
                        }
                        continue;
                    case 1:
                    case 2:
                        if (value != 0)
                        {
                            c.coeff[z] = checkedShort(value
                                    + sign * (bits.bit() << successive));
                        }
                        else
                        {
                            run--;
                            if (run == 0)
                            {
                                scanAcState = scanAcState == 2 ? 3 : 0;
                            }
                        }
                        break;
                    case 3:
                        if (value != 0)
                        {
                            c.coeff[z] = checkedShort(value
                                    + sign * (bits.bit() << successive));
                        }
                        else
                        {
                            c.coeff[z] = checkedShort(scanAcNext << successive);
                            scanAcState = 0;
                        }
                        break;
                    case 4:
                        if (value != 0)
                        {
                            c.coeff[z] = checkedShort(value
                                    + sign * (bits.bit() << successive));
                        }
                        break;
                    default:
                        fail("invalid progressive state");
                }
                k++;
            }
            if (scanAcState == 4)
            {
                scanEobRun--;
                if (scanEobRun == 0) { scanAcState = 0; }
            }
        }

        private int[] render() throws IOException
        {
            int pixels = checkedPixels(frame.width, frame.height);
            int[] out = new int[pixels];
            if (frame.components.length == 1)
            {
                Component y = frame.components[0];
                for (int py = 0, at = 0; py < frame.height; py++)
                {
                    for (int px = 0; px < frame.width; px++, at++)
                    {
                        int v = y.sample(px, py, frame.maxH, frame.maxV);
                        out[at] = 0xff000000 | (v << 16) | (v << 8) | v;
                    }
                }
                return out;
            }
            Component y = frame.components[0];
            Component cb = frame.components[1];
            Component cr = frame.components[2];
            for (int py = 0, at = 0; py < frame.height; py++)
            {
                checkCancelled(token);
                for (int px = 0; px < frame.width; px++, at++)
                {
                    int yy = y.sample(px, py, frame.maxH, frame.maxV);
                    int blueDiff = cb.sample(px, py, frame.maxH, frame.maxV) - 128;
                    int redDiff = cr.sample(px, py, frame.maxH, frame.maxV) - 128;
                    int r = clamp(yy + ((91881 * redDiff) >> 16));
                    int g = clamp(yy - ((22554 * blueDiff
                            + 46802 * redDiff) >> 16));
                    int b = clamp(yy + ((116130 * blueDiff) >> 16));
                    out[at] = 0xff000000 | (r << 16) | (g << 8) | b;
                }
            }
            return out;
        }

        private void parseFrame(int marker, int start, int end)
                throws IOException
        {
            if (frame != null) { fail("multiple JPEG frames unsupported"); }
            int at = start;
            int precision = u8(at++);
            if (precision != 8) { fail("only 8-bit JPEG is supported"); }
            int height = u16(at); at += 2;
            int width = u16(at); at += 2;
            checkedPixels(width, height);
            int count = u8(at++);
            if (count != 1 && count != 3)
            {
                if (count == 4) { fail("CMYK JPEG is unsupported"); }
                fail("unsupported JPEG component count " + count);
            }
            if (end - at != count * 3) { fail("invalid SOF length"); }
            Frame f = new Frame();
            f.width = width;
            f.height = height;
            f.progressive = marker == 0xffc2;
            f.components = new Component[count];
            for (int i = 0; i < count; i++)
            {
                Component c = new Component();
                c.id = u8(at++);
                int sampling = u8(at++);
                c.h = sampling >> 4;
                c.v = sampling & 15;
                c.quantId = u8(at++);
                if (c.h < 1 || c.v < 1 || c.h > 2 || c.v > 2)
                {
                    fail("unsupported JPEG sampling");
                }
                f.maxH = Math.max(f.maxH, c.h);
                f.maxV = Math.max(f.maxV, c.v);
                f.components[i] = c;
            }
            if (count == 1)
            {
                if (f.components[0].h != 1 || f.components[0].v != 1)
                {
                    fail("unsupported grayscale sampling");
                }
            }
            else
            {
                Component y = f.components[0];
                if (f.components[1].h != 1 || f.components[1].v != 1
                        || f.components[2].h != 1 || f.components[2].v != 1
                        || !((y.h == 1 && y.v == 1)
                        || (y.h == 2 && y.v == 1)
                        || (y.h == 2 && y.v == 2)))
                {
                    fail("only YCbCr 4:4:4, 4:2:2 and 4:2:0 are supported");
                }
            }
            f.mcusPerLine = ceilDiv(width, 8 * f.maxH);
            f.mcusPerColumn = ceilDiv(height, 8 * f.maxV);
            for (int i = 0; i < count; i++)
            {
                Component c = f.components[i];
                c.blocksPerLine = ceilDiv(ceilDiv(width, 8) * c.h, f.maxH);
                c.blocksPerColumn = ceilDiv(ceilDiv(height, 8) * c.v, f.maxV);
                int allocatedLines = f.mcusPerLine * c.h;
                int allocatedColumns = f.mcusPerColumn * c.v;
                c.strideBlocks = allocatedLines + 1;
                long words = 64L * allocatedColumns * c.strideBlocks;
                if (words <= 0 || words > (long) MemoryBudget.photoPixels() * 2L)
                {
                    fail("JPEG coefficient allocation exceeds limit");
                }
                c.coeff = new short[(int) words];
            }
            frame = f;
        }

        private void parseQuant(int at, int end) throws IOException
        {
            while (at < end)
            {
                int spec = u8(at++);
                int precision = spec >> 4;
                int id = spec & 15;
                if (id >= quant.length || precision > 1)
                {
                    fail("invalid quantization table");
                }
                int[] table = new int[64];
                for (int i = 0; i < 64; i++)
                {
                    int value;
                    if (precision == 0) { value = u8(at++); }
                    else { value = u16(at); at += 2; }
                    table[ZIGZAG[i]] = value;
                }
                quant[id] = table;
            }
            if (at != end) { fail("truncated DQT"); }
        }

        private void parseHuffman(int at, int end) throws IOException
        {
            while (at < end)
            {
                int spec = u8(at++);
                int id = spec & 15;
                int kind = spec >> 4;
                if (id >= 4 || kind > 1) { fail("invalid Huffman table"); }
                int[] lengths = new int[16];
                int count = 0;
                for (int i = 0; i < 16; i++)
                {
                    lengths[i] = u8(at++);
                    count += lengths[i];
                }
                if (count < 1 || count > 256 || at + count > end)
                {
                    fail("invalid Huffman values");
                }
                byte[] values = new byte[count];
                System.arraycopy(data, at, values, 0, count);
                at += count;
                Huffman table = new Huffman(lengths, values);
                if (kind == 0) { dc[id] = table; }
                else { ac[id] = table; }
            }
            if (at != end) { fail("truncated DHT"); }
        }

        private int segmentLength(int pos) throws IOException
        {
            int length = u16(pos);
            if (length < 2 || pos + length > data.length)
            {
                fail("truncated JPEG segment");
            }
            return length;
        }

        private int u8(int pos) throws IOException
        {
            if (pos < 0 || pos >= data.length) { fail("truncated JPEG"); }
            return data[pos] & 0xff;
        }

        private int u16(int pos) throws IOException
        {
            return (u8(pos) << 8) | u8(pos + 1);
        }

        private void inverseAll(Component c) throws IOException
        {
            int[] work = new int[64];
            for (int row = 0; row < c.blocksPerColumn; row++)
            {
                checkCancelled(token);
                for (int col = 0; col < c.blocksPerLine; col++)
                {
                    inverse(c, c.blockOffset(row, col), work);
                }
            }
        }

        private void inverse(Component c, int off, int[] p)
                throws IOException
        {
            int[] qt = c.quant;
            int v0, v1, v2, v3, v4, v5, v6, v7;
            int p0, p1, p2, p3, p4, p5, p6, p7, t;
            for (int row = 0; row < 64; row += 8)
            {
                p0 = c.coeff[off + row];
                p1 = c.coeff[off + row + 1];
                p2 = c.coeff[off + row + 2];
                p3 = c.coeff[off + row + 3];
                p4 = c.coeff[off + row + 4];
                p5 = c.coeff[off + row + 5];
                p6 = c.coeff[off + row + 6];
                p7 = c.coeff[off + row + 7];
                p0 *= qt[row];
                if ((p1 | p2 | p3 | p4 | p5 | p6 | p7) == 0)
                {
                    t = (5793 * p0 + 512) >> 10;
                    for (int i = 0; i < 8; i++) { p[row + i] = t; }
                    continue;
                }
                p1 *= qt[row + 1]; p2 *= qt[row + 2];
                p3 *= qt[row + 3]; p4 *= qt[row + 4];
                p5 *= qt[row + 5]; p6 *= qt[row + 6]; p7 *= qt[row + 7];
                v0 = (5793 * p0 + 128) >> 8;
                v1 = (5793 * p4 + 128) >> 8;
                v2 = p2; v3 = p6;
                v4 = (2896 * (p1 - p7) + 128) >> 8;
                v7 = (2896 * (p1 + p7) + 128) >> 8;
                v5 = p3 << 4; v6 = p5 << 4;
                v0 = (v0 + v1 + 1) >> 1; v1 = v0 - v1;
                t = (v2 * 3784 + v3 * 1567 + 128) >> 8;
                v2 = (v2 * 1567 - v3 * 3784 + 128) >> 8; v3 = t;
                v4 = (v4 + v6 + 1) >> 1; v6 = v4 - v6;
                v7 = (v7 + v5 + 1) >> 1; v5 = v7 - v5;
                v0 = (v0 + v3 + 1) >> 1; v3 = v0 - v3;
                v1 = (v1 + v2 + 1) >> 1; v2 = v1 - v2;
                t = (v4 * 2276 + v7 * 3406 + 2048) >> 12;
                v4 = (v4 * 3406 - v7 * 2276 + 2048) >> 12; v7 = t;
                t = (v5 * 799 + v6 * 4017 + 2048) >> 12;
                v5 = (v5 * 4017 - v6 * 799 + 2048) >> 12; v6 = t;
                p[row] = v0 + v7; p[row + 7] = v0 - v7;
                p[row + 1] = v1 + v6; p[row + 6] = v1 - v6;
                p[row + 2] = v2 + v5; p[row + 5] = v2 - v5;
                p[row + 3] = v3 + v4; p[row + 4] = v3 - v4;
            }
            for (int col = 0; col < 8; col++)
            {
                p0 = p[col]; p1 = p[col + 8]; p2 = p[col + 16];
                p3 = p[col + 24]; p4 = p[col + 32]; p5 = p[col + 40];
                p6 = p[col + 48]; p7 = p[col + 56];
                if ((p1 | p2 | p3 | p4 | p5 | p6 | p7) == 0)
                {
                    t = (5793 * p0 + 8192) >> 14;
                    t = t < -2040 ? 0 : (t >= 2024 ? 255 : (t + 2056) >> 4);
                    for (int i = 0; i < 8; i++)
                    {
                        c.coeff[off + col + i * 8] = (short) t;
                    }
                    continue;
                }
                v0 = (5793 * p0 + 2048) >> 12;
                v1 = (5793 * p4 + 2048) >> 12;
                v2 = p2; v3 = p6;
                v4 = (2896 * (p1 - p7) + 2048) >> 12;
                v7 = (2896 * (p1 + p7) + 2048) >> 12;
                v5 = p3; v6 = p5;
                v0 = ((v0 + v1 + 1) >> 1) + 4112; v1 = v0 - v1;
                t = (v2 * 3784 + v3 * 1567 + 2048) >> 12;
                v2 = (v2 * 1567 - v3 * 3784 + 2048) >> 12; v3 = t;
                v4 = (v4 + v6 + 1) >> 1; v6 = v4 - v6;
                v7 = (v7 + v5 + 1) >> 1; v5 = v7 - v5;
                v0 = (v0 + v3 + 1) >> 1; v3 = v0 - v3;
                v1 = (v1 + v2 + 1) >> 1; v2 = v1 - v2;
                t = (v4 * 2276 + v7 * 3406 + 2048) >> 12;
                v4 = (v4 * 3406 - v7 * 2276 + 2048) >> 12; v7 = t;
                t = (v5 * 799 + v6 * 4017 + 2048) >> 12;
                v5 = (v5 * 4017 - v6 * 799 + 2048) >> 12; v6 = t;
                p0 = clampFixed(v0 + v7); p7 = clampFixed(v0 - v7);
                p1 = clampFixed(v1 + v6); p6 = clampFixed(v1 - v6);
                p2 = clampFixed(v2 + v5); p5 = clampFixed(v2 - v5);
                p3 = clampFixed(v3 + v4); p4 = clampFixed(v3 - v4);
                c.coeff[off + col] = (short) p0;
                c.coeff[off + col + 8] = (short) p1;
                c.coeff[off + col + 16] = (short) p2;
                c.coeff[off + col + 24] = (short) p3;
                c.coeff[off + col + 32] = (short) p4;
                c.coeff[off + col + 40] = (short) p5;
                c.coeff[off + col + 48] = (short) p6;
                c.coeff[off + col + 56] = (short) p7;
            }
        }

        private static int clampFixed(int value)
        {
            return value < 16 ? 0 : (value >= 4080 ? 255 : value >> 4);
        }

    }

    private static final class Frame
    {
        int width, height, maxH, maxV, mcusPerLine, mcusPerColumn;
        boolean progressive;
        Component[] components;

        Component component(int id)
        {
            for (int i = 0; i < components.length; i++)
            {
                if (components[i].id == id) { return components[i]; }
            }
            return null;
        }
    }

    private static final class Component
    {
        int id, h, v, quantId, blocksPerLine, blocksPerColumn, strideBlocks;
        int pred;
        int[] quant;
        short[] coeff;
        Huffman dc, ac;

        int blockOffset(int row, int col)
        {
            return 64 * (row * strideBlocks + col);
        }

        int sample(int x, int y, int maxH, int maxV)
        {
            int sx = x * h / maxH;
            int sy = y * v / maxV;
            return coeff[blockOffset(sy >> 3, sx >> 3)
                    + ((sy & 7) << 3) + (sx & 7)] & 0xffff;
        }
    }

    private static final class Huffman
    {
        final int[] min = new int[17];
        final int[] max = new int[17];
        final int[] valueAt = new int[17];
        final byte[] values;

        Huffman(int[] lengths, byte[] values) throws IOException
        {
            this.values = values;
            int code = 0;
            int at = 0;
            for (int bits = 1; bits <= 16; bits++)
            {
                int count = lengths[bits - 1];
                if (count == 0)
                {
                    min[bits] = -1;
                    max[bits] = -1;
                }
                else
                {
                    min[bits] = code;
                    valueAt[bits] = at;
                    code += count - 1;
                    max[bits] = code;
                    at += count;
                    code++;
                }
                code <<= 1;
            }
            if (at != values.length || code > (1 << 17))
            {
                throw new IOException("oversubscribed Huffman table");
            }
        }

        int decode(ScanBits bits) throws IOException
        {
            int code = 0;
            for (int length = 1; length <= 16; length++)
            {
                code = (code << 1) | bits.bit();
                if (min[length] >= 0 && code <= max[length])
                {
                    int index = valueAt[length] + code - min[length];
                    if (index < 0 || index >= values.length)
                    {
                        throw new IOException("invalid Huffman index");
                    }
                    return values[index] & 0xff;
                }
            }
            throw new IOException("invalid Huffman sequence");
        }
    }

    private static final class ScanBits
    {
        final byte[] data;
        int pos;
        int value;
        int remaining;

        ScanBits(byte[] data, int pos)
        {
            this.data = data;
            this.pos = pos;
        }

        int bit() throws IOException
        {
            if (remaining == 0)
            {
                if (pos >= data.length) { throw new IOException("truncated entropy data"); }
                value = data[pos++] & 0xff;
                if (value == 0xff)
                {
                    if (pos >= data.length) { throw new IOException("truncated JPEG marker"); }
                    int next = data[pos++] & 0xff;
                    if (next != 0)
                    {
                        pos -= 2;
                        throw new IOException("unexpected marker in entropy data");
                    }
                }
                remaining = 8;
            }
            remaining--;
            return (value >> remaining) & 1;
        }

        int receive(int count) throws IOException
        {
            int out = 0;
            while (count-- > 0) { out = (out << 1) | bit(); }
            return out;
        }

        int receiveExtend(int count) throws IOException
        {
            if (count == 0) { return 0; }
            int value = receive(count);
            int threshold = 1 << (count - 1);
            return value >= threshold ? value : value + (-1 << count) + 1;
        }

        void align() { remaining = 0; }

        int readMarker() throws IOException
        {
            if (pos + 1 >= data.length || (data[pos] & 0xff) != 0xff)
            {
                throw new IOException("JPEG marker expected");
            }
            while (pos < data.length && (data[pos] & 0xff) == 0xff) { pos++; }
            if (pos >= data.length) { throw new IOException("truncated JPEG marker"); }
            return 0xff00 | (data[pos++] & 0xff);
        }

        int position() { return pos; }
    }

    private static short checkedShort(int value) throws IOException
    {
        if (value < -32768 || value > 32767)
        {
            throw new IOException("JPEG coefficient overflow");
        }
        return (short) value;
    }

    private static int checkedPixels(int width, int height) throws IOException
    {
        long pixels = (long) width * (long) height;
        int limit = MemoryBudget.photoPixels();
        if (width < 1 || height < 1 || pixels > limit)
        {
            throw new IOException("JPEG dimensions " + width + "x" + height
                                  + " exceed the " + limit + " pixel limit");
        }
        return (int) pixels;
    }

    private static int ceilDiv(int value, int divisor)
    {
        return (value + divisor - 1) / divisor;
    }

    private static int clamp(int value)
    {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    private static void fail(String message) throws IOException
    {
        throw new IOException(message);
    }
}
