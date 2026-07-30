package tg.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

import tg.api.Media;
import tg.api.Message;
import tg.api.Peer;
import tg.api.ReactionSummary;

/**
 * One conversation: messages, newest at the bottom.
 *
 * Drawn rather than built from Form items. A Form allocates an Item per line
 * and reflows the whole thing on every change; on a 320x240 screen only about
 * fifteen lines are visible at a time, so drawing just those keeps both the
 * heap and the repaint cost proportional to the screen rather than to the
 * history.
 *
 * Word wrapping is done once when the message list is set, not on every paint.
 */
public class ChatScreen extends Canvas
{
    public interface ActivationListener
    {
        void onMessageActivated(int messageId);
    }
    private static final int THUMB_CACHE = 12;

    private final Font font;
    private final Font metaFont;
    private final int lineHeight;
    private final Metrics metrics = new Metrics();
    private Theme theme;
    private int lastLayoutWidth = -1;
    private int lastThumbnailHeight = -1;

    private String title = "";
    private String[] lines = new String[0];
    private boolean[] outgoing = new boolean[0];
    private boolean[] meta = new boolean[0];
    private int[] lineMessageIds = new int[0];
    private int[] lineMessageOffsets = new int[0];
    private Message[] currentMessages = new Message[0];
    private Peer peer;
    private final int[] thumbnailIds = new int[THUMB_CACHE];
    private final Image[] thumbnails = new Image[THUMB_CACHE];
    private int thumbnailCount;

    private final ChatScrollState scroll = new ChatScrollState();
    private String status;
    private boolean mediaPreviews = true;
    private int focusedMessageId;
    private ActivationListener activationListener;

    public ChatScreen()
    {
        this(Theme.byId(Theme.LIGHT));
    }

    public ChatScreen(Theme theme)
    {
        font = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        metaFont = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        lineHeight = Math.max(font.getHeight(), EmojiText.GLYPH);
        this.theme = theme == null ? Theme.byId(Theme.LIGHT) : theme;
    }

    public void setTheme(Theme value)
    {
        theme = value == null ? Theme.byId(Theme.LIGHT) : value;
        repaint();
    }

    public void setTitle(String title)
    {
        this.title = title == null ? "" : title;
        repaint();
    }

    public void setPeer(Peer value) { peer = value; }
    public Peer peer() { return peer; }
    public Message[] messages() { return currentMessages; }

    /** Transient one-line status, e.g. "sending..." */
    public void setStatus(String status)
    {
        this.status = status;
        repaint();
    }

    public void setMediaPreviews(boolean value)
    {
        if (mediaPreviews == value) { return; }
        mediaPreviews = value;
        layoutMessages(currentMessages, true);
    }

    public void setActivationListener(ActivationListener value)
    {
        activationListener = value;
    }

    public int focusedMessageId() { return focusedMessageId; }
    public int transcriptLineCount() { return lines.length; }
    public boolean isAtEnd() { return scroll.top() >= maxTop(); }

    public void focusMessage(int messageId)
    {
        if (!containsMessage(messageId)) { return; }
        focusedMessageId = messageId;
        for (int i = 0; i < lineMessageIds.length; i++)
        {
            if (lineMessageIds[i] == messageId)
            {
                int targetTop = i - visibleLines() / 2;
                scroll.userScroll(targetTop - scroll.top(), maxTop());
                break;
            }
        }
        repaint();
    }

    /**
     * Lay out a history.
     *
     * @param messages newest first, as Telegram returns them; displayed oldest
     *                 first so the conversation reads downwards
     */
    public void setMessages(Message[] messages)
    {
        layoutMessages(messages, true);
    }

    /** Clear or replace the transcript when navigating to a different peer. */
    public void resetMessages(Message[] messages)
    {
        clearThumbnails();
        layoutMessages(messages, false);
    }

