package tg.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

import tg.api.Poll;
import tg.api.PollOption;

/** Bounded radio/checkbox picker for one poll message. */
public class PollScreen extends Canvas
{
    public interface SelectionListener
    {
        void onSelectionChanged();
    }

    private final Font font;
    private final Font metaFont;
    private final Metrics metrics = new Metrics();
    private Theme theme;
    private Poll poll;
    private boolean[] selectedOptions = new boolean[0];
    private boolean dirty;
    private int selected;
    private int top;
    private String status = "";
    private SelectionListener selectionListener;

    public PollScreen()
    {
        this(Theme.byId(Theme.LIGHT));
    }

    public PollScreen(Theme theme)
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

    public void setSelectionListener(SelectionListener value)
    {
        selectionListener = value;
    }

    /** Apply fresh server state, preserving an unsubmitted local selection. */
    public void setPoll(Poll value)
    {
        byte[][] pending = dirty ? selectedTokens() : null;
        poll = value;
        int count = poll == null || poll.options == null
                ? 0 : poll.options.length;
        selectedOptions = new boolean[count];
        for (int i = 0; i < count; i++)
        {
            PollOption option = poll.options[i];
            selectedOptions[i] = pending == null
                    ? option != null && option.chosen
                    : contains(pending, option == null ? null : option.option);
        }
        if (poll == null || !poll.canVote()) { dirty = false; }
        if (selected >= count) { selected = count == 0 ? 0 : count - 1; }
        if (selected < 0) { selected = 0; }
        ensureVisible(visibleRows());
        repaint();
        notifySelectionChanged();
    }

    public Poll poll() { return poll; }
    public int selectedIndex() { return selected; }
    public int itemCount() { return selectedOptions.length; }
    public boolean isDirty() { return dirty; }

    public boolean isSelected(int index)
    {
        return index >= 0 && index < selectedOptions.length
                && selectedOptions[index];
    }

    public boolean canSubmit()
    {
        if (poll == null || !poll.canVote()) { return false; }
        for (int i = 0; i < selectedOptions.length; i++)
        {
            if (selectedOptions[i]) { return true; }
        }
        return false;
    }

    public byte[][] selectedTokens()
    {
        if (poll == null) { return new byte[0][]; }
        int count = 0;
        for (int i = 0; i < selectedOptions.length; i++)
        {
            if (selectedOptions[i]) { count++; }
        }
        byte[][] out = new byte[count][];
        int at = 0;
        for (int i = 0; i < selectedOptions.length; i++)
        {
            if (!selectedOptions[i]) { continue; }
            byte[] token = poll.options[i].option;
            byte[] copy = new byte[token.length];
            System.arraycopy(token, 0, copy, 0, token.length);
            out[at++] = copy;
        }
        return out;
    }

    public void setStatus(String value)
    {
        status = value == null ? "" : value;
        repaint();
    }

    public void moveSelection(int delta) { move(delta); }
    public void activateSelected() { toggle(); }

    protected void paint(Graphics g)
    {
        updateMetrics();
        String title = poll != null && poll.quiz ? "Quiz" : "Poll";
        UiChrome.background(g, theme, metrics);
        UiChrome.header(g, theme, metrics, font, title, headerStatus());

        int y = metrics.bodyTop;
        int rowHeight = rowHeight();
        g.setFont(font);
        g.setColor(theme.text);
        String question = poll == null ? "" : poll.question;
        g.drawString(UiChrome.clip(question == null ? "" : question, font,
                Math.max(1, metrics.width - metrics.padding * 2)),
                metrics.padding, y + metrics.padding,
                Graphics.TOP | Graphics.LEFT);
        y += rowHeight;

        int visible = visibleRows();
        ensureVisible(visible);
        for (int row = 0; row < visible; row++)
        {
            int index = top + row;
            if (index >= selectedOptions.length) { break; }
            boolean focused = index == selected;
            if (focused)
            {
                g.setColor(theme.selection);
                g.fillRect(0, y, metrics.width, rowHeight);
            }
            g.setColor(focused ? theme.selectionText
                    : (selectedOptions[index] ? theme.accent : theme.text));
            String mark = selectedOptions[index] ? "[x] " : "[ ] ";
            g.drawString(mark, metrics.padding, y + metrics.padding,
                    Graphics.TOP | Graphics.LEFT);
            int x = metrics.padding + font.stringWidth(mark);
            String option = optionText(index);
            g.drawString(UiChrome.clip(option, font,
                    Math.max(1, metrics.width - x - metrics.padding)),
                    x, y + metrics.padding, Graphics.TOP | Graphics.LEFT);
            y += rowHeight;
        }
    }

