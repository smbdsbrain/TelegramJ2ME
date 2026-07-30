package tgtest;

import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

import tg.io.Inflate;

/**
 * DEFLATE and gzip decompression against java.util.zip.
 *
 * Telegram wraps large RPC results in gzip_packed - the dialog list and message
 * history always arrive compressed - and CLDC has no java.util.zip whatsoever,
 * so this had to be written from scratch. The JDK is the perfect oracle here:
 * anything it compresses, we must decompress byte for byte.
 *
 * The inputs are chosen to exercise all three DEFLATE block types. Highly
 * repetitive data produces long back-references (including overlapping ones,
 * where a naive arraycopy would be wrong); incompressible random data forces
 * stored blocks; ordinary text produces dynamic Huffman.
 */
public final class InflateTest implements Test
{
    public String name()
    {
        return "io/inflate-vs-jdk";
    }

    public void run() throws Exception
    {
        blockTypes();
        sizes();
        overlappingBackReferences();
        realisticTlPayload();
        refusesToExceedTheLimit();
        rejectsGarbage();
    }

    private void blockTypes() throws Exception
    {
        // Dynamic Huffman: ordinary text.
        byte[] text = Assert.ascii(
                "Telegram messages are mostly text, and mostly repetitive text at that. "
                + "Telegram messages are mostly text, and mostly repetitive text at that.");
        roundTrip("text", text);

        // Long runs: heavy back-references.
        roundTrip("run of zeros", new byte[50000]);
        roundTrip("run of 0xAA", Assert.repeat((byte) 0xAA, 40000));

        // Incompressible: forces stored blocks.
        byte[] noise = new byte[30000];
        new Random(1).nextBytes(noise);
        roundTrip("random noise", noise);

        roundTrip("empty", new byte[0]);
        roundTrip("single byte", new byte[] { 42 });
    }

    /** Sizes around the internal buffer growth points. */
    private void sizes() throws Exception
    {
        Random r = new Random(99);
        int[] sizes = { 1, 2, 63, 64, 65, 255, 256, 1023, 1024, 1025, 4096, 65535, 65536 };
        for (int i = 0; i < sizes.length; i++)
        {
            byte[] data = new byte[sizes[i]];
            // Semi-compressible: a mix of structure and entropy.
            for (int j = 0; j < data.length; j++)
            {
                data[j] = (byte) ((j % 17 == 0) ? r.nextInt(256) : (j & 0x3f));
            }
            roundTrip("size " + sizes[i], data);
        }
    }

    /**
     * A back-reference whose distance is shorter than its length reads bytes
     * that the same copy is still producing. It is legal, common, and the one
     * case where System.arraycopy gives the wrong answer.
     */
    private void overlappingBackReferences() throws Exception
    {
        byte[] data = new byte[10000];
        for (int i = 0; i < data.length; i++)
        {
            data[i] = (byte) 'x';                       // distance 1, length ~10000
        }
        roundTrip("overlap distance 1", data);

        byte[] pattern = new byte[9999];
        for (int i = 0; i < pattern.length; i++)
        {
            pattern[i] = (byte) ("abc".charAt(i % 3));   // distance 3
        }
        roundTrip("overlap distance 3", pattern);
    }

    /** Something shaped like an actual gzip_packed TL response. */
    private void realisticTlPayload() throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Random r = new Random(7);
        for (int i = 0; i < 400; i++)
        {
            // Repeated constructor ids and peer ids, varying text - which is
            // exactly why Telegram bothers to compress dialog lists.
            out.write(new byte[] { 0x1c, (byte) 0xb5, (byte) 0xc4, 0x15 });
            long peer = 100000000L + r.nextInt(50);
            for (int b = 0; b < 8; b++) { out.write((int) (peer >>> (b * 8)) & 0xff); }
            byte[] text = Assert.ascii("message number " + i + " in a dialog list");
            out.write(text.length);
            out.write(text);
        }
        roundTrip("tl-like payload", out.toByteArray());
    }

    private void refusesToExceedTheLimit() throws Exception
    {
        // 1 MB of zeros compresses to about a kilobyte: the classic shape of a
        // decompression bomb. With a small limit it must refuse rather than
        // allocate.
        byte[] bomb = gzip(new byte[1024 * 1024]);
        try
        {
            Inflate.gunzip(bomb, 0, bomb.length, 64 * 1024);
            Assert.fail("inflated past the declared output limit");
        }
        catch (java.io.IOException expected)
        {
            Assert.isTrue("limit message mentions the cap",
                    expected.getMessage().indexOf("limit") >= 0);
        }
    }

    private void rejectsGarbage()
    {
        try
        {
            Inflate.gunzip(Assert.unhex("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"));
            Assert.fail("accepted data with no gzip magic");
        }
        catch (java.io.IOException expected) { }

        try
        {
            Inflate.gunzip(Assert.unhex("1f8b"));
            Assert.fail("accepted a truncated gzip stream");
        }
        catch (java.io.IOException expected) { }
    }

    // ----------------------------------------------------------- helpers

    private void roundTrip(String what, byte[] data) throws Exception
    {
        byte[] gz = gzip(data);
        Assert.bytesEqual("gunzip " + what + " (" + data.length + " bytes)",
                data, Inflate.gunzip(gz, 0, gz.length, 4 * 1024 * 1024));

        byte[] raw = deflateRaw(data);
        Assert.bytesEqual("inflateRaw " + what,
                data, Inflate.inflateRaw(raw, 0, raw.length, 4 * 1024 * 1024, 64));
    }

    private static byte[] gzip(byte[] data) throws Exception
    {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(bos);
        gz.write(data);
        gz.close();
        return bos.toByteArray();
    }

    private static byte[] deflateRaw(byte[] data)
    {
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished())
        {
            int n = d.deflate(buf);
            if (n == 0) { break; }
            bos.write(buf, 0, n);
        }
        d.end();
        return bos.toByteArray();
    }
}
