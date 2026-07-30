package tgtest;

import java.util.Random;

import tg.tl.Tl;
import tg.tl.TlException;
import tg.tl.TlReader;
import tg.tl.TlWriter;
import tg.tl.Utf8;

/**
 * TL serialization, including the parts that are easy to get subtly wrong.
 *
 * Three areas get disproportionate attention:
 *
 * 1. **The 253/254 length boundary.** TL switches from a one-byte to a
 *    four-byte length header at 254, and the padding to a 4-byte multiple is
 *    computed over header+payload. Off-by-one here corrupts every large field.
 *
 * 2. **Hostile input.** Every length in a TL stream is attacker-controlled. On
 *    a 5 MiB heap, a field claiming 2 GB must throw rather than attempt the
 *    allocation, so the malformed cases are tested as first-class behaviour
 *    rather than as an afterthought.
 *
 * 3. **UTF-8.** CLDC's String.getBytes() uses the handset's default encoding,
 *    which on a 2011 Russian-market phone may well not be UTF-8. Round-tripping
 *    Cyrillic and emoji is what proves we are not depending on it.
 */
public final class TlTest implements Test
{
    public String name()
    {
        return "tl/serialization";
    }

    public void run() throws Exception
    {
        primitives();
        bytesBoundary();
        strings();
        vectors();
        malformedInput();
    }

    private void primitives() throws Exception
    {
        TlWriter w = new TlWriter();
        w.writeInt(0);
        w.writeInt(-1);
        w.writeInt(Integer.MIN_VALUE);
        w.writeInt(Integer.MAX_VALUE);
        w.writeInt(0x1cb5c415);
        w.writeLong(0L);
        w.writeLong(-1L);
        w.writeLong(Long.MIN_VALUE);
        w.writeLong(Long.MAX_VALUE);
        w.writeBool(true);
        w.writeBool(false);

        // TL is little-endian; a big-endian slip would be invisible on
        // symmetric values like -1, so check the actual bytes of a known one.
        TlWriter le = new TlWriter();
        le.writeInt(0x01020304);
        Assert.bytesEqual("int32 is little-endian", "04030201", le.toByteArray());

        TlWriter le64 = new TlWriter();
        le64.writeLong(0x0102030405060708L);
        Assert.bytesEqual("int64 is little-endian", "0807060504030201", le64.toByteArray());

        TlReader r = new TlReader(w.toByteArray());
        Assert.equal("int 0", 0, r.readInt());
        Assert.equal("int -1", -1, r.readInt());
        Assert.equal("int MIN", Integer.MIN_VALUE, r.readInt());
        Assert.equal("int MAX", Integer.MAX_VALUE, r.readInt());
        Assert.equal("vector id", Tl.VECTOR, r.readInt());
        Assert.equal("long 0", 0L, r.readLong());
        Assert.equal("long -1", -1L, r.readLong());
        Assert.equal("long MIN", Long.MIN_VALUE, r.readLong());
        Assert.equal("long MAX", Long.MAX_VALUE, r.readLong());
        Assert.isTrue("bool true", r.readBool());
        Assert.isFalse("bool false", r.readBool());
        Assert.equal("fully consumed", 0, r.remaining());

        // int128 / int256 are raw, not length-prefixed.
        byte[] nonce = Assert.unhex("000102030405060708090a0b0c0d0e0f");
        TlWriter raw = new TlWriter();
        raw.writeRaw(nonce);
        Assert.bytesEqual("int128 is written raw", nonce, raw.toByteArray());
        Assert.bytesEqual("int128 round trip", nonce,
                new TlReader(raw.toByteArray()).readRaw(16));
    }

    /**
     * Exhaustive across the header transition. Lengths 250-260 cover every
     * combination of one/four byte header and every padding remainder.
     */
    private void bytesBoundary() throws Exception
    {
        Random rnd = new Random(17);
        for (int len = 0; len <= 600; len++)
        {
            byte[] payload = new byte[len];
            rnd.nextBytes(payload);

            TlWriter w = new TlWriter();
            w.writeBytes(payload);
            byte[] encoded = w.toByteArray();

            Assert.equal("length " + len + " encodes to a multiple of 4",
                    0, encoded.length % 4);

            int expectedHeader = (len < 254) ? 1 : 4;
            int expectedTotal = ((expectedHeader + len) + 3) / 4 * 4;
            Assert.equal("length " + len + " total encoded size",
                    expectedTotal, encoded.length);

            if (len < 254)
            {
                Assert.equal("length " + len + " uses a 1-byte header",
                        len, encoded[0] & 0xff);
            }
            else
            {
                Assert.equal("length " + len + " uses the 0xFE marker",
                        254, encoded[0] & 0xff);
                int declared = (encoded[1] & 0xff)
                             | ((encoded[2] & 0xff) << 8)
                             | ((encoded[3] & 0xff) << 16);
                Assert.equal("length " + len + " 24-bit length field", len, declared);
            }

            TlReader r = new TlReader(encoded);
            Assert.bytesEqual("length " + len + " round trip", payload, r.readBytes());
            Assert.equal("length " + len + " consumed exactly", 0, r.remaining());
        }

        // Two fields back to back: if padding were mishandled the second read
        // would start at the wrong offset, which is the realistic failure mode.
        for (int a = 250; a <= 258; a++)
        {
            for (int b = 0; b <= 6; b++)
            {
                byte[] first = Assert.repeat((byte) 0xAA, a);
                byte[] second = Assert.repeat((byte) 0xBB, b);
                TlWriter w = new TlWriter();
                w.writeBytes(first);
                w.writeBytes(second);
                TlReader r = new TlReader(w.toByteArray());
                Assert.bytesEqual("consecutive " + a + "/" + b + " first", first, r.readBytes());
                Assert.bytesEqual("consecutive " + a + "/" + b + " second", second, r.readBytes());
                Assert.equal("consecutive " + a + "/" + b + " consumed", 0, r.remaining());
            }
        }
    }