    protected void sizeChanged(int width, int height)
    {
        metrics.update(width, height, font, metaFont);
        ensureVisible(visibleRows());
    }

    protected void keyPressed(int keyCode)
    {
        int action = 0;
        try { action = getGameAction(keyCode); }
        catch (Throwable ignored) { }
        if (action == Canvas.UP || keyCode == Canvas.KEY_NUM2) { move(-1); }
        else if (action == Canvas.DOWN || keyCode == Canvas.KEY_NUM8) { move(1); }
        else if (action == Canvas.FIRE || keyCode == Canvas.KEY_NUM5) { toggle(); }
    }

    protected void keyRepeated(int keyCode) { keyPressed(keyCode); }

    private void move(int delta)
    {
        if (selectedOptions.length == 0) { return; }
        selected += delta;
        if (selected < 0) { selected = 0; }
        if (selected >= selectedOptions.length)
        {
            selected = selectedOptions.length - 1;
        }
        ensureVisible(visibleRows());
        repaint();
    }

    private void toggle()
    {
        if (poll == null || !poll.canVote() || selectedOptions.length == 0)
        {
            return;
        }
        if (poll.multipleChoice)
        {
            selectedOptions[selected] = !selectedOptions[selected];
        }
        else
        {
            for (int i = 0; i < selectedOptions.length; i++)
            {
                selectedOptions[i] = i == selected;
            }
        }
        dirty = true;
        repaint();
        notifySelectionChanged();
    }

    private void notifySelectionChanged()
    {
        SelectionListener listener = selectionListener;
        if (listener != null) { listener.onSelectionChanged(); }
    }

    private String headerStatus()
    {
        if (status.length() > 0) { return status; }
        if (poll == null) { return ""; }
        if (poll.closed) { return "closed"; }
        if (poll.revotingDisabled && poll.hasVote()) { return "voted"; }
        if (poll.totalVoters >= 0) { return poll.totalVoters + " votes"; }
        return poll.multipleChoice ? "choose answers" : "choose one";
    }

    private String optionText(int index)
    {
        PollOption option = poll.options[index];
        StringBuffer out = new StringBuffer();
        int percent = poll.percent(option);
        if (percent >= 0)
        {
            out.append(percent);
            out.append("% ");
        }
        out.append(option.text == null ? "" : option.text);
        if (poll.quiz && option.correct) { out.append(" (correct)"); }
        return out.toString();
    }

    private int visibleRows()
    {
        updateMetrics();
        int rows = metrics.bodyHeight / rowHeight() - 1;
        return rows < 1 ? 1 : rows;
    }

    private void ensureVisible(int visible)
    {
        if (selected < top) { top = selected; }
        if (selected >= top + visible) { top = selected - visible + 1; }
        if (top < 0) { top = 0; }
    }

    private int rowHeight()
    {
        return Math.max(1, metrics.lineHeight + metrics.padding * 2);
    }

    private void updateMetrics()
    {
        metrics.update(getWidth(), getHeight(), font, metaFont);
    }

    private static boolean contains(byte[][] values, byte[] wanted)
    {
        for (int i = 0; values != null && i < values.length; i++)
        {
            if (Poll.sameBytes(values[i], wanted)) { return true; }
        }
        return false;
    }
}
