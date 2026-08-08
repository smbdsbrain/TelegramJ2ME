package tg.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/** Compact reaction picker rendered with the same emoji atlas as chat text. */
public class ReactionScreen extends Canvas
{
    public interface ActivationListener
    {
        void onReactionSelected(int index);
        void onRemoveAll();
        void onViewReactions();
        void onViewSource();
    }

    private final Font font;
    private final Font metaFont;
    private final Metrics metrics = new Metrics();
    private Theme theme;
    private String[] emoji = new String[0];
    private String[] labels = new String[0];
    private boolean[] chosen = new boolean[0];
    private boolean showRemoveAll;
    private boolean showViewReactions;
    private String viewSourceLabel;
    private int selected;
    private int top;
    private ActivationListener activationListener;

    public ReactionScreen()
    {
        this(Theme.byId(Theme.LIGHT));
    }

    public ReactionScreen(Theme theme)
    {
        font = Font.getFont(Font.FACE_PROPORTIONAL,
                Font.STYLE_PLAIN, Font.SIZE_SMALL);
        metaFont = Font.getFont(Font.FACE_PROPORTIONAL,
                Font.STYLE_PLAIN, Font.SIZE_SMALL);
        this.theme = theme == null ? Theme.byId(Theme.LIGHT) : theme;
    }

    public void setTheme(Theme value)
    {
        theme = value == null ? Theme.byId(Theme.LIGHT) : value;
        repaint();
    }

    public void setActivationListener(ActivationListener value)
    {
        activationListener = value;
    }

    public void setReactions(String[] values, String[] names,
                             String[] selectedEmoji)
    {
        emoji = values == null ? new String[0] : values;
        labels = names == null ? new String[0] : names;
        chosen = new boolean[emoji.length];
        showRemoveAll = selectedEmoji != null && selectedEmoji.length > 0;
        for (int i = 0; i < emoji.length; i++)
        {
            for (int j = 0; selectedEmoji != null
                    && j < selectedEmoji.length; j++)
            {
                if (emoji[i].equals(selectedEmoji[j]))
                {
                    chosen[i] = true;
                    break;
                }
            }
        }
        selected = 0;
        top = 0;
        repaint();
    }

    public void setActions(boolean viewReactions, String sourceLabel)
    {
        showViewReactions = viewReactions;
        viewSourceLabel = sourceLabel;
        selected = 0;
        top = 0;
        repaint();
    }

    public int itemCount()
    {
        return actionCount() + emoji.length + (showRemoveAll ? 1 : 0);
    }

    public boolean isChosen(int index)
    {
        return index >= 0 && index < chosen.length && chosen[index];
    }

    protected void paint(Graphics g)
    {
        updateMetrics();
        UiChrome.background(g, theme, metrics);
        UiChrome.header(g, theme, metrics, font, "Reactions", "");

        int rowHeight = rowHeight();
        int visible = metrics.bodyHeight / rowHeight;
        if (visible < 1) { visible = 1; }
        ensureVisible(visible);
        int y = metrics.bodyTop;
        int count = itemCount();
        for (int row = 0; row < visible; row++)
        {
            int index = top + row;
            if (index >= count) { break; }
            boolean focused = index == selected;
            if (focused)
            {
                g.setColor(theme.selection);
                g.fillRect(0, y, metrics.width, rowHeight);
            }
            int rowText = focused ? theme.selectionText : theme.text;
            int actionCount = actionCount();
            if (index < actionCount)
            {
                g.setFont(font);
                g.setColor(rowText);
                String action = showViewReactions && index == 0
                        ? "View reactions" : viewSourceLabel;
                g.drawString(action, metrics.padding, y + metrics.padding,
                        Graphics.TOP | Graphics.LEFT);
            }
            else if (index - actionCount < emoji.length)
            {
                int reaction = index - actionCount;
                g.setColor(chosen[reaction] ? theme.accent : rowText);
                g.setFont(font);
                g.drawString(chosen[reaction] ? "[x]" : "[ ]",
                        metrics.padding, y + metrics.padding,
                        Graphics.TOP | Graphics.LEFT);
                int emojiX = metrics.padding + font.stringWidth("[x]")
                        + metrics.padding;
                EmojiText.drawString(g, emoji[reaction], emojiX,
                        y + metrics.padding, font);
                int labelX = emojiX
                        + EmojiText.stringWidth(emoji[reaction], font)
                        + metrics.padding;
                String label = reaction < labels.length ? labels[reaction] : "";
                EmojiText.drawString(g, UiChrome.clip(label, font,
                        Math.max(1, metrics.width - labelX - metrics.padding)),
                        labelX, y + metrics.padding, font);
            }
            else
            {
                g.setFont(metaFont);
                g.setColor(focused
                        ? theme.selectionText : theme.secondaryText);
                g.drawString("Remove all reactions", metrics.padding,
                        y + metrics.padding, Graphics.TOP | Graphics.LEFT);
            }
            y += rowHeight;
        }
    }

    protected void sizeChanged(int width, int height)
    {
        metrics.update(width, height, font, metaFont);
        int visible = metrics.bodyHeight / rowHeight();
        ensureVisible(visible < 1 ? 1 : visible);
    }

    protected void keyPressed(int keyCode)
    {
        int action = 0;
        try { action = getGameAction(keyCode); }
        catch (Throwable ignored) { }
        if (action == Canvas.UP || keyCode == Canvas.KEY_NUM2) { move(-1); }
        else if (action == Canvas.DOWN || keyCode == Canvas.KEY_NUM8) { move(1); }
        else if (action == Canvas.FIRE || keyCode == Canvas.KEY_NUM5) { activate(); }
    }

    protected void keyRepeated(int keyCode) { keyPressed(keyCode); }

    private void move(int delta)
    {
        int count = itemCount();
        if (count == 0) { return; }
        selected += delta;
        if (selected < 0) { selected = 0; }
        if (selected >= count) { selected = count - 1; }
        repaint();
    }

    /**
     * Take the current selection, as the fire key would.
     *
     * Exists because on at least one handset the fire key does not arrive. The
     * Nokia C3-00 gives the AMS the whole navigation cluster along with the
     * soft, call and end keys - measured twice, written up in
     * docs/hardware/nokia-c3-00.md - so a Canvas that can only be activated by
     * FIRE cannot be activated at all there. Every other selectable Canvas in
     * this client already carries a command for the same reason; this one was
     * the exception, and the symptom was a reaction palette a reader could
     * open and not use.
     */
    public void activateSelected()
    {
        activate();
    }

    /** Move the selection without the navigation cluster. */
    public void moveSelection(int delta)
    {
        move(delta);
    }

    private void activate()
    {
        ActivationListener listener = activationListener;
        if (listener == null || itemCount() == 0) { return; }
        int actions = actionCount();
        if (selected < actions)
        {
            if (showViewReactions && selected == 0) { listener.onViewReactions(); }
            else { listener.onViewSource(); }
        }
        else if (selected - actions < emoji.length)
        {
            listener.onReactionSelected(selected - actions);
        }
        else { listener.onRemoveAll(); }
    }

    private void ensureVisible(int visible)
    {
        if (selected < top) { top = selected; }
        if (selected >= top + visible) { top = selected - visible + 1; }
        if (top < 0) { top = 0; }
    }

    private int actionCount()
    {
        return (showViewReactions ? 1 : 0)
                + (viewSourceLabel == null ? 0 : 1);
    }

    private int rowHeight()
    {
        return Math.max(1, metrics.lineHeight + metrics.padding * 2);
    }

    private void updateMetrics()
    {
        metrics.update(getWidth(), getHeight(), font, metaFont);
    }
}
