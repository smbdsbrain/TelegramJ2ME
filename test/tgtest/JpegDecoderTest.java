package tgtest;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import tg.mem.MemoryBudget;
import tg.ui.JpegDecoder;
import tg.ui.StrippedJpeg;

/** Differential and hostile-input checks for the device JPEG decoder. */
public final class JpegDecoderTest implements Test
{
    public String name() { return "phase4/jpeg-baseline-progressive"; }

    public void run() throws Exception
    {
        differential("gray", BufferedImage.TYPE_BYTE_GRAY, 1, 1, false);
        differential("444", BufferedImage.TYPE_INT_RGB, 1, 1, false);
        differential("422", BufferedImage.TYPE_INT_RGB, 2, 1, false);
        differential("420", BufferedImage.TYPE_INT_RGB, 2, 2, false);
        differential("progressive", BufferedImage.TYPE_INT_RGB, 2, 2, true);
        restartMarkers();
        strippedJpeg();
        hostileDimensions();
        budgetBoundsTheDecode();
        truncation();
        unsupportedArithmetic();
        unsupportedLosslessAndCmyk();
    }

    private static void differential(String name, int type, int h, int v,
                                     boolean progressive) throws Exception
    {
        BufferedImage source = pattern(type);
        byte[] jpeg = encode(source, h, v, progressive);
        compare(name, jpeg);
    }

    private static void compare(String name, byte[] jpeg) throws Exception
    {
        BufferedImage reference = ImageIO.read(new ByteArrayInputStream(jpeg));
        JpegDecoder.Decoded actual = JpegDecoder.decodePixels(
                new ByteArrayInputStream(jpeg), null);
        Assert.equal(name + " width", reference.getWidth(), actual.width);
        Assert.equal(name + " height", reference.getHeight(), actual.height);
        long totalError = 0;
        int maxError = 0;
        for (int y = 0; y < actual.height; y++)
        {
            for (int x = 0; x < actual.width; x++)
            {
                int expected;
                if ("gray".equals(name))
                {
                    int sample = reference.getRaster().getSample(x, y, 0);
                    expected = 0xff000000 | (sample << 16)
                            | (sample << 8) | sample;
                }
                else
                {
                    expected = reference.getRGB(x, y);
                }
                int got = actual.rgb[y * actual.width + x];
                for (int shift = 0; shift <= 16; shift += 8)
                {
                    int error = Math.abs(((expected >> shift) & 255)
                            - ((got >> shift) & 255));
                    totalError += error;
                    maxError = Math.max(maxError, error);
                }
            }
        }
        long samples = actual.width * actual.height * 3L;
        int meanLimit = ("gray".equals(name) || "444".equals(name)) ? 2 : 10;
        int maxLimit = ("gray".equals(name) || "444".equals(name)) ? 8 : 96;
        Assert.isTrue(name + " mean channel error <= " + meanLimit + " (mean="
                        + ((double) totalError / samples) + ")",
                totalError <= samples * meanLimit);
        Assert.isTrue(name + " max channel error <= " + maxLimit + " (max="
                        + maxError + ")", maxError <= maxLimit);
    }