    private void layoutMessages(Message[] messages, boolean preserveScroll)
    {
        if (messages == null) { messages = new Message[0]; }
        currentMessages = messages;
        int[] oldMessageIds = lineMessageIds;
        int[] oldMessageOffsets = lineMessageOffsets;
        updateMetrics();
        int width = metrics.contentWidth;
        lastLayoutWidth = width;
        lastThumbnailHeight = metrics.thumbnailHeight;

        // Two passes: count, then fill. Growing a Vector of Strings and copying
        // it out would double the peak allocation for no benefit.
        int count = 0;
        for (int pass = 0; pass < 2; pass++)
        {
            int previousDay = 0;
            if (pass == 1)
            {
                lines = new String[count];
                outgoing = new boolean[count];
                meta = new boolean[count];
                lineMessageIds = new int[count];
                lineMessageOffsets = new int[count];
                count = 0;
            }
            for (int i = messages.length - 1; i >= 0; i--)
            {
                Message m = messages[i];
                if (m == null)
                {
                    continue;
                }
                int day = DateTime.dayKey(m.date);
                if (day != 0 && day != previousDay)
                {
                    if (pass == 1)
                    {
                        lines[count] = "-- " + DateTime.date(m.date) + " --";
                        outgoing[count] = false;
                        meta[count] = true;
                        lineMessageIds[count] = 0;
                        lineMessageOffsets[count] = 0;
                    }
                    count++;
                    previousDay = day;
                }
                String who = m.senderName();
                if (m.outgoing && m.read)
                {
                    who += " [read]";
                }
                String time = DateTime.time(m.date);
                if (time.length() > 0)
                {
                    who = who.length() == 0 ? time : (who + "  " + time);
                }
                if (who.length() > 0)
                {
                    if (pass == 1)
                    {
                        lines[count] = who;
                        outgoing[count] = m.outgoing;
                        meta[count] = true;
                        lineMessageIds[count] = m.id;
                        lineMessageOffsets[count] = 0;
                    }
                    count++;
                }
                String reply = replyLine(m, messages);
                if (reply.length() > 0)
                {
                    int replyStart = count;
                    count = wrap(reply, width, pass, count,
                            m.outgoing, m.id, 150);
                    if (pass == 1)
                    {
                        for (int line = replyStart; line < count; line++)
                        {
                            meta[line] = true;
                        }
                    }
                }
                if (m.forwarded != null && m.forwarded.label.length() > 0)
                {
                    int forwardedStart = count;
                    count = wrap(m.forwarded.label, width, pass, count,
                            m.outgoing, m.id, 100);
                    if (pass == 1)
                    {
                        for (int line = forwardedStart; line < count; line++)
                        {
                            meta[line] = true;
                        }
                    }
                }
                count = wrap(m.text, width, pass, count, m.outgoing, m.id,
                        200);
                if (m.media != null)
                {
                    count = wrap(m.media.label, width, pass, count,
                            m.outgoing, m.id, 1000);
                    if (mediaPreviews && m.media.kind == Media.PHOTO
                            && m.media.photo != null
                            && m.media.photo.stripped() != null)
                    {
                        int rows = (metrics.thumbnailHeight + lineHeight - 1)
                                / lineHeight;
                        for (int row = 0; row < rows; row++)
                        {
                            if (pass == 1)
                            {
                                lines[count] = "";
                                outgoing[count] = m.outgoing;
                                meta[count] = false;
                                lineMessageIds[count] = m.id;
                                lineMessageOffsets[count] = 1100 + row;
                            }
                            count++;
                        }
                    }
                }
                String reactionLine = reactionLine(m.reactions);
                if (reactionLine.length() > 0)
                {
                    count = wrap(reactionLine, width, pass, count,
                            m.outgoing, m.id, 2000);
                }
            }
        }
        if (!containsMessage(focusedMessageId) && messages.length > 0)
        {
            for (int i = 0; i < messages.length; i++)
            {
                if (messages[i] != null)
                {
                    focusedMessageId = messages[i].id;
                    break;
                }
            }
        }
        if (preserveScroll)
        {
            scroll.replace(oldMessageIds, oldMessageOffsets,
                    lineMessageIds, lineMessageOffsets, maxTop());
        }
        else
        {
            scroll.reset(maxTop());
        }
        repaint();
    }

