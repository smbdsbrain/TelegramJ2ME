package tg.plat;

import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Font;

/**
 * What the display is, as opposed to what the panel is advertised as.
 *
 * A Samsung GT-C3592 advertises 240x320 and gives a MIDlet a 240x268 canvas -
 * the AMS keeps 52 pixels of chrome. Every layout decision in this client rests
 * on the usable figure, and until now the only place it surfaced was a line
 * inside the interactive Keys screen, which means it is missing from both
 * hardware notes written so far.
 *
 * The colour and font half needs no Canvas and so runs in the automatic sweep.
 * Canvas geometry does need one on screen - a Canvas that has never been shown
 * reports whatever the implementation feels like, frequently zero - so it lives
 * in {@code tg.ui.DisplayScreen}, which measures itself when it is painted.
 */
public final class DisplayProbe
{
    private DisplayProbe() { }

    public static String[] run(Display display)
    {
        String[] out = new String[16];
        int w = 0;

        out[w++] = "display capabilities";
        out[w++] = "";

        if (display == null)
        {
            out[w++] = "no Display (not running as a MIDlet)";
        }
        else
        {
            out[w++] = "numColors = " + safeInt(display, 0);
            out[w++] = "isColor = " + safeBool(display);
            out[w++] = "colour depth ~ " + depthOf(safeInt(display, 0)) + " bpp";
        }
        out[w++] = "";

        // Font metrics decide how many message lines fit a screen, which is the
        // other half of the layout budget. SIZE_SMALL is what the chat list
        // uses when the theme asks for compact.
        out[w++] = "fonts (height / baseline / 'M' width):";
        out[w++] = "  small  " + describe(Font.SIZE_SMALL);
        out[w++] = "  medium " + describe(Font.SIZE_MEDIUM);
        out[w++] = "  large  " + describe(Font.SIZE_LARGE);
        out[w++] = "";
        out[w++] = "default font height = " + defaultFontHeight();
        out[w++] = "";
        out[w++] = "canvas size: see the Display screen -";
        out[w++] = "a Canvas must be shown before it";
        out[w++] = "reports its real size.";

        String[] trimmed = new String[w];
        System.arraycopy(out, 0, trimmed, 0, w);
        return trimmed;
    }

    /**
     * Every lcdui call here is guarded, including this one.
     *
     * A probe runs inside the unattended sweep, where an exception costs the
     * whole scenario - and a handset that answers a font query with null is
     * exactly the kind of thing this build exists to find out about, not to
     * fall over on.
     */
    private static String defaultFontHeight()
    {
        try
        {
            Font f = Font.getDefaultFont();
            return f == null ? "no default font" : String.valueOf(f.getHeight());
        }
        catch (Throwable t)
        {
            return "unavailable (" + t.getClass().getName() + ")";
        }
    }

    private static String describe(int size)
    {
        try
        {
            Font f = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, size);
            return f.getHeight() + " / " + f.getBaselinePosition()
                    + " / " + f.charWidth('M');
        }
        catch (Throwable t)
        {
            return "unavailable (" + t.getClass().getName() + ")";
        }
    }

    private static int safeInt(Display display, int fallback)
    {
        try { return display.numColors(); }
        catch (Throwable t) { return fallback; }
    }

    private static String safeBool(Display display)
    {
        try { return String.valueOf(display.isColor()); }
        catch (Throwable t) { return "unavailable"; }
    }

    /** Colours to bits, so the figure can be compared with an image budget. */
    private static int depthOf(int colours)
    {
        int bits = 0;
        long n = 1;
        while (n < colours && bits < 32) { n <<= 1; bits++; }
        return bits;
    }
}
