package tg.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

import tg.api.Dialog;
import tg.api.Peer;

/** Adaptive, themed two-line dialog list with explicit unread badges. */
public class DialogListScreen extends Canvas
{
    public interface ActivationListener
    {
        void onDialogActivated(Peer peer);
    }

    public interface ViewportListener
    {
        void onDialogViewportChanged();
    }

    private final Font titleFont = Font.getFont(Font.FACE_PROPORTIONAL,
            Font.STYLE_BOLD, Font.SIZE_SMALL);
    private final Font font = Font.getFont(Font.FACE_PROPORTIONAL,
            Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font metaFont = Font.getFont(Font.FACE_PROPORTIONAL,
            Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Metrics metrics = new Metrics();
    private Theme theme;
    private Dialog[] dialogs = new Dialog[0];
    private int totalCount;

    /** Position of {@code dialogs[0]} in the list as a whole. */
    private int windowStart;
    private int selected;
    private int top;
    private String connection = "";
    private String updates = "";
    private String emptyText = "(no chats)";
    private ActivationListener activationListener;
    private ViewportListener viewportListener;
    private AvatarCache avatarCache;

    public DialogListScreen(Theme theme)
    {
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

    public void setViewportListener(ViewportListener value)
    {
        viewportListener = value;
    }

    public void setAvatarCache(AvatarCache value)
    {
        avatarCache = value;
        repaint();
    }

    public void avatarsChanged() { repaint(); }

    /**
     * Install a list, anchored on a peer rather than on a row index.
     *
     * The chat list reorders under the reader: a message arriving in a chat
     * promotes it above every unpinned one, so the row a reader is looking at
     * moves whenever anyone writes to them. Following the selected <i>peer</i>
     * is what makes that survivable, and it is why this takes a Peer at all.
     *
     * Following the peer is not enough on its own. {@code ensureVisible} only
     * guarantees the selected row is somewhere on screen, so a promotion from
     * below shifts every row by one and the reader's place slides a line at a
     * time. Holding the anchored row at the same offset within the viewport
     * makes the rows above it change while it stays put, which is what actually
     * looks like nothing moved.
     *
     * @param firstRow position of {@code values[0]} in the list as a whole.
     *                 Non-zero when the reader has scrolled far enough that
     *                 rows above have been dropped from the window
     * @param allCount dialogs the list has in total, retained or not; the
     *                 header counts against it
     * @param selectedPeer row to keep, or null when this is a reset rather than
     *                     a reorder - a filter change, a fresh sign-in
     */
    public void setDialogs(Dialog[] values, int firstRow, int allCount,
                           Peer selectedPeer)
    {
        windowStart = firstRow < 0 ? 0 : firstRow;
        int anchorOffset = -1;
        if (selectedPeer != null && dialogs.length > 0)
        {
            anchorOffset = selected - top;
        }
        dialogs = values == null ? new Dialog[0] : values;
        totalCount = Math.max(dialogs.length, allCount);
        int found = find(selectedPeer);
        if (found >= 0) { selected = found; }
        else if (selected >= dialogs.length) { selected = dialogs.length - 1; }
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
        emptyText = value == null ? "(no chats)" : value;
    }

    public Peer selectedPeer()
    {
        return selected >= 0 && selected < dialogs.length
                && dialogs[selected] != null ? dialogs[selected].peer : null;
    }

    public int selectedIndex() { return selected; }
    public int topIndex() { return top; }
    public int visibleRows() { updateMetrics(); return metrics.visibleRows(); }

    /** Rows held. Exposed for the driver, like {@code ChatScreen.messageCount}. */
    public int dialogCount() { return dialogs.length; }

    /** What the header counts against: the server's total, when it gave one. */
    public int totalCount() { return totalCount; }

    /**
     * Peer on the bottom row, or null when nothing is shown.
     *
     * The one thing a fetch-on-scroll decision needs from this screen. Kept
     * here rather than derived from {@link #visiblePeers} by the caller because
     * a trailing null - a row whose dialog went missing mid-update - would
     * otherwise read as an empty list and stop paging.
     */
    public Peer lastVisiblePeer()
    {
        Peer[] shown = visiblePeers();
        for (int i = shown.length - 1; i >= 0; i--)
        {
            if (shown[i] != null) { return shown[i]; }
        }
        return null;
    }

    /** Peer on the top row - the other edge a fetch is decided from. */
    public Peer firstVisiblePeer()
    {
        Peer[] shown = visiblePeers();
        for (int i = 0; i < shown.length; i++)
        {
            if (shown[i] != null) { return shown[i]; }
        }
        return null;
    }

    /** Position of the window in the list as a whole. */
    public int windowStart() { return windowStart; }

    /**
     * What the header says about the window, composed by the caller.
     *
     * The screen knows how many rows it holds and nothing about what they are a
     * window onto - whether a filter is narrowing them, whether the ordering
     * has gone stale, what the server said the total was. Handing it the
     * finished string keeps that policy in one place instead of reconstructing
     * it here from four fields.
     */
    public void setWindowLabel(String label)
    {
        windowLabel = label == null ? "" : label;
        repaint();
    }

    private String windowLabel = "";

    public int avatarSize()
    {
        updateMetrics();
        return Math.min(metrics.iconSize,
                metrics.rowHeight - metrics.padding * 2);
    }

    /** Stable peers currently worth loading, in visual order. */
    public Peer[] visiblePeers()
    {
        updateMetrics();
        ensureVisible();
        int count = Math.min(metrics.visibleRows(), dialogs.length - top);
        if (count < 0) { count = 0; }
        Peer[] peers = new Peer[count];
        for (int i = 0; i < count; i++)
        {
            Dialog dialog = dialogs[top + i];
            peers[i] = dialog == null ? null : dialog.peer;
        }
        return peers;
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
        // Where the reader is, not how much is held. The retained count used
        // to be both, back when the list was everything that had been loaded;
        // with a window it would report the window size and sit at "500/1690"
        // whether the reader was at row 500 or row 1500.
        // The window, not the cursor. "912/1690" answered where the reader
        // was and left them to guess how much of the list was actually here,
        // which is the question that matters once Filter and the restore stack
        // only see what is loaded.
        String count = windowLabel.length() > 0 ? windowLabel
                : (dialogs.length == 0 ? ("0/" + totalCount)
                        : ((windowStart + selected + 1) + "/" + totalCount));
        String state = connection;
        if (updates.length() > 0) { state += "/" + updates; }
        UiChrome.header(g, theme, metrics, titleFont,
                "Chats " + count, state);

        int visible = metrics.visibleRows();
        ensureVisible();
        int y = metrics.bodyTop;
        if (dialogs.length == 0)
        {
            g.setColor(theme.secondaryText);
            g.setFont(font);
            g.drawString(emptyText, metrics.padding,
                    y + metrics.padding, Graphics.TOP | Graphics.LEFT);
        }
        for (int row = 0; row < visible; row++)
        {
            int index = top + row;
            if (index >= dialogs.length) { break; }
            Dialog dialog = dialogs[index];
            if (dialog == null) { continue; }
            boolean focused = index == selected;
            if (focused)
            {
                g.setColor(theme.selection);
                g.fillRect(0, y, metrics.width, metrics.rowHeight);
            }
            int primary = focused ? theme.selectionText : theme.text;
            int secondary = focused ? theme.selectionText
                    : theme.secondaryText;
            int icon = Math.min(metrics.iconSize,
                    metrics.rowHeight - metrics.padding * 2);
            int iconY = y + (metrics.rowHeight - icon) / 2;
            Image avatar = avatarCache == null
                    ? null : avatarCache.get(dialog.peer);
            if (avatar == null)
            {
                Icons.peer(g, dialog.peer, metrics.padding, iconY, icon, primary);
            }
            else
            {
                int avatarX = metrics.padding + (icon - avatar.getWidth()) / 2;
                int avatarY = iconY + (icon - avatar.getHeight()) / 2;
                g.drawImage(avatar, avatarX, avatarY,
                        Graphics.TOP | Graphics.LEFT);
                g.setColor(focused ? theme.selectionText : theme.border);
                g.drawRect(metrics.padding, iconY, icon - 1, icon - 1);
            }
            int textX = metrics.padding * 2 + icon;
            int right = metrics.width - metrics.padding;
            String date = DateTime.compact(dialog.date);
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
            if (dialog.pinned)
            {
                int pinSize = Math.max(4, metrics.lineHeight / 2);
                Icons.pin(g, titleRight - pinSize, y + metrics.padding,
                        pinSize, secondary);
                titleRight -= pinSize + metrics.padding;
            }
            g.setFont(titleFont);
            g.setColor(primary);
            g.drawString(UiChrome.clip(dialog.title(), titleFont,
                    Math.max(1, titleRight - textX)), textX,
                    y + metrics.padding, Graphics.TOP | Graphics.LEFT);

            String preview = dialog.preview();
            int previewY = y + metrics.padding * 2 + metrics.lineHeight;
            int badgeWidth = 0;
            String unread = "";
            if (dialog.unreadCount > 0)
            {
                unread = dialog.unreadCount > 999 ? "999+"
                        : String.valueOf(dialog.unreadCount);
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
            Peer peer = selectedPeer();
            if (listener != null && peer != null)
            {
                listener.onDialogActivated(peer);
            }
        }
    }

    protected void keyRepeated(int keyCode) { keyPressed(keyCode); }

    private void move(int delta)
    {
        if (dialogs.length == 0) { return; }
        selected += delta;
        if (selected < 0) { selected = 0; }
        if (selected >= dialogs.length) { selected = dialogs.length - 1; }
        ensureVisible();
        repaint();
        viewportChanged();
    }

    private void ensureVisible()
    {
        // Metrics first. This used to run against whatever the last paint left
        // behind, which self-corrected on the next one and so never showed -
        // until the scroll position started deciding when to fetch a page.
        // Clamping `top` against a row count from before the screen had a size
        // puts the bottom row somewhere it is not, and the fetch margin is
        // measured from the bottom row. The update is arithmetic on two ints.
        updateMetrics();
        int visible = metrics.visibleRows();
        if (selected < top) { top = selected; }
        if (selected >= top + visible) { top = selected - visible + 1; }
        int max = dialogs.length - visible;
        if (max < 0) { max = 0; }
        if (top > max) { top = max; }
        if (top < 0) { top = 0; }
    }

    private int find(Peer peer)
    {
        if (peer == null) { return -1; }
        for (int i = 0; i < dialogs.length; i++)
        {
            Peer candidate = dialogs[i] == null ? null : dialogs[i].peer;
            if (candidate != null && candidate.kind == peer.kind
                    && candidate.id == peer.id) { return i; }
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
        if (listener != null) { listener.onDialogViewportChanged(); }
    }
}
