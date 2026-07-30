package tg.ui;

import javax.microedition.lcdui.Font;

/** Viewport-derived geometry shared by application-drawn screens. */
public final class Metrics
{
    public int width;
    public int height;
    public int unit;
    public int padding;
    public int lineHeight;
    public int headerHeight;
    public int bodyTop;
    public int bodyBottom;
    public int bodyHeight;
    public int contentWidth;
    public int rowHeight;
    public int iconSize;
    public int thumbnailHeight;
    public int panStep;

    public void update(int width, int height, Font primary, Font secondary)
    {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        int shortest = Math.min(this.width, this.height);
        unit = shortest / 80;
        if (unit < 2) { unit = 2; }
        if (unit > 6) { unit = 6; }
        padding = unit;
        int primaryHeight = primary == null ? 1 : primary.getHeight();
        int secondaryHeight = secondary == null
                ? primaryHeight : secondary.getHeight();
        lineHeight = Math.max(Math.max(primaryHeight, secondaryHeight),
                EmojiText.GLYPH);
        headerHeight = lineHeight + padding * 2;
        bodyTop = Math.min(this.height, headerHeight);
        // Canvas height already excludes the native MIDP command bar.
        bodyBottom = this.height;
        bodyHeight = Math.max(1, bodyBottom - bodyTop);
        contentWidth = Math.max(1, this.width - padding * 2);
        iconSize = Math.max(lineHeight, lineHeight * 2);
        rowHeight = Math.max(lineHeight * 2 + padding * 3,
                iconSize + padding * 2);
        thumbnailHeight = Math.max(lineHeight,
                Math.min(bodyHeight / 3, lineHeight * 4));
        panStep = Math.max(padding * 8, shortest / 10);
    }

    public int visibleRows()
    {
        int count = bodyHeight / Math.max(1, rowHeight);
        return count < 1 ? 1 : count;
    }

    public int visibleLines()
    {
        int count = bodyHeight / Math.max(1, lineHeight);
        return count < 1 ? 1 : count;
    }
}