    private static BufferedImage pattern(int type)
    {
        BufferedImage image = new BufferedImage(31, 23, type);
        for (int y = 0; y < image.getHeight(); y++)
        {
            for (int x = 0; x < image.getWidth(); x++)
            {
                int r = (x * 7 + y * 3) & 255;
                int g = (x * 2 + y * 11) & 255;
                int b = (x * 13 + y * 5) & 255;
                image.setRGB(x, y, 0xff000000 | (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, int h, int v,
                                 boolean progressive) throws Exception
    {
        return encode(image, h, v, progressive, 0);
    }

    private static byte[] encode(BufferedImage image, int h, int v,
                                 boolean progressive, int restart)
            throws Exception
    {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageOutputStream output = ImageIO.createImageOutputStream(bytes);
        writer.setOutput(output);
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.88f);
        param.setProgressiveMode(progressive
                ? ImageWriteParam.MODE_DEFAULT : ImageWriteParam.MODE_DISABLED);
        ImageTypeSpecifier type = ImageTypeSpecifier.createFromRenderedImage(image);
        IIOMetadata metadata = writer.getDefaultImageMetadata(type, param);
        if (image.getColorModel().getNumColorComponents() == 3)
        {
            String format = metadata.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
            NodeList specs = root.getElementsByTagName("componentSpec");
            for (int i = 0; i < specs.getLength(); i++)
            {
                IIOMetadataNode spec = (IIOMetadataNode) specs.item(i);
                spec.setAttribute("HsamplingFactor", i == 0
                        ? Integer.toString(h) : "1");
                spec.setAttribute("VsamplingFactor", i == 0
                        ? Integer.toString(v) : "1");
            }
            if (restart > 0)
            {
                NodeList sequences = root.getElementsByTagName("markerSequence");
                IIOMetadataNode dri = new IIOMetadataNode("dri");
                dri.setAttribute("interval", Integer.toString(restart));
                sequences.item(0).appendChild(dri);
            }
            metadata.setFromTree(format, root);
        }
        writer.write(null, new IIOImage(image, null, metadata), param);
        output.close();
        writer.dispose();
        return bytes.toByteArray();
    }

    private static void restartMarkers() throws Exception
    {
        byte[] jpeg = encode(pattern(BufferedImage.TYPE_INT_RGB),
                2, 2, false, 2);
        Assert.isTrue("DRI emitted", markerOrMinusOne(jpeg, 0xdd) >= 0);
        Assert.isTrue("restart emitted",
                markerOrMinusOne(jpeg, 0xd0) >= 0
                || markerOrMinusOne(jpeg, 0xd1) >= 0);
        compare("restart", jpeg);
    }

    /**
     * The pixel cap used to be a literal in three separate classes. It is now
     * one measured number, and the point of measuring it is that a smaller heap
     * actually refuses an image a larger heap accepts - before the framebuffer
     * is allocated, not after the OutOfMemoryError.
     */
    private static void budgetBoundsTheDecode() throws Exception
    {
        // 256x256 = 65 536 pixels: inside the reference budget of 307 200 and
        // outside the 16 384 floor a tiny heap gets. The entropy data no longer
        // matches these dimensions, so at the reference budget this fails for
        // some other reason - what matters is which reason.
        byte[] jpeg = encode(pattern(BufferedImage.TYPE_INT_RGB), 2, 2, false);
        int sof = marker(jpeg, 0xc0);
        jpeg[sof + 5] = (byte) 0x01;      // height 256
        jpeg[sof + 6] = (byte) 0x00;
        jpeg[sof + 7] = (byte) 0x01;      // width 256
        jpeg[sof + 8] = (byte) 0x00;

        Assert.isTrue("the reference budget admits 256x256",
                MemoryBudget.photoPixels() >= 65536);
        try
        {
            JpegDecoder.decodePixels(new ByteArrayInputStream(jpeg), null);
        }
        catch (IOException other)
        {
            Assert.isTrue("the reference budget does not refuse 256x256 on size",
                    other.getMessage().indexOf("pixel limit") < 0);
        }

        MemoryBudget.init(64 * 1024, 0, MemoryBudget.SOURCE_MEASURED);
        try
        {
            Assert.isTrue("a 64 KB heap refuses 256x256",
                    MemoryBudget.photoPixels() < 65536);
            expectFailure("a measured floor budget refuses the decode",
                    jpeg, "pixel limit");
        }
        finally { MemoryBudget.reset(); }
    }

    private static void hostileDimensions() throws Exception
    {
        byte[] jpeg = encode(pattern(BufferedImage.TYPE_INT_RGB), 2, 2, false);
        int sof = marker(jpeg, 0xc0);
        jpeg[sof + 5] = (byte) 0xff;
        jpeg[sof + 6] = (byte) 0xff;
        expectFailure("hostile dimensions", jpeg, "dimensions");
    }

    private static void strippedJpeg() throws Exception
    {
        byte[] jpeg = encode(pattern(BufferedImage.TYPE_INT_RGB), 2, 2, false);
        int sos = marker(jpeg, 0xda);
        int length = ((jpeg[sos + 2] & 255) << 8) | (jpeg[sos + 3] & 255);
        int entropy = sos + 2 + length;
        int entropyLength = jpeg.length - entropy - 2;
        byte[] stripped = new byte[3 + entropyLength];
        stripped[0] = 1;
        stripped[1] = 23;
        stripped[2] = 31;
        System.arraycopy(jpeg, entropy, stripped, 3, entropyLength);
        byte[] restored = StrippedJpeg.restore(stripped);
        JpegDecoder.Decoded decoded = JpegDecoder.decodePixels(
                new ByteArrayInputStream(restored), null);
        Assert.equal("stripped width", 31, decoded.width);
        Assert.equal("stripped height", 23, decoded.height);
    }

    private static void truncation() throws Exception
    {
        byte[] jpeg = encode(pattern(BufferedImage.TYPE_INT_RGB), 2, 2, true);
        byte[] cut = new byte[jpeg.length - 17];
        System.arraycopy(jpeg, 0, cut, 0, cut.length);
        expectFailure("truncation", cut, null);
    }

    private static void unsupportedArithmetic() throws Exception
    {
        byte[] jpeg = encode(pattern(BufferedImage.TYPE_INT_RGB), 1, 1, false);
        int sof = marker(jpeg, 0xc0);
        jpeg[sof + 1] = (byte) 0xc9;
        expectFailure("arithmetic", jpeg, "arithmetic");
    }

    private static void unsupportedLosslessAndCmyk() throws Exception
    {
        byte[] lossless = encode(pattern(BufferedImage.TYPE_INT_RGB),
                1, 1, false);
        int sof = marker(lossless, 0xc0);
        lossless[sof + 1] = (byte) 0xc3;
        expectFailure("lossless", lossless, "lossless");

        byte[] cmyk = encode(pattern(BufferedImage.TYPE_INT_RGB),
                1, 1, false);
        sof = marker(cmyk, 0xc0);
        cmyk[sof + 9] = 4;
        expectFailure("CMYK", cmyk, "CMYK");
    }

    private static int marker(byte[] jpeg, int low)
    {
        int found = markerOrMinusOne(jpeg, low);
        if (found >= 0) { return found; }
        throw new AssertionError("marker ff" + Integer.toHexString(low)
                + " not found");
    }

    private static int markerOrMinusOne(byte[] jpeg, int low)
    {
        for (int i = 0; i + 1 < jpeg.length; i++)
        {
            if ((jpeg[i] & 255) == 0xff && (jpeg[i + 1] & 255) == low)
            {
                return i;
            }
        }
        return -1;
    }

    private static void expectFailure(String name, byte[] jpeg, String contains)
            throws Exception
    {
        try
        {
            JpegDecoder.decodePixels(new ByteArrayInputStream(jpeg), null);
            Assert.fail(name + " accepted");
        }
        catch (IOException expected)
        {
            if (contains != null)
            {
                Assert.isTrue(name + " message",
                        expected.getMessage().indexOf(contains) >= 0);
            }
        }
    }
}
