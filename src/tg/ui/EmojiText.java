package tg.ui;

import java.io.IOException;
import java.io.InputStream;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/** Mixed device-font, sprite-emoji and semantic ASCII renderer. */
public final class EmojiText
{
    public static final int GLYPH = 16;
    private static Image sheet;
    private static boolean loadAttempted;

    private EmojiText() { }

    public static int stringWidth(String text, Font font)
    {
        return substringWidth(text, 0, text == null ? 0 : text.length(), font);
    }

    public static int substringWidth(String text, int start, int length, Font font)
    {
        if (text == null || length <= 0) { return 0; }
        int end = Math.min(text.length(), start + length);
        int width = 0;
        int at = start;
        while (at < end)
        {
            Token token = token(text, at, end);
            width += token.sprite ? GLYPH : font.stringWidth(token.text);
            at = token.end;
        }
        return width;
    }

    public static void drawString(Graphics g, String text, int x, int y, Font font)
    {
        if (text == null) { return; }
        Image sprites = sprites();
        int at = 0;
        while (at < text.length())
        {
            Token token = token(text, at, text.length());
            if (token.sprite && sprites != null)
            {
                int cell = token.cell;
                int sx = (cell & 15) * GLYPH;
                int sy = (cell >> 4) * GLYPH;
                g.drawRegion(sprites, sx, sy, GLYPH, GLYPH, 0,
                        x, y, Graphics.TOP | Graphics.LEFT);
                x += GLYPH;
            }
            else
            {
                g.setFont(font);
                g.drawString(token.text, x, y, Graphics.TOP | Graphics.LEFT);
                x += font.stringWidth(token.text);
            }
            at = token.end;
        }
    }

    /** Boundary after one renderer token; never splits a surrogate/ZWJ sequence. */
    public static int nextBoundary(String text, int at)
    {
        if (text == null || at >= text.length()) { return at; }
        return token(text, at, text.length()).end;
    }

    public static boolean hasSprite(int codePoint)
    {
        return find(codePoint) >= 0;
    }

    public static String fallbackFor(int codePoint)
    {
        return isEmoji(codePoint) ? fallback(codePoint) : null;
    }

    private static Token token(String text, int at, int end)
    {
        int cp = codePoint(text, at);
        int next = at + (cp > 0xffff ? 2 : 1);
        if (next < end && text.charAt(next) == '\ufe0f') { next++; }
        if (next < end)
        {
            int modifier = codePoint(text, next);
            if (modifier >= 0x1f3fb && modifier <= 0x1f3ff)
            {
                next += modifier > 0xffff ? 2 : 1;
            }
        }
        // A compact client renders a ZWJ sequence as its first useful base.
        int sequenceEnd = next;
        while (sequenceEnd < end && text.charAt(sequenceEnd) == '\u200d')
        {
            sequenceEnd++;
            if (sequenceEnd >= end) { break; }
            int joined = codePoint(text, sequenceEnd);
            sequenceEnd += joined > 0xffff ? 2 : 1;
            if (sequenceEnd < end && text.charAt(sequenceEnd) == '\ufe0f')
            {
                sequenceEnd++;
            }
        }
        int cell = find(cp);
        Token out = new Token();
        out.end = sequenceEnd;
        if (cell >= 0)
        {
            out.sprite = true;
            out.cell = cell;
            out.text = "";
        }
        else if (isEmoji(cp))
        {
            out.text = fallback(cp);
        }
        else
        {
            out.text = text.substring(at, next);
            out.end = next;
        }
        return out;
    }

    private static int codePoint(String text, int at)
    {
        char high = text.charAt(at);
        if (high >= 0xd800 && high <= 0xdbff && at + 1 < text.length())
        {
            char low = text.charAt(at + 1);
            if (low >= 0xdc00 && low <= 0xdfff)
            {
                return 0x10000 + ((high - 0xd800) << 10) + (low - 0xdc00);
            }
        }
        return high;
    }

    private static int find(int cp)
    {
        int lo = 0;
        int hi = EmojiData.CODEPOINTS.length - 1;
        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            int value = EmojiData.CODEPOINTS[mid];
            if (value == cp) { return EmojiData.CELLS[mid]; }
            if (value < cp) { lo = mid + 1; }
            else { hi = mid - 1; }
        }
        return -1;
    }

    private static boolean isEmoji(int cp)
    {
        return cp >= 0x1f000 || (cp >= 0x2600 && cp <= 0x27ff)
                || cp == 0x00a9 || cp == 0x00ae || cp == 0x203c
                || cp == 0x2049 || cp == 0x2122 || cp == 0x2139;
    }

    private static String fallback(int cp)
    {
        switch (cp)
        {
            case 0x1f600: case 0x1f603: case 0x1f604: return ":-)";
            case 0x1f622: case 0x1f625: case 0x1f62d: return ":'-(";
            case 0x1f620: case 0x1f621: return ">:(";
            case 0x1f609: return ";-)";
            case 0x1f618: return ":-*";
            case 0x2764: case 0x1f49b: case 0x1f49a:
            case 0x1f499: case 0x1f49c: return "<3";
            case 0x1f4f7: return "[camera]";
            case 0x1f525: return "[fire]";
            case 0x1f389: return "[party]";
            case 0x1f680: return "[rocket]";
            case 0x1f44d: return "[+]";
            case 0x1f44e: return "[-]";
            default: return "[emoji]";
        }
    }

    /**
     * Drop the decoded sprite sheet so it can be collected, and allow it to be
     * loaded again on the next paint.
     *
     * Both fields have to be reset. {@code loadAttempted} latches true so that
     * a handset with no PNG decoder is not asked twice, which means nulling the
     * sheet alone would disable emoji permanently rather than temporarily.
     *
     * Worth 49 472 bytes on the one handset where it has been measured - the
     * smallest thing on the pressure ladder, and last for that reason.
     */
    public static synchronized void release()
    {
        sheet = null;
        loadAttempted = false;
    }

    private static synchronized Image sprites()
    {
        if (loadAttempted) { return sheet; }
        loadAttempted = true;
        InputStream in = null;
        try
        {
            in = EmojiText.class.getResourceAsStream("/emoji.png");
            if (in != null) { sheet = Image.createImage(in); }
        }
        catch (Throwable ignored) { sheet = null; }
        finally
        {
            if (in != null) { try { in.close(); } catch (IOException ignored) { } }
        }
        return sheet;
    }

    private static final class Token
    {
        int end;
        int cell;
        boolean sprite;
        String text;
    }
}
