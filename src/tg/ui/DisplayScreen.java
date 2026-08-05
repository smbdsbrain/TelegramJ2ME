package tg.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * The usable canvas, measured rather than assumed.
 *
 * A GT-C3592 advertises a 240x320 panel and hands a MIDlet 240x268; the missing
 * 52 pixels are AMS chrome. Every layout budget in this client is written
 * against the usable figure, so it has to come from the runtime, and it has to
 * come from a Canvas that is actually on screen - {@code getWidth()} on a
 * Canvas that has never been shown is not required to mean anything.
 *
 * The full-screen figure is measured too because it is the cheapest layout win
 * available: nothing in {@code src/} calls {@code setFullScreenMode} today, and
 * whether that is leaving 52 pixels on the table is a per-handset question.
 */
public final class DisplayScreen extends Canvas
{
    public static final Command CMD_FULLSCREEN =
            new Command("Full screen", Command.SCREEN, 1);

    private final Font font;
    private final Metrics metrics = new Metrics();
    private final Theme theme;

    private int normalWidth;
    private int normalHeight;
    private int fullWidth;
    private int fullHeight;
    private boolean fullScreen;

    public DisplayScreen()
    {
        this(Theme.byId(Theme.LIGHT));
    }

    public DisplayScreen(Theme theme)
    {
        font = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN,
                            Font.SIZE_SMALL);
        this.theme = theme == null ? Theme.byId(Theme.LIGHT) : theme;
    }

    /** Toggle and re-measure. Both figures are kept; the pair is the finding. */
    public void toggleFullScreen()
    {
        fullScreen = !fullScreen;
        try
        {
            setFullScreenMode(fullScreen);
        }
        catch (Throwable ignored)
        {
            // MIDP 2.0 defines it, but a handset that refuses is itself the
            // answer; the recorded sizes will simply be equal.
        }
        repaint();
    }

    protected void sizeChanged(int w, int h)
    {
        record(w, h);
    }

    protected void paint(Graphics g)
    {
        record(getWidth(), getHeight());

        metrics.update(getWidth(), getHeight(), font, font);
        UiChrome.background(g, theme, metrics);
        g.setFont(font);
        UiChrome.header(g, theme, metrics, font,
                "Display " + getWidth() + "x" + getHeight(), "");

        g.setColor(theme.text);
        int line = Math.max(font.getHeight(), 1);
        int y = metrics.bodyTop;
        String[] rows = snapshot();
        for (int i = 0; i < rows.length; i++)
        {
            g.drawString(rows[i], metrics.padding, y,
                    Graphics.TOP | Graphics.LEFT);
            y += line;
            if (y > metrics.bodyBottom - line) { break; }
        }
    }

    /** Report for transcription and upload. */
    public String[] snapshot()
    {
        return new String[] {
            "canvas normal    = " + size(normalWidth, normalHeight),
            "canvas fullscreen= " + size(fullWidth, fullHeight),
            "gain             = " + gain(),
            "hasRepeatEvents  = " + safe(0),
            "hasPointerEvents = " + safe(1),
            "hasPointerMotion = " + safe(2),
            "doubleBuffered   = " + safe(3),
            "press Full screen to fill the second row"
        };
    }

    private void record(int w, int h)
    {
        if (w <= 0 || h <= 0) { return; }
        if (fullScreen) { fullWidth = w; fullHeight = h; }
        else { normalWidth = w; normalHeight = h; }
    }

    private String gain()
    {
        if (normalHeight <= 0 || fullHeight <= 0) { return "not measured yet"; }
        int dw = fullWidth - normalWidth;
        int dh = fullHeight - normalHeight;
        if (dw == 0 && dh == 0)
        {
            return "none - full screen changes nothing here";
        }
        // Not "0x52": a WxH delta reads as a hex literal exactly when one side
        // is zero, which is the common case and the first one measured.
        return "+" + dw + " px wide, +" + dh + " px tall";
    }

    private static String size(int w, int h)
    {
        return w <= 0 ? "not measured yet" : (w + "x" + h);
    }

    private String safe(int which)
    {
        try
        {
            switch (which)
            {
                case 0: return String.valueOf(hasRepeatEvents());
                case 1: return String.valueOf(hasPointerEvents());
                case 2: return String.valueOf(hasPointerMotionEvents());
                default: return String.valueOf(isDoubleBuffered());
            }
        }
        catch (Throwable t)
        {
            return "unavailable";
        }
    }
}
