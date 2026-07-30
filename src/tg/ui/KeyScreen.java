package tg.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Key-code reconnaissance.
 *
 * MIDP only defines key codes for the ITU-T keypad plus the abstract game
 * actions. Everything else - letters, the soft keys, back/clear, enter - is
 * vendor specific. The messenger's text entry and navigation depend on knowing
 * the real values, so this screen simply reports whatever the runtime sends.
 *
 * getGameAction() and getKeyName() are both called defensively: handsets are
 * known to throw IllegalArgumentException for keys they do not recognise.
 */
public class KeyScreen extends Canvas
{
    private static final int HISTORY = 12;

    private final String[] history = new String[HISTORY];
    private int count;

    private final Font font;
    private final int lineHeight;
    private final Metrics metrics = new Metrics();
    private Theme theme;

    public KeyScreen()
    {
        this(Theme.byId(Theme.LIGHT));
    }

    public KeyScreen(Theme theme)
    {
        font = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        lineHeight = Math.max(font.getHeight(), EmojiText.GLYPH);
        this.theme = theme == null ? Theme.byId(Theme.LIGHT) : theme;
    }

    protected void paint(Graphics g)
    {
        metrics.update(getWidth(), getHeight(), font, font);
        UiChrome.background(g, theme, metrics);
        g.setFont(font);
        UiChrome.header(g, theme, metrics, font,
                "Keys " + metrics.width + "x" + metrics.height, "");

        g.setColor(theme.text);
        int y = metrics.bodyTop;
        g.drawString("press any key; newest first", metrics.padding, y,
                Graphics.TOP | Graphics.LEFT);
        y += lineHeight + 2;

        for (int i = 0; i < count; i++)
        {
            g.drawString(history[i], metrics.padding, y,
                    Graphics.TOP | Graphics.LEFT);
            y += lineHeight;
            if (y > metrics.bodyBottom - lineHeight) { break; }
        }
    }

    protected void keyPressed(int keyCode)
    {
        record("DN", keyCode);
    }

    protected void keyReleased(int keyCode)
    {
        record("UP", keyCode);
    }

    protected void keyRepeated(int keyCode)
    {
        record("RP", keyCode);
    }

    /** Report of everything seen, for transcription into the hardware doc. */
    public String[] snapshot()
    {
        String[] out = new String[count + 1];
        out[0] = "canvas " + getWidth() + "x" + getHeight()
                 + " fullscreen=" + hasRepeatEvents() + " pointer=" + hasPointerEvents();
        for (int i = 0; i < count; i++) { out[i + 1] = history[i]; }
        return out;
    }

    private void record(String kind, int keyCode)
    {
        StringBuffer sb = new StringBuffer(48);
        sb.append(kind).append(' ').append(keyCode);

        sb.append(" name=");
        try { sb.append(getKeyName(keyCode)); }
        catch (Throwable t) { sb.append('?'); }

        sb.append(" action=");
        try
        {
            int a = getGameAction(keyCode);
            sb.append(actionName(a)).append('(').append(a).append(')');
        }
        catch (Throwable t) { sb.append('?'); }

        if (keyCode >= 32 && keyCode < 127)
        {
            sb.append(" char='").append((char) keyCode).append('\'');
        }

        // Newest first: shift down, then insert.
        for (int i = HISTORY - 1; i > 0; i--) { history[i] = history[i - 1]; }
        history[0] = sb.toString();
        if (count < HISTORY) { count++; }

        repaint();
    }

    private static String actionName(int a)
    {
        switch (a)
        {
            case Canvas.UP:    return "UP";
            case Canvas.DOWN:  return "DOWN";
            case Canvas.LEFT:  return "LEFT";
            case Canvas.RIGHT: return "RIGHT";
            case Canvas.FIRE:  return "FIRE";
            case Canvas.GAME_A: return "GAME_A";
            case Canvas.GAME_B: return "GAME_B";
            case Canvas.GAME_C: return "GAME_C";
            case Canvas.GAME_D: return "GAME_D";
            case 0:            return "none";
            default:           return "other";
        }
    }
}
