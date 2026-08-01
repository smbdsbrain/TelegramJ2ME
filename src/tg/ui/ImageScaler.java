package tg.ui;

import java.io.IOException;

import javax.microedition.lcdui.Image;

import tg.mem.MemoryBudget;

/**
 * Nearest-neighbour fit with strict output and source pixel caps.
 *
 * The cap is {@link tg.mem.MemoryBudget#photoPixels}, the same number the
 * decoder enforces. It used to be a second copy of the same literal, and two
 * copies of one policy is one copy too many: a decoder and a scaler that
 * disagree about what fits produce an image nothing can display.
 */
public final class ImageScaler
{
    private ImageScaler() { }

    public static Image fit(Image source, int width, int height)
            throws IOException
    {
        int sw = source.getWidth();
        int sh = source.getHeight();
        if (sw <= 0 || sh <= 0 || (long) sw * sh > MemoryBudget.photoPixels())
        {
            throw new IOException("decoded photo is " + sw + "x" + sh
                    + ", over the " + MemoryBudget.photoPixels() + " pixel limit");
        }
        int dw = sw;
        int dh = sh;
        if (dw > width)
        {
            dh = Math.max(1, dh * width / dw);
            dw = width;
        }
        // Landscape photos fit fully; tall photos stay fit-width and scroll.
        if (dw >= dh && dh > height)
        {
            dw = Math.max(1, dw * height / dh);
            dh = height;
        }
        return scale(source, sw, sh, dw, dh);
    }

    /** Fit both dimensions, used by inline thumbnails. */
    public static Image fitBox(Image source, int width, int height)
            throws IOException
    {
        int sw = source.getWidth();
        int sh = source.getHeight();
        if (sw <= 0 || sh <= 0 || (long) sw * sh > MemoryBudget.photoPixels())
        {
            throw new IOException("decoded photo is " + sw + "x" + sh
                    + ", over the " + MemoryBudget.photoPixels() + " pixel limit");
        }
        int dw = sw;
        int dh = sh;
        if (dw > width)
        {
            dh = Math.max(1, dh * width / dw);
            dw = width;
        }
        if (dh > height)
        {
            dw = Math.max(1, dw * height / dh);
            dh = height;
        }
        return scale(source, sw, sh, dw, dh);
    }

    private static Image scale(Image source, int sw, int sh, int dw, int dh)
            throws IOException
    {
        if (dw == sw && dh == sh) { return source; }
        if ((long) dw * dh > MemoryBudget.photoPixels())
        {
            throw new IOException("scaled photo is " + dw + "x" + dh
                    + ", over the " + MemoryBudget.photoPixels() + " pixel limit");
        }
        int[] src = new int[sw * sh];
        source.getRGB(src, 0, sw, 0, 0, sw, sh);
        int[] dst = new int[dw * dh];
        for (int y = 0; y < dh; y++)
        {
            int sy = y * sh / dh;
            int row = sy * sw;
            for (int x = 0; x < dw; x++)
            {
                dst[y * dw + x] = src[row + x * sw / dw];
            }
        }
        src = null;
        return Image.createRGBImage(dst, dw, dh, false);
    }
}