    private void strings() throws Exception
    {
        String[] samples = {
            "",
            "hello",
            "Привет, мир",                       // Cyrillic: 2 bytes per char
            "日本語テキスト",                      // CJK: 3 bytes per char
            "emoji 😀 👍",   // surrogate pairs: 4 bytes each
            "mixed ASCII / Кириллица / 🌐",
            "a" + (char) 0 + "b"                            // embedded NUL must survive
        };

        for (int i = 0; i < samples.length; i++)
        {
            TlWriter w = new TlWriter();
            w.writeString(samples[i]);
            String back = new TlReader(w.toByteArray()).readString();
            Assert.equal("string round trip [" + i + "]", samples[i], back);
        }

        // Must be UTF-8 specifically, not the platform encoding.
        Assert.bytesEqual("Cyrillic encodes as UTF-8",
                "d09fd180d0b8d0b2d0b5d182", Utf8.encode("Привет"));
        Assert.bytesEqual("emoji encodes as 4-byte UTF-8",
                "f09f9880", Utf8.encode("😀"));
        Assert.equal("UTF-8 decodes back",
                "Привет", Utf8.decode(Assert.unhex("d09fd180d0b8d0b2d0b5d182")));

        // A truncated sequence must degrade, not throw - one bad message must
        // not take down the dialog list.
        String damaged = Utf8.decode(Assert.unhex("d09f d180 d0"));
        Assert.isTrue("truncated UTF-8 degrades gracefully", damaged.length() >= 2);
    }

    private void vectors() throws Exception
    {
        long[] longs = { 0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 42L };
        TlWriter w = new TlWriter();
        w.writeLongVector(longs);

        TlReader r = new TlReader(w.toByteArray());
        long[] back = r.readLongVector();
        Assert.equal("long vector size", longs.length, back.length);
        for (int i = 0; i < longs.length; i++)
        {
            Assert.equal("long vector [" + i + "]", longs[i], back[i]);
        }

        TlWriter empty = new TlWriter();
        empty.writeVectorHeader(0);
        Assert.equal("empty vector", 0, new TlReader(empty.toByteArray()).readVectorCount());
    }

    /**
     * The parser must reject network-controlled nonsense without allocating.
     * Each case here is something a hostile or broken peer can actually send.
     */
    private void malformedInput()
    {
        // A bytes field claiming ~16 MB with 4 bytes actually present.
        expectTlException("oversized bytes length", new Runnable()
        {
            public void run()
            {
                try
                {
                    new TlReader(Assert.unhex("feffffff")).readBytes();
                    Assert.fail("accepted a 16 MB length in a 4-byte buffer");
                }
                catch (TlException expected) { throw new Marker(); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });

        // A vector claiming 2 billion elements.
        expectTlException("oversized vector count", new Runnable()
        {
            public void run()
            {
                try
                {
                    new TlReader(Assert.unhex("15c4b51c ffffff7f")).readVectorCount();
                    Assert.fail("accepted a 2-billion element vector");
                }
                catch (TlException expected) { throw new Marker(); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });

        // Negative vector count.
        expectTlException("negative vector count", new Runnable()
        {
            public void run()
            {
                try
                {
                    new TlReader(Assert.unhex("15c4b51c ffffffff")).readVectorCount();
                    Assert.fail("accepted a negative vector count");
                }
                catch (TlException expected) { throw new Marker(); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });

        // Truncated int.
        expectTlException("truncated int", new Runnable()
        {
            public void run()
            {
                try
                {
                    new TlReader(Assert.unhex("0102")).readInt();
                    Assert.fail("accepted a truncated int32");
                }
                catch (TlException expected) { throw new Marker(); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });

        // Wrong constructor where a vector was expected.
        expectTlException("wrong constructor", new Runnable()
        {
            public void run()
            {
                try
                {
                    new TlReader(Assert.unhex("deadbeef 00000000")).readVectorCount();
                    Assert.fail("accepted a non-vector constructor");
                }
                catch (TlException expected) { throw new Marker(); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });

        // A Bool field carrying something that is not a Bool constructor.
        expectTlException("invalid bool", new Runnable()
        {
            public void run()
            {
                try
                {
                    new TlReader(Assert.unhex("deadbeef")).readBool();
                    Assert.fail("accepted an invalid Bool constructor");
                }
                catch (TlException expected) { throw new Marker(); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });

        // Truncated padding: the declared length fits but the padding does not.
        expectTlException("truncated padding", new Runnable()
        {
            public void run()
            {
                try
                {
                    new TlReader(Assert.unhex("0261")).readBytes();
                    Assert.fail("accepted a field with truncated padding");
                }
                catch (TlException expected) { throw new Marker(); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });
    }

    /** Signals that the expected TlException was thrown. */
    private static final class Marker extends RuntimeException { }

    private static void expectTlException(String what, Runnable body)
    {
        try
        {
            body.run();
            Assert.fail(what + ": expected a TlException, none was thrown");
        }
        catch (Marker ok)
        {
            // correct
        }
    }
}
