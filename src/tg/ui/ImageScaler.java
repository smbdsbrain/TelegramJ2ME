package tg.ui;

import java.io.IOException;

import javax.microedition.lcdui.Image;

/** Nearest-neighbour fit with strict output and source pixel caps. */
public final class ImageScaler
{
    public static final int MAX_PIXELS = 307200;

    private ImageScaler() { }

    public static Image fit(Image source, int width, int height)
            throws IOException
    {
        int sw = source.getWidth();
        int sh = source.getHeight();
        if (sw <= 0 || sh <= 0 || (long) sw * sh > MAX_PIXELS)
        {
            throw new IOException("decoded photo dimensions exceed memory policy");
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
        if (sw <= 0 || sh <= 0 || (long) sw * sh > MAX_PIXELS)
        {
            throw new IOException("decoded photo dimensions exceed memory policy");
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
        if ((long) dw * dh > MAX_PIXELS)
        {
            throw new IOException("scaled photo exceeds memory policy");
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
