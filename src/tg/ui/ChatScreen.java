package tg.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

import tg.api.Media;
import tg.api.Message;
import tg.api.Peer;
import tg.api.ReactionSummary;
import tg.mem.MemoryBudget;

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
 *
 * <h3>Only part of the transcript is laid out</h3>
 * The screen holds every retained {@link Message} - a reply needs to be able to
 * quote a message that is nowhere near the viewport - but wraps only the ones
 * within {@link MemoryBudget#layoutWindowScreens()} screens either side of it.
 * Everything laid out costs five parallel arrays keyed by display line plus a
 * String per line, so before this the cost was proportional to how far back the
 * user had ever read. Now it is proportional to the screen.
 *
 * The window is rebuilt when the viewport comes within one screen of an edge of
 * it, which leaves two screens of scrolling before the next rebuild can be
 * provoked. That gap is deliberate: it is what stops a reader moving back and
 * forth across a boundary from reflowing on every keypress.
 */
public class ChatScreen extends Canvas
{
    public interface ActivationListener
    {
        void onMessageActivated(int messageId);
    }

    /**
     * Told whenever the viewport moves, so somebody else can decide whether
     * more history is needed. Deliberately carries nothing: the listener asks
     * the screen what it wants to know, exactly as
     * {@link DialogListScreen.ViewportListener} does for avatars.
     */
    public interface ViewportListener
    {
        void onChatViewportChanged();
    }

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
    private final int thumbnailCapacity;
    private final int[] thumbnailIds;
    private final Image[] thumbnails;
    private int thumbnailCount;

    private final ChatScrollState scroll = new ChatScrollState();
    private String status;
    private int newMessageCount;

    /**
     * The status line, for a driver that has to assert on what it says.
     *
     * The line is how every asynchronous history action reports itself - which
     * page is loading, which one was refused, whether a jump landed - and on a
     * MIDlet there is nowhere else for a test to read that from.
     */
    public String status() { return displayStatus(); }
    private boolean mediaPreviews = true;
    private int focusedMessageId;
    private ActivationListener activationListener;
    private ViewportListener viewportListener;

    /**
     * Inclusive bounds of the laid-out slice of {@link #currentMessages}, which
     * is newest-first: {@code windowFirst} is the newest message wrapped and
     * {@code windowLast} the oldest. An empty transcript leaves the window
     * empty rather than degenerate, hence the -1.
     */
    private int windowFirst;
    private int windowLast = -1;

    /** Message the window is built around - the one at the top of the viewport. */
    private int anchorMessageId;

    private int layoutCount;

    private final int windowScreens;

    public ChatScreen()
    {
        this(Theme.byId(Theme.LIGHT));
    }

    public ChatScreen(Theme theme)
    {
        this(theme, MemoryBudget.thumbnailCacheEntries(),
                MemoryBudget.layoutWindowScreens());
    }

    /**
     * @param thumbnailCapacity decoded inline thumbnails to retain; floored at
     *                          two, because a single slot is evicted by the
     *                          next message that scrolls into view
     * @param windowScreens     screens of wrapped transcript kept either side
     *                          of the viewport; floored at one, which is the
     *                          least that can fill the screen at all
     */
    public ChatScreen(Theme theme, int thumbnailCapacity, int windowScreens)
    {
        font = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        metaFont = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        lineHeight = Math.max(font.getHeight(), EmojiText.GLYPH);
        this.theme = theme == null ? Theme.byId(Theme.LIGHT) : theme;
        if (thumbnailCapacity < 2) { thumbnailCapacity = 2; }
        this.thumbnailCapacity = thumbnailCapacity;
        this.thumbnailIds = new int[thumbnailCapacity];
        this.thumbnails = new Image[thumbnailCapacity];
        this.windowScreens = windowScreens < 1 ? 1 : windowScreens;
    }

    /** Decoded thumbnails this screen will hold. */
    public int thumbnailCapacity() { return thumbnailCapacity; }

    /** Screens of wrapped transcript this screen keeps either side of the viewport. */
    public int windowScreens() { return windowScreens; }

    /**
     * How many times the transcript has been wrapped.
     *
     * Reported rather than inferred because the number is the only way to see
     * whether the window is doing its job: a reader moving back and forth
     * across an eviction boundary should reflow once, not once per keypress,
     * and nothing else on the screen shows the difference.
     */
    public int layoutCount() { return layoutCount; }

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

    /** New live messages retained below a reader who is viewing older text. */
    public void setNewMessageCount(int count)
    {
        newMessageCount = count > 0 ? count : 0;
        repaint();
    }

    public int newMessageCount() { return newMessageCount; }

    public void setMediaPreviews(boolean value)
    {
        if (mediaPreviews == value) { return; }
        mediaPreviews = value;
        layoutMessages(currentMessages, true);
        viewportChanged();
    }

    public void setActivationListener(ActivationListener value)
    {
        activationListener = value;
    }

    public void setViewportListener(ViewportListener value)
    {
        viewportListener = value;
    }

    public int focusedMessageId() { return focusedMessageId; }

    /** Display lines currently laid out. Bounded by the window, not the history. */
    public int transcriptLineCount() { return lines.length; }

    /** Retained messages, laid out or not. */
    public int messageCount() { return currentMessages.length; }

    /**
     * At the newest message, rather than merely at the bottom of the window.
     * New content only follows the viewport when both are true.
     */
    public boolean isAtEnd()
    {
        return windowFirst == 0 && scroll.top() >= maxTop();
    }

    /** Id of the message at the top of the viewport, or 0 when there is none. */
    public int topVisibleMessageId()
    {
        int top = scroll.top();
        for (int i = top; i < lineMessageIds.length; i++)
        {
            if (lineMessageIds[i] != 0) { return lineMessageIds[i]; }
        }
        for (int i = Math.min(top, lineMessageIds.length) - 1; i >= 0; i--)
        {
            if (lineMessageIds[i] != 0) { return lineMessageIds[i]; }
        }
        return 0;
    }

    /**
     * Retained messages older than the top of the viewport.
     *
     * This is what decides when another page is worth asking for, and it counts
     * messages rather than lines on purpose: lines beyond the window have not
     * been wrapped and their count is not known without doing the work.
     */
    public int messagesOlderThanViewport()
    {
        if (currentMessages.length == 0) { return 0; }
        int at = messageIndex(topVisibleMessageId());
        if (at < 0) { return currentMessages.length; }
        return currentMessages.length - 1 - at;
    }

    /**
     * Retained messages newer than the top of the viewport.
     *
     * The mirror of {@link #messagesOlderThanViewport}, and it exists for the
     * same reason: a reader who has gone far enough back has pushed the newest
     * messages out of the retained set, and coming forward again has to be able
     * to notice that before running out of transcript.
     */
    public int messagesNewerThanViewport()
    {
        if (currentMessages.length == 0) { return 0; }
        int at = messageIndex(topVisibleMessageId());
        return at < 0 ? currentMessages.length : at;
    }

    /**
     * Messages on or near the screen, newest first.
     *
     * The band is one screen either side of the viewport rather than exactly
     * what is visible, so that whatever is decoded for them - inline thumbnails
     * - is ready by the time it scrolls in.
     */
    public Message[] visibleMessages()
    {
        int visible = visibleLines();
        int from = scroll.top() - visible;
        int to = scroll.top() + visible * 2;
        if (from < 0) { from = 0; }
        if (to > lineMessageIds.length) { to = lineMessageIds.length; }

        Message[] found = new Message[to - from < 0 ? 0 : to - from];
        int count = 0;
        int previousId = 0;
        for (int i = from; i < to; i++)
        {
            int id = lineMessageIds[i];
            if (id == 0 || id == previousId) { continue; }
            previousId = id;
            int at = messageIndex(id);
            if (at >= 0) { found[count++] = currentMessages[at]; }
        }
        Message[] out = new Message[count];
        System.arraycopy(found, 0, out, 0, count);
        return out;
    }

    public void focusMessage(int messageId)
    {
        if (messageIndex(messageId) < 0) { return; }
        focusedMessageId = messageId;
        ensureLaidOut(messageId);
        for (int i = 0; i < lineMessageIds.length; i++)
        {
            if (lineMessageIds[i] == messageId)
            {
                int targetTop = i - visibleLines() / 2;
                scroll.userScroll(targetTop - scroll.top(), maxTop());
                settleFollowState();
                break;
            }
        }
        repaint();
        viewportChanged();
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
        viewportChanged();
    }

    /** Clear or replace the transcript when navigating to a different peer. */
    public void resetMessages(Message[] messages)
    {
        clearThumbnails();
        anchorMessageId = 0;
        layoutMessages(messages, false);
        viewportChanged();
    }

    private void layoutMessages(Message[] messages, boolean preserveScroll)
    {
        if (messages == null) { messages = new Message[0]; }
        layoutCount++;
        currentMessages = messages;
        int[] oldMessageIds = lineMessageIds;
        int[] oldMessageOffsets = lineMessageOffsets;
        updateMetrics();
        int width = metrics.contentWidth;
        lastLayoutWidth = width;
        lastThumbnailHeight = metrics.thumbnailHeight;
        chooseWindow(messages, width);

        // Two passes: count, then fill. Growing a Vector of Strings and copying
        // it out would double the peak allocation for no benefit. Both are now
        // bounded to the window, so what used to be the whole transcript twice
        // is a few screens twice.
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
            for (int i = windowLast; i >= windowFirst; i--)
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
                String who = senderLine(m);
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
                if (m.editDate > 0)
                {
                    count = wrap("edited", width, pass, count, m.outgoing,
                            m.id, 195);
                    if (pass == 1) { meta[count - 1] = true; }
                }
                count = wrap(m.text, width, pass, count, m.outgoing, m.id,
                        200);
                if (m.media != null)
                {
                    count = wrap(m.media.label, width, pass, count,
                            m.outgoing, m.id, 1000);
                    {
                        int rows = thumbnailRows(m);
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
        if (messageIndex(focusedMessageId) < 0 && messages.length > 0)
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
        settleFollowState();
        anchorMessageId = topVisibleMessageId();
        repaint();
    }

    /**
     * Re-decide whether new content should pull the viewport with it.
     *
     * "At the end" has to mean the end of the conversation rather than the
     * bottom of the window, or paging down in the middle of the history would
     * arm the follow flag and the next rebuild would yank the reader to the
     * newest message. When the window does reach the newest, asking
     * {@code userScroll} for a zero move re-evaluates the flag honestly instead
     * of latching it off.
     */
    private void settleFollowState()
    {
        if (windowFirst > 0) { scroll.stopFollowingEnd(); }
        else { scroll.userScroll(0, maxTop()); }
    }

    /**
     * Decide which slice of the transcript to wrap.
     *
     * Walks outward from the anchor, wrapping each message only far enough to
     * count its lines, and stops on each side once a screen budget is covered.
     * The cost is therefore set by the window, never by how much history is
     * retained - which is the entire point.
     */
    private void chooseWindow(Message[] messages, int width)
    {
        if (messages.length == 0)
        {
            windowFirst = 0;
            windowLast = -1;
            return;
        }
        int anchor = anchorIndex(messages);
        int budget = windowScreens * Math.max(1, visibleLines());

        int first = anchor;
        for (int newer = 0; first > 0 && newer < budget; )
        {
            first--;
            newer += messageLines(messages[first], width);
        }
        int last = anchor;
        for (int older = 0; last + 1 < messages.length && older < budget; )
        {
            last++;
            older += messageLines(messages[last], width);
        }
        windowFirst = first;
        windowLast = last;
    }

    /**
     * Index of the message the window should be built around.
     *
     * Falling back to the newest when the anchor is gone is a safety net rather
     * than a normal path: the retained set is trimmed around the same anchor, so
     * the message being read is the one thing eviction is not allowed to take.
     */
    private int anchorIndex(Message[] messages)
    {
        if (scroll.followsEnd() || anchorMessageId == 0) { return 0; }
        for (int i = 0; i < messages.length; i++)
        {
            if (messages[i] != null && messages[i].id == anchorMessageId)
            {
                return i;
            }
        }
        return 0;
    }

    /**
     * Display lines one message occupies, excluding any date separator.
     *
     * Used only to size the window; the array is sized by the exact counting
     * pass, so a separator this does not know about cannot make the layout
     * disagree with itself.
     */
    private int messageLines(Message m, int width)
    {
        if (m == null) { return 0; }
        int count = 0;
        if (senderLine(m).length() > 0) { count++; }
        String reply = replyLine(m, currentMessages);
        if (reply.length() > 0) { count += countWrapped(reply, width); }
        if (m.forwarded != null && m.forwarded.label.length() > 0)
        {
            count += countWrapped(m.forwarded.label, width);
        }
        if (m.text != null && m.text.length() > 0)
        {
            count += countWrapped(m.text, width);
        }
        if (m.media != null)
        {
            if (m.media.label != null && m.media.label.length() > 0)
            {
                count += countWrapped(m.media.label, width);
            }
            count += thumbnailRows(m);
        }
        String reactions = reactionLine(m.reactions);
        if (reactions.length() > 0) { count += countWrapped(reactions, width); }
        return count == 0 ? 1 : count;
    }

    private int thumbnailRows(Message m)
    {
        if (!mediaPreviews || m.media == null || m.media.kind != Media.PHOTO
                || m.media.photo == null || m.media.photo.stripped() == null)
        {
            return 0;
        }
        return (metrics.thumbnailHeight + lineHeight - 1) / lineHeight;
    }

    private String senderLine(Message m)
    {
        String who = m.senderName();
        if (m.outgoing && m.read) { who += " [read]"; }
        String time = DateTime.time(m.date);
        if (time.length() > 0)
        {
            who = who.length() == 0 ? time : (who + "  " + time);
        }
        return who;
    }

    /** Append a message we just sent, before the server echoes it back. */
    public void appendLocal(String text)
    {
        // The appended lines belong after the newest message, so the window has
        // to be there. Somebody who just sent something wants to see it.
        if (windowFirst > 0) { jumpToNewest(); }
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
        jumpToNewest();
        viewportChanged();
    }

    /** Put the window back on the newest message and follow it again. */
    private void jumpToNewest()
    {
        anchorMessageId = 0;
        scroll.reset(maxTop());
        layoutMessages(currentMessages, true);
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
        String headerStatus = UiChrome.clip(displayStatus(), metaFont,
                metrics.width / 2);
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

    private String displayStatus()
    {
        String shown = status == null ? "" : status;
        if (newMessageCount > 0)
        {
            shown += (shown.length() == 0 ? "" : " ") + "+"
                    + newMessageCount + " new";
        }
        return shown;
    }

    protected void sizeChanged(int width, int height)
    {
        metrics.update(width, height, font, metaFont);
        if (lastLayoutWidth != metrics.contentWidth
                || lastThumbnailHeight != metrics.thumbnailHeight)
        {
            layoutMessages(currentMessages, true);
        }
        viewportChanged();
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
        settleFollowState();
        reflowIfNearWindowEdge();
        repaint();
        viewportChanged();
    }

    /**
     * Rebuild the window when the viewport gets within a screen of an edge of
     * it and there is retained history on the far side.
     *
     * The margin is one screen and the window is {@link #windowScreens} screens
     * deep either side, so a rebuild leaves the viewport at least two screens
     * from both edges. Moving back and forth across an eviction boundary
     * therefore cannot provoke a second rebuild without real scrolling in
     * between - which is what keeps eviction from turning into a fetch storm.
     */
    private boolean reflowIfNearWindowEdge()
    {
        if (currentMessages.length == 0) { return false; }
        int margin = visibleLines();
        boolean nearOldest = scroll.top() <= margin
                && windowLast < currentMessages.length - 1;
        boolean nearNewest = scroll.top() >= maxTop() - margin && windowFirst > 0;
        if (!nearOldest && !nearNewest) { return false; }

        int anchor = topVisibleMessageId();
        if (anchor == 0) { return false; }
        anchorMessageId = anchor;
        layoutMessages(currentMessages, true);
        return true;
    }

    /** Bring one message into the laid-out window if it is not already there. */
    private void ensureLaidOut(int messageId)
    {
        if (messageId == 0 || lineIndex(messageId) >= 0) { return; }
        if (messageIndex(messageId) < 0) { return; }
        anchorMessageId = messageId;
        // Jumping to a named message is leaving the end, and the window has to
        // be allowed to build somewhere other than the newest to get there.
        // settleFollowState turns following back on if the jump lands at the
        // end anyway.
        scroll.stopFollowingEnd();
        layoutMessages(currentMessages, true);
    }

    /**
     * Move focus one message.
     *
     * Walks the retained messages rather than the laid-out lines, so reaching
     * the edge of the window steps into the next message instead of stopping at
     * a boundary the reader cannot see.
     */
    private void focus(int direction)
    {
        if (currentMessages.length == 0) { return; }
        // Display order is oldest-first and currentMessages is newest-first, so
        // moving down the screen means moving back through the array.
        int step = -direction;
        int at = messageIndex(focusedMessageId);
        int i = at < 0 ? (step > 0 ? 0 : currentMessages.length - 1) : at + step;
        while (i >= 0 && i < currentMessages.length)
        {
            Message candidate = currentMessages[i];
            if (candidate != null && candidate.id != 0
                    && candidate.id != focusedMessageId)
            {
                focusedMessageId = candidate.id;
                ensureLaidOut(focusedMessageId);
                scrollFocusedIntoView();
                repaint();
                viewportChanged();
                return;
            }
            i += step;
        }
        // There is no next message, but the focused message may have more
        // lines below the viewport (caption/media/thumbnail/reactions).
        if (direction > 0 && scroll.top() < maxTop())
        {
            scroll.userScroll(maxTop() - scroll.top(), maxTop());
            repaint();
            viewportChanged();
        }
    }

    /** Minimal scroll that puts the focused message on screen. */
    private void scrollFocusedIntoView()
    {
        int i = lineIndex(focusedMessageId);
        if (i < 0) { return; }
        int top = scroll.top();
        int visible = visibleLines();
        int first = i;
        while (first > 0 && lineMessageIds[first - 1] == focusedMessageId)
        {
            first--;
        }
        int last = i;
        while (last + 1 < lineMessageIds.length
                && lineMessageIds[last + 1] == focusedMessageId)
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
            int target = messageLines <= visible ? last - visible + 1 : first;
            scroll.userScroll(target - top, maxTop());
        }
        settleFollowState();
    }

    /** First laid-out line of a message, or -1 when it is outside the window. */
    private int lineIndex(int id)
    {
        if (id == 0) { return -1; }
        for (int i = 0; i < lineMessageIds.length; i++)
        {
            if (lineMessageIds[i] == id) { return i; }
        }
        return -1;
    }

    /** Index in the retained transcript, laid out or not, or -1. */
    private int messageIndex(int id)
    {
        if (id == 0) { return -1; }
        for (int i = 0; i < currentMessages.length; i++)
        {
            if (currentMessages[i] != null && currentMessages[i].id == id)
            {
                return i;
            }
        }
        return -1;
    }

    private void viewportChanged()
    {
        ViewportListener listener = viewportListener;
        if (listener != null) { listener.onChatViewportChanged(); }
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

    /**
     * Index one past the last character that fits on a line starting at
     * {@code start}.
     *
     * Three passes over the line at most, each linear: find the hard break,
     * measure forward until the width runs out, then look back for a space to
     * break at. It used to be one pass that re-measured the whole prefix per
     * character, which is the same answer at O(k&sup2;) - see {@link EmojiText}.
     */
    private int lineEnd(String text, int start, int width)
    {
        int n = text.length();
        int hardBreak = text.indexOf('\n', start);
        int limit = hardBreak >= 0 ? hardBreak : n;
        if (limit <= start) { return limit; }

        int fit = EmojiText.fitEnd(text, start, limit, width, font);
        if (fit >= limit) { return limit; }

        // Break at the last space if there was one, otherwise mid-word: a URL
        // with no spaces in it must not vanish off the right edge. The space
        // that itself overflowed still counts - the line ends before it either
        // way - but one at the very start does not, or the line would be empty.
        for (int i = fit; i > start; i--)
        {
            if (text.charAt(i) == ' ') { return i; }
        }
        // Nothing fits and nothing to break on - emit one token so wrapping
        // always makes progress.
        return fit > start ? fit
                : Math.min(limit, EmojiText.nextBoundary(text, start));
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
        if (thumbnailCount < thumbnailCapacity)
        {
            thumbnailIds[thumbnailCount] = messageId;
            thumbnails[thumbnailCount] = image;
            thumbnailCount++;
        }
        else
        {
            System.arraycopy(thumbnailIds, 1, thumbnailIds, 0,
                    thumbnailCapacity - 1);
            System.arraycopy(thumbnails, 1, thumbnails, 0,
                    thumbnailCapacity - 1);
            thumbnailIds[thumbnailCapacity - 1] = messageId;
            thumbnails[thumbnailCapacity - 1] = image;
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

    /**
     * Drop every decoded thumbnail. Public because the memory-pressure ladder
     * calls it from a worker thread, and this is the cheapest useful thing the
     * client can give back - roughly 190 KB when the cache is full.
     */
    public synchronized void clearThumbnails()
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