    /** Append a message we just sent, before the server echoes it back. */
    public void appendLocal(String text)
    {
        updateMetrics();
        int width = metrics.contentWidth;

        int extra = 1 + countWrapped(text, width);
        String[] newLines = new String[lines.length + extra];
        boolean[] newOut = new boolean[newLines.length];
        boolean[] newMeta = new boolean[newLines.length];
        int[] newMessageIds = new int[newLines.length];
        int[] newMessageOffsets = new int[newLines.length];
        System.arraycopy(lines, 0, newLines, 0, lines.length);
        System.arraycopy(outgoing, 0, newOut, 0, outgoing.length);
        System.arraycopy(meta, 0, newMeta, 0, meta.length);
        System.arraycopy(lineMessageIds, 0, newMessageIds, 0,
                lineMessageIds.length);
        System.arraycopy(lineMessageOffsets, 0, newMessageOffsets, 0,
                lineMessageOffsets.length);

        int at = lines.length;
        newLines[at] = "You";
        newOut[at] = true;
        newMeta[at] = true;
        newMessageIds[at] = 0;
        newMessageOffsets[at] = 0;
        at++;

        lines = newLines;
        outgoing = newOut;
        meta = newMeta;
        lineMessageIds = newMessageIds;
        lineMessageOffsets = newMessageOffsets;
        wrapInto(text, width, at, true, 0, 1);
        scroll.appended(maxTop());
        repaint();
    }

    public void scrollToEnd()
    {
        scroll.reset(maxTop());
        repaint();
    }

    protected void paint(Graphics g)
    {
        updateMetrics();
        if (lastLayoutWidth != metrics.contentWidth
                || lastThumbnailHeight != metrics.thumbnailHeight)
        {
            layoutMessages(currentMessages, true);
        }
        String headerStatus = status == null
                ? "" : UiChrome.clip(status, metaFont, metrics.width / 2);
        UiChrome.background(g, theme, metrics);
        UiChrome.header(g, theme, metrics, font, title, headerStatus);
        int visible = visibleLines();

        int y = metrics.bodyTop;
        for (int i = 0; i < visible; i++)
        {
            int idx = scroll.top() + i;
            if (idx >= lines.length)
            {
                break;
            }
            if (meta[idx])
            {
                g.setFont(metaFont);
                g.setColor(theme.secondaryText);
            }
            if (lineMessageIds[idx] != 0
                    && lineMessageIds[idx] == focusedMessageId)
            {
                g.setColor(theme.selection);
                g.fillRect(0, y, metrics.width, lineHeight);
                g.setColor(meta[idx] ? theme.secondaryText
                        : (outgoing[idx] ? theme.outgoingText : theme.text));
            }
            else
            {
                g.setFont(font);
                g.setColor(outgoing[idx] ? theme.outgoingText : theme.text);
            }
            String s = lines[idx];
            int messageOffset = lineMessageOffsets[idx];
            if (messageOffset >= 1100 && messageOffset < 1200)
            {
                Image thumbnail = thumbnail(lineMessageIds[idx]);
                if (thumbnail != null)
                {
                    int clipX = g.getClipX();
                    int clipY = g.getClipY();
                    int clipWidth = g.getClipWidth();
                    int clipHeight = g.getClipHeight();
                    g.clipRect(0, y, metrics.width, lineHeight);
                    g.drawImage(thumbnail, metrics.padding,
                            y - (messageOffset - 1100) * lineHeight,
                            Graphics.TOP | Graphics.LEFT);
                    g.setClip(clipX, clipY, clipWidth, clipHeight);
                }
            }
            else if (s != null)
            {
                EmojiText.drawString(g, s, metrics.padding, y,
                        meta[idx] ? metaFont : font);
            }
            y += lineHeight;
        }
    }

    protected void sizeChanged(int width, int height)
    {
        metrics.update(width, height, font, metaFont);
        if (lastLayoutWidth != metrics.contentWidth
                || lastThumbnailHeight != metrics.thumbnailHeight)
        {
            layoutMessages(currentMessages, true);
        }
    }

