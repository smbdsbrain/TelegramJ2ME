package tg.ui;

import java.io.IOException;

import tg.mem.MemoryBudget;

/**
 * Reconstructs Telegram's photoStrippedSize payload into a regular JPEG.
 *
 * The payload contains one-byte height/width followed by entropy data. The
 * fixed tables are Telegram's interoperable stripped-thumbnail template.
 */
public final class StrippedJpeg
{
    private static final String HEADER =
        "ffd8ffe000104a46494600010100000100010000ffdb004300281c1e231e19282321232d2b28303c64413c37373c7b585d49"
      + "64918099968f808c8aa0b4e6c3a0aadaad8a8cc8ffcbdaeef5ffffff9bc1fffffffaffe6fdfff8ffdb0043012b2d2d3c353c"
      + "76414176f8a58ca5f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8"
      + "f8f8f8f8f8f8f8f8f8ffc0001108001e002803012200021101031101ffc4001f00000105010101010101000000000000000001"
      + "02030405060708090a0bffc400b5100002010303020403050504040000017d01020300041105122131410613516107227114"
      + "328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a53545556"
      + "5758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5"
      + "b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffc4001f010003"
      + "0101010101010101010000000000000102030405060708090a0bffc400b51100020102040403040705040400010277000102"
      + "031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a35"
      + "363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495"
      + "969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9ea"
      + "f2f3f4f5f6f7f8f9faffda000c03010002110311003f00";

    private static byte[] header;

    private StrippedJpeg() { }

    public static byte[] restore(byte[] stripped) throws IOException
    {
        if (stripped == null || stripped.length < 4 || stripped[0] != 1)
        {
            throw new IOException("invalid stripped JPEG");
        }
        int height = stripped[1] & 0xff;
        int width = stripped[2] & 0xff;
        if (width < 1 || height < 1
                || (long) width * (long) height > MemoryBudget.photoPixels())
        {
            throw new IOException("invalid stripped JPEG dimensions");
        }
        byte[] prefix = header();
        byte[] jpeg = new byte[prefix.length + stripped.length - 3 + 2];
        System.arraycopy(prefix, 0, jpeg, 0, prefix.length);
        System.arraycopy(stripped, 3, jpeg, prefix.length, stripped.length - 3);
        jpeg[164] = (byte) height;
        jpeg[166] = (byte) width;
        jpeg[jpeg.length - 2] = (byte) 0xff;
        jpeg[jpeg.length - 1] = (byte) 0xd9;
        return jpeg;
    }

    /**
     * Live bytes at the peak of decoding this payload, before restoring it.
     *
     * A conversation screen queues up to a dozen of these at once, so the
     * question "does the next one fit" has to be answerable without doing any of
     * the work. It is: the payload states its own size in bytes 1 and 2, and the
     * restored JPEG is the fixed template plus the entropy data, both known here.
     *
     * @return 0 for a payload this class would refuse to restore anyway. The
     *         caller admits it and lets {@link #restore} report the real fault
     *         rather than turning a malformed thumbnail into a memory refusal.
     */
    public static long decodeCost(byte[] stripped)
    {
        if (stripped == null || stripped.length < 4 || stripped[0] != 1)
        {
            return 0;
        }
        int height = stripped[1] & 0xff;
        int width = stripped[2] & 0xff;
        if (width < 1 || height < 1) { return 0; }
        // What restore() will build: the template, the entropy data, and the two
        // bytes of EOI it appends. HEADER.length() / 2 rather than header(),
        // which would allocate the template to measure it.
        int restored = HEADER.length() / 2 + stripped.length - 3 + 2;
        return MemoryBudget.photoDecodeCost(width, height, restored);
    }

    private static synchronized byte[] header()
    {
        if (header != null) { return header; }
        byte[] decoded = new byte[HEADER.length() / 2];
        for (int i = 0; i < decoded.length; i++)
        {
            decoded[i] = (byte) ((hex(HEADER.charAt(i * 2)) << 4)
                    | hex(HEADER.charAt(i * 2 + 1)));
        }
        header = decoded;
        return decoded;
    }

    private static int hex(char c)
    {
        return c <= '9' ? c - '0' : c - 'a' + 10;
    }
}
