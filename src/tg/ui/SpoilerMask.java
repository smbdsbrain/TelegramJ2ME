package tg.ui;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/** Tiny, static Telegram-like dot texture for concealed text. */
final class SpoilerMask
{
    private static final int WIDTH = 16;
    private static final int HEIGHT = 8;

    private Image normal;
    private Image selected;
    private int normalColor = -1;
    private int selectedColor = -1;

    void invalidate()
    {
        normal = null;
        selected = null;
        normalColor = -1;
        selectedColor = -1;
    }

    void draw(Graphics g, int x, int y, int width, int lineHeight,
              int color, boolean focused)
    {
        if (width <= 0) { return; }
        Image tile = image(color, focused);
        int top = y + Math.max(0, (lineHeight - HEIGHT) / 2);
        int clipX = g.getClipX();
        int clipY = g.getClipY();
        int clipWidth = g.getClipWidth();
        int clipHeight = g.getClipHeight();
        g.clipRect(x, top, width, HEIGHT);
        for (int at = x; at < x + width; at += WIDTH)
        {
            g.drawImage(tile, at, top, Graphics.TOP | Graphics.LEFT);
        }
        g.setClip(clipX, clipY, clipWidth, clipHeight);
    }

    private Image image(int color, boolean focused)
    {
        if (focused)
        {
            if (selected == null || selectedColor != color)
            {
                selected = create(color);
                selectedColor = color;
            }
            return selected;
        }
        if (normal == null || normalColor != color)
        {
            normal = create(color);
            normalColor = color;
        }
        return normal;
    }

    private static Image create(int color)
    {
        int[] pixels = new int[WIDTH * HEIGHT];
        int opaque = 0xff000000 | (color & 0x00ffffff);
        for (int y = 0; y < HEIGHT; y++)
        {
            int shift = (y & 1) == 0 ? 1 : 3;
            for (int x = shift; x < WIDTH; x += 4)
            {
                pixels[y * WIDTH + x] = opaque;
            }
        }
        return Image.createRGBImage(pixels, WIDTH, HEIGHT, true);
    }
}