    protected void keyPressed(int keyCode)
    {
        int action = 0;
        try { action = getGameAction(keyCode); }
        catch (Throwable ignored) { }

        if (action == Canvas.UP || keyCode == Canvas.KEY_NUM2)
        {
            focus(-1);
        }
        else if (action == Canvas.DOWN || keyCode == Canvas.KEY_NUM8)
        {
            focus(1);
        }
        else if (action == Canvas.LEFT || keyCode == Canvas.KEY_NUM4)
        {
            scroll(-visibleLines());
        }
        else if (action == Canvas.RIGHT || keyCode == Canvas.KEY_NUM6)
        {
            scroll(visibleLines());
        }
        else if (action == Canvas.FIRE || keyCode == Canvas.KEY_NUM5)
        {
            ActivationListener listener = activationListener;
            if (listener != null && focusedMessageId != 0)
            {
                listener.onMessageActivated(focusedMessageId);
            }
        }
    }

    protected void keyRepeated(int keyCode)
    {
        keyPressed(keyCode);
    }

    // ------------------------------------------------------------ internal

    private void scroll(int delta)
    {
        scroll.userScroll(delta, maxTop());
        repaint();
    }

    private void focus(int direction)
    {
        if (lineMessageIds.length == 0) { return; }
        int at = focusLine();
        if (at < 0) { at = direction < 0 ? lineMessageIds.length : -1; }
        int current = focusedMessageId;
        int i = at + direction;
        while (i >= 0 && i < lineMessageIds.length)
        {
            int id = lineMessageIds[i];
            if (id != 0 && id != current)
            {
                focusedMessageId = id;
                int top = scroll.top();
                int visible = visibleLines();
                int first = i;
                while (first > 0 && lineMessageIds[first - 1] == id)
                {
                    first--;
                }
                int last = i;
                while (last + 1 < lineMessageIds.length
                        && lineMessageIds[last + 1] == id)
                {
                    last++;
                }
                if (first < top)
                {
                    scroll.userScroll(first - top, maxTop());
                }
                else if (last >= top + visible)
                {
                    int messageLines = last - first + 1;
                    int target = messageLines <= visible
                            ? last - visible + 1 : first;
                    scroll.userScroll(target - top, maxTop());
                }
                repaint();
                return;
            }
            i += direction;
        }
        // There is no next message, but the focused message may have more
        // lines below the viewport (caption/media/thumbnail/reactions).
        if (direction > 0 && scroll.top() < maxTop())
        {
            scroll.userScroll(maxTop() - scroll.top(), maxTop());
            repaint();
        }
    }

    private int focusLine()
    {
        for (int i = 0; i < lineMessageIds.length; i++)
        {
            if (lineMessageIds[i] == focusedMessageId) { return i; }
        }
        return -1;
    }

    private boolean containsMessage(int id)
    {
        if (id == 0) { return false; }
        for (int i = 0; i < lineMessageIds.length; i++)
        {
            if (lineMessageIds[i] == id) { return true; }
        }
        return false;
    }

    private int visibleLines()
    {
        updateMetrics();
        return metrics.visibleLines();
    }

    private int maxTop()
    {
        int max = lines.length - visibleLines();
        return max < 0 ? 0 : max;
    }

    /**
     * Greedy word wrap. On pass 0 it only counts lines; on pass 1 it writes
     * them. Splitting an over-long word mid-way is deliberate - a URL with no
     * spaces must not vanish off the right edge.
     */
    private int wrap(String text, int width, int pass, int at,
                     boolean isOutgoing, int messageId, int messageOffset)
    {
        if (text == null || text.length() == 0)
        {
            return at;
        }
        if (pass == 0)
        {
            return at + countWrapped(text, width);
        }
        return wrapInto(text, width, at, isOutgoing, messageId, messageOffset);
    }

    private int countWrapped(String text, int width)
    {
        int count = 0;
        int start = 0;
        while (start < text.length())
        {
            int end = lineEnd(text, start, width);
            count++;
            start = skipSpace(text, end);
        }
        return count == 0 ? 1 : count;
    }

    private int wrapInto(String text, int width, int at, boolean isOutgoing,
                         int messageId, int messageOffset)
    {
        int start = 0;
        while (start < text.length() && at < lines.length)
        {
            int end = lineEnd(text, start, width);
            lines[at] = text.substring(start, end);
            outgoing[at] = isOutgoing;
            meta[at] = false;
            lineMessageIds[at] = messageId;
            lineMessageOffsets[at] = messageOffset;
            at++;
            messageOffset++;
            start = skipSpace(text, end);
        }
        return at;
    }

