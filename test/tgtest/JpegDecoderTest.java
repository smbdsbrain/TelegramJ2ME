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
        headerDimensions();
        strippedDecodeCost();
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

    /**
     * The size of an image, read without decoding it.
     *
     * This is what lets the client refuse an avatar decode instead of catching
     * the OutOfMemoryError afterwards, so the number has to agree with the
     * decoder on every shape the decoder accepts - and has to be 0, not a guess,
     * on everything it does not. A wrong answer here is a memory budget computed
     * for an image that is not the one about to be built.
     */
    private static void headerDimensions() throws Exception
    {
        String[] names = { "gray", "444", "422", "420", "progressive" };
        int[][] shapes = { { BufferedImage.TYPE_BYTE_GRAY, 1, 1, 0 },
                           { BufferedImage.TYPE_INT_RGB, 1, 1, 0 },
                           { BufferedImage.TYPE_INT_RGB, 2, 1, 0 },
                           { BufferedImage.TYPE_INT_RGB, 2, 2, 0 },
                           { BufferedImage.TYPE_INT_RGB, 2, 2, 1 } };
        for (int i = 0; i < names.length; i++)
        {
            byte[] jpeg = encode(pattern(shapes[i][0]), shapes[i][1],
                    shapes[i][2], shapes[i][3] != 0);
            JpegDecoder.Decoded decoded = JpegDecoder.decodePixels(
                    new ByteArrayInputStream(jpeg), null);
            int size = JpegDecoder.dimensions(jpeg);
            Assert.equal(names[i] + " header width", decoded.width, size >>> 16);
            Assert.equal(names[i] + " header height", decoded.height, size & 0xffff);
        }

        // Restart markers appear before the frame header in some encoders and
        // carry no length field, so a walk that treated them like a segment
        // would read the next two bytes as a length and lose the frame.
        byte[] restarts = encode(pattern(BufferedImage.TYPE_INT_RGB),
                2, 2, false, 2);
        JpegDecoder.Decoded viaDecoder = JpegDecoder.decodePixels(
                new ByteArrayInputStream(restarts), null);
        int size = JpegDecoder.dimensions(restarts);
        Assert.equal("restart width", viaDecoder.width, size >>> 16);
        Assert.equal("restart height", viaDecoder.height, size & 0xffff);

        Assert.equal("null is unreadable", 0, JpegDecoder.dimensions(null));
        Assert.equal("empty is unreadable", 0,
                JpegDecoder.dimensions(new byte[0]));
        Assert.equal("a PNG is unreadable", 0, JpegDecoder.dimensions(
                new byte[] { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10 }));

        // Header cut off mid-frame: no dimensions rather than dimensions read
        // out of whatever followed in memory.
        byte[] jpeg = encode(pattern(BufferedImage.TYPE_INT_RGB), 2, 2, false);
        int sof = marker(jpeg, 0xc0);
        byte[] cut = new byte[sof + 4];
        System.arraycopy(jpeg, 0, cut, 0, cut.length);
        Assert.equal("a truncated frame header is unreadable", 0,
                JpegDecoder.dimensions(cut));

        // A zero dimension would make photoDecodeCost report a free decode.
        byte[] zeroed = encode(pattern(BufferedImage.TYPE_INT_RGB), 2, 2, false);
        sof = marker(zeroed, 0xc0);
        zeroed[sof + 5] = 0;
        zeroed[sof + 6] = 0;
        Assert.equal("a zero width is unreadable", 0,
                JpegDecoder.dimensions(zeroed));
    }

    /**
     * A stripped thumbnail states its own size, so its decode can be priced
     * without restoring it.
     */
    private static void strippedDecodeCost() throws Exception
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

        long cost = StrippedJpeg.decodeCost(stripped);
        byte[] restored = StrippedJpeg.restore(stripped);
        Assert.equal("cost priced the image restore() actually builds",
                MemoryBudget.photoDecodeCost(31, 23, restored.length), cost);
        Assert.isTrue("a thumbnail costs something", cost > 0);

        // Zero means "no opinion", and the caller admits the decode so that
        // restore() can report the real fault. It must never mean "free".
        Assert.equal("null has no price", 0L, StrippedJpeg.decodeCost(null));
        Assert.equal("a short payload has no price", 0L,
                StrippedJpeg.decodeCost(new byte[] { 1, 2 }));
        Assert.equal("a foreign format has no price", 0L,
                StrippedJpeg.decodeCost(new byte[] { 2, 23, 31, 0 }));
        Assert.equal("a zero dimension has no price", 0L,
                StrippedJpeg.decodeCost(new byte[] { 1, 0, 31, 0 }));
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
