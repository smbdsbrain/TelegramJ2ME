package tg.ui;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/** Allocation-free painter for common Canvas chrome. */
public final class UiChrome
{
    private UiChrome() { }

    public static void background(Graphics g, Theme theme, Metrics metrics)
    {
        g.setColor(theme.background);
        g.fillRect(0, 0, metrics.width, metrics.height);
    }

    public static void header(Graphics g, Theme theme, Metrics metrics,
                              Font font, String left, String right)
    {
        g.setColor(theme.accent);
        g.fillRect(0, 0, metrics.width, metrics.headerHeight);
        g.setColor(theme.accentText);
        g.setFont(font);
        int y = metrics.padding;
        if (left != null)
        {
            g.drawString(clip(left, font,
                    Math.max(1, metrics.width * 2 / 3)),
                    metrics.padding, y, Graphics.TOP | Graphics.LEFT);
        }
        if (right != null && right.length() > 0)
        {
            g.drawString(clip(right, font, Math.max(1, metrics.width / 3)),
                    metrics.width - metrics.padding, y,
                    Graphics.TOP | Graphics.RIGHT);
        }
    }

    public static String clip(String value, Font font, int width)
    {
        if (value == null || width <= 0) { return ""; }
        if (font.stringWidth(value) <= width) { return value; }
        String suffix = "...";
        int suffixWidth = font.stringWidth(suffix);
        if (suffixWidth > width) { return ""; }
        int end = value.length();
        while (end > 0
                && font.substringWidth(value, 0, end) + suffixWidth > width)
        {
            end--;
        }
        return value.substring(0, end) + suffix;
    }
}