    /** Index one past the last character that fits on a line starting at {@code start}. */
    private int lineEnd(String text, int start, int width)
    {
        int n = text.length();
        int lastSpace = -1;
        int i = start;
        while (i < n)
        {
            char c = text.charAt(i);
            if (c == '\n')
            {
                return i;
            }
            if (c == ' ')
            {
                lastSpace = i;
            }
            int next = EmojiText.nextBoundary(text, i);
            if (EmojiText.substringWidth(text, start, next - start, font)
                    > width)
            {
                // Break at the last space if there was one, otherwise mid-word.
                return (lastSpace > start) ? lastSpace
                        : (i > start ? i : next);
            }
            i = next;
        }
        return n;
    }

    private static int skipSpace(String text, int at)
    {
        while (at < text.length() && (text.charAt(at) == ' ' || text.charAt(at) == '\n'))
        {
            at++;
        }
        return at;
    }

    private static String reactionLine(ReactionSummary[] reactions)
    {
        if (reactions == null || reactions.length == 0) { return ""; }
        StringBuffer out = new StringBuffer();
        for (int i = 0; i < reactions.length; i++)
        {
            ReactionSummary reaction = reactions[i];
            if (reaction == null || reaction.emoji == null) { continue; }
            if (out.length() > 0) { out.append(' '); }
            if (reaction.chosen) { out.append('*'); }
            out.append(reaction.emoji);
            out.append(' ');
            out.append(reaction.count);
        }
        return out.toString();
    }

    public static String replyLine(Message message, Message[] messages)
    {
        if (message == null || message.replyToMessageId <= 0) { return ""; }
        for (int i = 0; i < messages.length; i++)
        {
            Message source = messages[i];
            if (source == null || source.id != message.replyToMessageId)
            {
                continue;
            }
            String author = source.senderName();
            if (author.length() == 0) { author = "Message"; }
            String text = source.summaryText();
            int nl = text.indexOf('\n');
            if (nl >= 0) { text = text.substring(0, nl); }
            if (text.length() > 48) { text = text.substring(0, 45) + "..."; }
            return text.length() == 0 ? ("Reply to " + author)
                    : ("Reply to " + author + ": " + text);
        }
        return "Reply to #" + message.replyToMessageId;
    }

    public synchronized boolean hasThumbnail(int messageId)
    {
        return thumbnail(messageId) != null;
    }

    public int thumbnailWidth()
    {
        updateMetrics();
        return metrics.contentWidth;
    }

    public int thumbnailHeight()
    {
        updateMetrics();
        return metrics.thumbnailHeight;
    }

    public synchronized void setThumbnail(int messageId, Image image)
    {
        if (messageId == 0 || image == null) { return; }
        for (int i = 0; i < thumbnailCount; i++)
        {
            if (thumbnailIds[i] == messageId)
            {
                thumbnails[i] = image;
                repaint();
                return;
            }
        }
        if (thumbnailCount < THUMB_CACHE)
        {
            thumbnailIds[thumbnailCount] = messageId;
            thumbnails[thumbnailCount] = image;
            thumbnailCount++;
        }
        else
        {
            System.arraycopy(thumbnailIds, 1, thumbnailIds, 0,
                    THUMB_CACHE - 1);
            System.arraycopy(thumbnails, 1, thumbnails, 0,
                    THUMB_CACHE - 1);
            thumbnailIds[THUMB_CACHE - 1] = messageId;
            thumbnails[THUMB_CACHE - 1] = image;
        }
        repaint();
    }

    private synchronized Image thumbnail(int messageId)
    {
        for (int i = 0; i < thumbnailCount; i++)
        {
            if (thumbnailIds[i] == messageId) { return thumbnails[i]; }
        }
        return null;
    }

    private synchronized void clearThumbnails()
    {
        for (int i = 0; i < thumbnailCount; i++)
        {
            thumbnailIds[i] = 0;
            thumbnails[i] = null;
        }
        thumbnailCount = 0;
    }

    private void updateMetrics()
    {
        metrics.update(getWidth(), getHeight(), font, metaFont);
    }
}
