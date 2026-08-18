package tg.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

import tg.api.ForumTopic;
import tg.api.Peer;

/**
 * A forum's topic list: the {@link DialogListScreen} shape one screen down.
 *
 * The same two-line rows, badges and window header, minus the avatars - a
 * topic has a custom-emoji icon this client deliberately does not fetch, so a
 * row is its title. Selection is anchored on the topic id the way the chat
 * list anchors on a peer, because a live message moves rows under the reader.
 */
public class TopicListScreen extends Canvas
{
    public interface ActivationListener
    {
        void onTopicActivated(ForumTopic topic);
    }

    public interface ViewportListener
    {
        void onTopicViewportChanged();
    }

    private final Font titleFont = Font.getFont(Font.FACE_PROPORTIONAL,
            Font.STYLE_BOLD, Font.SIZE_SMALL);
    private final Font font = Font.getFont(Font.FACE_PROPORTIONAL,
            Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font metaFont = Font.getFont(Font.FACE_PROPORTIONAL,
            Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Metrics metrics = new Metrics();
    private Theme theme;

    /** The forum these topics belong to; what restoreScreen rebinds from. */
    private final Peer peer;

    private ForumTopic[] topics = new ForumTopic[0];
    private int totalCount;

    /** Position of {@code topics[0]} in the list as a whole. */
    private int windowStart;
    private int selected;
    private int top;
    private String connection = "";
    private String updates = "";
    private String emptyText = "(no topics)";
    private String windowLabel = "";
    private ActivationListener activationListener;
    private ViewportListener viewportListener;

    public TopicListScreen(Theme theme, Peer peer)
    {
        this.theme = theme == null ? Theme.byId(Theme.LIGHT) : theme;
        this.peer = peer;
    }

    public Peer peer() { return peer; }

    public void setTheme(Theme value)
    {
        theme = value == null ? Theme.byId(Theme.LIGHT) : value;
        repaint();
    }

    public void setActivationListener(ActivationListener value)
    {
        activationListener = value;
    }

    public void setViewportListener(ViewportListener value)
    {
        viewportListener = value;
    }

    /**
     * Install the window, anchored on a topic id rather than a row index -
     * an arriving message promotes its topic and the reader's place has to
     * survive that, the same reason the chat list anchors on a peer.
     *
     * @param firstRow position of {@code values[0]} in the list as a whole
     * @param allCount topics the forum has in total; the header counts
     *                 against it
     * @param selectedTopicId row to keep, or 0 for a reset
     */
    public void setTopics(ForumTopic[] values, int firstRow, int allCount,
                          int selectedTopicId)
    {
        windowStart = firstRow < 0 ? 0 : firstRow;
        int anchorOffset = -1;
        if (selectedTopicId > 0 && topics.length > 0)
        {
            anchorOffset = selected - top;
        }
        topics = values == null ? new ForumTopic[0] : values;
        totalCount = Math.max(topics.length, allCount);
        int found = find(selectedTopicId);
        if (found >= 0) { selected = found; }
        else if (selected >= topics.length) { selected = topics.length - 1; }
        if (selected < 0) { selected = 0; }
        if (found >= 0 && anchorOffset >= 0)
        {
            top = selected - anchorOffset;
            if (top < 0) { top = 0; }
        }
        ensureVisible();
        repaint();
        viewportChanged();
    }

    public void setStatus(String connection, String updates)
    {
        this.connection = connection == null ? "" : connection;
        this.updates = updates == null ? "" : updates;
        repaint();
    }

    public void setEmptyText(String value)
    {
        emptyText = value == null ? "(no topics)" : value;
    }

    /** See {@code DialogListScreen.setWindowLabel}: policy stays the caller's. */
    public void setWindowLabel(String label)
    {
        windowLabel = label == null ? "" : label;
        repaint();
    }

    public ForumTopic selectedTopic()
    {
        return selected >= 0 && selected < topics.length
                ? topics[selected] : null;
    }

    public int selectedIndex() { return selected; }
    public int topIndex() { return top; }
    public int visibleRows() { updateMetrics(); return metrics.visibleRows(); }

    /** Rows held. Exposed for the driver, like {@code dialogCount}. */
    public int topicCount() { return topics.length; }

    public int totalCount() { return totalCount; }

    /** Position of the window in the list as a whole. */
    public int windowStart() { return windowStart; }

    /** Index of the bottom visible row - the edge a fetch is decided from. */
    public int lastVisibleIndex()
    {
        updateMetrics();
        ensureVisible();
        int last = top + metrics.visibleRows() - 1;
        return last >= topics.length ? topics.length - 1 : last;
    }

    /** Index of the top visible row. */
    public int firstVisibleIndex()
    {
        updateMetrics();
        ensureVisible();
        return topics.length == 0 ? -1 : top;
    }

    protected void sizeChanged(int width, int height)
    {
        metrics.update(width, height, font, metaFont);
        ensureVisible();
        viewportChanged();
    }

    protected void paint(Graphics g)
    {
        updateMetrics();
        UiChrome.background(g, theme, metrics);
        String count = windowLabel.length() > 0 ? windowLabel
                : (topics.length == 0 ? ("0/" + totalCount)
                        : ((windowStart + selected + 1) + "/" + totalCount));
        String state = connection;
        if (updates.length() > 0) { state += "/" + updates; }
        UiChrome.header(g, theme, metrics, titleFont,
                "Topics " + count, state);

        int visible = metrics.visibleRows();
        ensureVisible();
        int y = metrics.bodyTop;
        if (topics.length == 0)
        {
            g.setColor(theme.secondaryText);
            g.setFont(font);
            g.drawString(emptyText, metrics.padding,
                    y + metrics.padding, Graphics.TOP | Graphics.LEFT);
        }
        for (int row = 0; row < visible; row++)
        {
            int index = top + row;
            if (index >= topics.length) { break; }
            ForumTopic topic = topics[index];
            if (topic == null) { continue; }
            boolean focused = index == selected;
            if (focused)
            {
                g.setColor(theme.selection);
                g.fillRect(0, y, metrics.width, metrics.rowHeight);
            }
            int primary = focused ? theme.selectionText : theme.text;
            int secondary = focused ? theme.selectionText
                    : theme.secondaryText;
            int textX = metrics.padding;
            int right = metrics.width - metrics.padding;
            String date = DateTime.compact(topic.lastDate);
            g.setFont(metaFont);
            int dateWidth = metaFont.stringWidth(date);
            if (date.length() > 0)
            {
                g.setColor(secondary);
                g.drawString(date, right, y + metrics.padding,
                        Graphics.TOP | Graphics.RIGHT);
            }
            int titleRight = right - (dateWidth == 0
                    ? 0 : dateWidth + metrics.padding);
            if (topic.pinned)
            {
                int pinSize = Math.max(4, metrics.lineHeight / 2);
                Icons.pin(g, titleRight - pinSize, y + metrics.padding,
                        pinSize, secondary);
                titleRight -= pinSize + metrics.padding;
            }
            String title = topic.title;
            if (topic.closed) { title += " [closed]"; }
            if (topic.hidden) { title += " [hidden]"; }
            g.setFont(titleFont);
            g.setColor(primary);
            g.drawString(UiChrome.clip(title, titleFont,
                    Math.max(1, titleRight - textX)), textX,
                    y + metrics.padding, Graphics.TOP | Graphics.LEFT);

            String preview = topic.preview();
            int previewY = y + metrics.padding * 2 + metrics.lineHeight;
            int badgeWidth = 0;
            String unread = "";
            if (topic.unreadCount > 0)
            {
                unread = topic.unreadCount > 999 ? "999+"
                        : String.valueOf(topic.unreadCount);
                badgeWidth = metaFont.stringWidth(unread)
                        + metrics.padding * 2;
            }
            g.setFont(metaFont);
            g.setColor(secondary);
            g.drawString(UiChrome.clip(preview, metaFont,
                    Math.max(1, right - textX - badgeWidth
                            - (badgeWidth == 0 ? 0 : metrics.padding))),
                    textX, previewY, Graphics.TOP | Graphics.LEFT);
            if (badgeWidth > 0)
            {
                int badgeHeight = metaFont.getHeight() + metrics.padding;
                int badgeY = previewY;
                g.setColor(theme.badge);
                g.fillRoundRect(right - badgeWidth, badgeY, badgeWidth,
                        badgeHeight, badgeHeight, badgeHeight);
                g.setColor(theme.badgeText);
                g.drawString(unread, right - badgeWidth / 2,
                        badgeY + metrics.padding / 2,
                        Graphics.TOP | Graphics.HCENTER);
            }
            g.setColor(theme.border);
            g.drawLine(textX, y + metrics.rowHeight - 1,
                    metrics.width - 1, y + metrics.rowHeight - 1);
            y += metrics.rowHeight;
        }
    }

    protected void keyPressed(int keyCode)
    {
        int action = 0;
        try { action = getGameAction(keyCode); }
        catch (Throwable ignored) { }
        if (action == UP || keyCode == KEY_NUM2) { move(-1); }
        else if (action == DOWN || keyCode == KEY_NUM8) { move(1); }
        else if (action == LEFT || keyCode == KEY_NUM4)
        {
            move(-visibleRows());
        }
        else if (action == RIGHT || keyCode == KEY_NUM6)
        {
            move(visibleRows());
        }
        else if (action == FIRE || keyCode == KEY_NUM5)
        {
            ActivationListener listener = activationListener;
            ForumTopic topic = selectedTopic();
            if (listener != null && topic != null)
            {
                listener.onTopicActivated(topic);
            }
        }
    }

    protected void keyRepeated(int keyCode) { keyPressed(keyCode); }

    private void move(int delta)
    {
        if (topics.length == 0) { return; }
        selected += delta;
        if (selected < 0) { selected = 0; }
        if (selected >= topics.length) { selected = topics.length - 1; }
        ensureVisible();
        repaint();
        viewportChanged();
    }

    private void ensureVisible()
    {
        // Metrics first; see DialogListScreen.ensureVisible for why.
        updateMetrics();
        int visible = metrics.visibleRows();
        if (selected < top) { top = selected; }
        if (selected >= top + visible) { top = selected - visible + 1; }
        int max = topics.length - visible;
        if (max < 0) { max = 0; }
        if (top > max) { top = max; }
        if (top < 0) { top = 0; }
    }

    private int find(int topicId)
    {
        if (topicId <= 0) { return -1; }
        for (int i = 0; i < topics.length; i++)
        {
            if (topics[i] != null && topics[i].id == topicId) { return i; }
        }
        return -1;
    }

    private void updateMetrics()
    {
        metrics.update(getWidth(), getHeight(), font, metaFont);
    }

    private void viewportChanged()
    {
        ViewportListener listener = viewportListener;
        if (listener != null) { listener.onTopicViewportChanged(); }
    }
}
