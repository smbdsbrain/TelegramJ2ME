package tg.ui;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

import tg.api.MessageEntity;

/** Bounded mixed-font measurement and rendering for Telegram message text. */
public final class RichText
{
    private static final int STRIKE = 1 << 3;
    private static final int MONO = 1 << 4;
    private static final int SPOILER = 1 << 5;
    private static final int QUOTE = 1 << 6;

    private final Font[] proportional = new Font[8];
    private final Font[] monospace = new Font[8];
    private final SpoilerMask spoilerMask = new SpoilerMask();
    private final int lineHeight;
    private final int baseline;

    public RichText()
    {
        proportional[0] = Font.getFont(Font.FACE_PROPORTIONAL,
                Font.STYLE_PLAIN, Font.SIZE_SMALL);
        monospace[0] = Font.getFont(Font.FACE_MONOSPACE,
                Font.STYLE_PLAIN, Font.SIZE_SMALL);
        lineHeight = Math.max(EmojiText.GLYPH,
                Math.max(proportional[0].getHeight(), monospace[0].getHeight()));
        baseline = Math.max(proportional[0].getBaselinePosition(),
                monospace[0].getBaselinePosition());
    }

    public Font plainFont() { return proportional[0]; }
    public int lineHeight() { return lineHeight; }

    public void themeChanged() { spoilerMask.invalidate(); }

    /** Width of the text tokens in [start,end), using all active entities. */
    public int width(String text, int start, int end, MessageEntity[] entities)
    {
        int width = 0;
        int at = start;
        while (at < end)
        {
            int next = Math.min(end, EmojiText.nextBoundary(text, at));
            width += EmojiText.substringWidth(text, at, next - at,
                    font(flagsAt(entities, at)));
            at = next;
        }
        return width;
    }

    /** First token that does not fit, or end when the whole range fits. */
    public int fitEnd(String text, int start, int end, int width,
                      MessageEntity[] entities)
    {
        int used = 0;
        int at = start;
        while (at < end)
        {
            int next = Math.min(end, EmojiText.nextBoundary(text, at));
            used += EmojiText.substringWidth(text, at, next - at,
                    font(flagsAt(entities, at)));
            if (used > width) { return at; }
            at = next;
        }
        return end;
    }

    /**
     * Keep a blockquote on its own visual lines, including malformed input
     * where Telegram did not align the entity to newline boundaries.
     */
    public int blockBoundary(int start, int end, MessageEntity[] entities)
    {
        if (entities == null) { return end; }
        boolean quoted = quotedAt(entities, start);
        int boundary = end;
        for (int i = 0; i < entities.length; i++)
        {
            MessageEntity entity = entities[i];
            if (entity == null || entity.type != MessageEntity.BLOCKQUOTE) { continue; }
            int entityEnd = entity.offset + entity.length;
            if (quoted && entityEnd > start && entityEnd < boundary)
            {
                boundary = entityEnd;
            }
            else if (!quoted && entity.offset > start && entity.offset < boundary)
            {
                boundary = entity.offset;
            }
        }
        return boundary;
    }

    public boolean quotedAt(MessageEntity[] entities, int offset)
    {
        return (flagsAt(entities, offset) & QUOTE) != 0;
    }

    public void drawLine(Graphics g, String text, int start, int end,
                         int x, int y, MessageEntity[] entities,
                         boolean revealSpoilers, boolean focused,
                         int textColor, int spoilerColor)
    {
        int at = start;
        while (at < end)
        {
            int flags = flagsAt(entities, at);
            int next = nextStyleBoundary(at, end, entities);
            Font active = font(flags);
            int width = width(text, at, next, entities);
            int top = y + baseline - active.getBaselinePosition();
            if ((flags & SPOILER) != 0 && !revealSpoilers)
            {
                spoilerMask.draw(g, x, y, width, lineHeight,
                        spoilerColor, focused);
            }
            else
            {
                g.setColor(textColor);
                EmojiText.drawString(g, text.substring(at, next), x, top, active);
                if ((flags & STRIKE) != 0)
                {
                    int strikeY = top + Math.max(1,
                            active.getBaselinePosition() / 2);
                    g.drawLine(x, strikeY, x + width - 1, strikeY);
                }
            }
            x += width;
            at = next;
        }
    }

    private Font font(int flags)
    {
        int style = flags & (Font.STYLE_BOLD | Font.STYLE_ITALIC
                | Font.STYLE_UNDERLINED);
        Font[] cache = (flags & MONO) != 0 ? monospace : proportional;
        if (cache[style] == null)
        {
            cache[style] = Font.getFont((flags & MONO) != 0
                            ? Font.FACE_MONOSPACE : Font.FACE_PROPORTIONAL,
                    style, Font.SIZE_SMALL);
        }
        return cache[style];
    }

    private static int nextStyleBoundary(int at, int end,
                                         MessageEntity[] entities)
    {
        int next = end;
        if (entities == null) { return next; }
        for (int i = 0; i < entities.length; i++)
        {
            MessageEntity entity = entities[i];
            if (entity == null) { continue; }
            int entityEnd = entity.offset + entity.length;
            if (entity.offset > at && entity.offset < next) { next = entity.offset; }
            if (entityEnd > at && entityEnd < next) { next = entityEnd; }
        }
        return next;
    }

    private static int flagsAt(MessageEntity[] entities, int offset)
    {
        int flags = 0;
        if (entities == null) { return flags; }
        for (int i = 0; i < entities.length; i++)
        {
            MessageEntity entity = entities[i];
            if (entity == null || offset < entity.offset
                    || offset >= entity.offset + entity.length) { continue; }
            switch (entity.type)
            {
                case MessageEntity.BOLD: flags |= Font.STYLE_BOLD; break;
                case MessageEntity.ITALIC: flags |= Font.STYLE_ITALIC; break;
                case MessageEntity.UNDERLINE: flags |= Font.STYLE_UNDERLINED; break;
                case MessageEntity.STRIKE: flags |= STRIKE; break;
                case MessageEntity.CODE:
                case MessageEntity.PRE: flags |= MONO; break;
                case MessageEntity.BLOCKQUOTE: flags |= QUOTE; break;
                case MessageEntity.SPOILER: flags |= SPOILER; break;
                default:
                    if (entity.actionable()) { flags |= Font.STYLE_UNDERLINED; }
                    break;
            }
        }
        return flags;
    }
}
