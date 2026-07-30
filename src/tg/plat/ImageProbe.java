package tg.plat;

import javax.microedition.lcdui.Image;

import tg.io.Hex;

/** Verifies the optional JPEG decoder with a mandatory PNG control image. */
public final class ImageProbe
{
    private static final String JPEG =
        "ffd8ffe000104a46494600010101006000600000ffdb0043000302020302020303030304030304050805050404050a070706080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514"
      + "ffdb00430103040405040509050509140d0b0d1414141414141414141414141414141414141414141414141414141414141414141414141414141414141414141414"
      + "ffc00011080008000803012200021101031101"
      + "ffc4001f0000010501010101010100000000000000000102030405060708090a0b"
      + "ffc400b5100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9fa"
      + "ffc4001f0100030101010101010101010000000000000102030405060708090a0b"
      + "ffc400b51100020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9fa"
      + "ffda000c03010002110311003f00e0a8a28afe8d3f023fffd9";

    private static final String PNG =
        "89504e470d0a1a0a0000000d4948445200000008000000080806000000c40fbe8b"
      + "000000017352474200aece1ce90000000467414d410000b18f0bfc610500000009"
      + "7048597300000ec300000ec301c76fa8640000001649444154285363d0a838f11f"
      + "1f66401740c7c3430100f1fa99c1664444730000000049454e44ae426082";

    private ImageProbe() { }

    public static String[] run()
    {
        String[] out = new String[6];
        out[0] = "Image.createImage(byte[])";
        out[1] = decode("PNG control", PNG);
        out[2] = decode("JPEG optional", JPEG);
        out[3] = "";
        out[4] = "PNG must pass on MIDP 2.0.";
        out[5] = "JPEG PASS enables Telegram photos.";
        return out;
    }

    private static String decode(String label, String hex)
    {
        try
        {
            byte[] raw = Hex.decode(hex);
            long t0 = System.currentTimeMillis();
            Image image = Image.createImage(raw, 0, raw.length);
            return label + ": PASS " + image.getWidth() + "x" + image.getHeight()
                    + " " + (System.currentTimeMillis() - t0) + "ms";
        }
        catch (Throwable t)
        {
            return label + ": FAIL " + t.getClass().getName() + " "
                    + String.valueOf(t.getMessage());
        }
    }
}
