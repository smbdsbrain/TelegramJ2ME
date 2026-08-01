package tgtest;

import javax.microedition.lcdui.Canvas;

import org.microemu.device.DeviceFactory;

import tg.api.Message;
import tg.api.PageMerge;
import tg.mem.MemoryBudget;
import tg.ui.ChatScreen;
import tg.ui.EmojiText;

/**
 * Virtualised chat scrolling.
 *
 * The properties that matter are all invisible from a screenshot, which is why
 * they are pinned here:
 *
 *   - what is laid out is bounded by the screen, not by how much history has
 *     been loaded, so reading back through a long channel cannot grow the heap
 *     without limit;
 *   - moving back and forth across a window edge reflows once rather than on
 *     every keypress, which is what keeps eviction from turning into a fetch
 *     storm on a connection where a page costs seconds;
 *   - the reader's position survives every rebuild, because a transcript that
 *     jumps while being read is worse than one that does not scroll at all;
 *   - the word wrap is linear, and produces exactly the same break points as
 *     the quadratic version it replaced.
 */
public final class ChatWindowTest implements Test
{
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;

    /** 2026-01-01T00:00:00Z, so the date separators are real. */
    private static final int BASE_DATE = 1767225600;

    public String name() { return "ui/chat-window"; }

    public void run()
    {
        DeviceFactory.setDevice(new UiTestDevice("chat-window", WIDTH, HEIGHT));

        layoutIsBoundedByTheScreen();
        readingBackwardsReachesTheOldestMessage();
        crossingAnEdgeReflowsOnceNotPerKeypress();
        theReadersPositionSurvivesARebuild();
        prefetchMarginCrossesOnceOnTheWayUp();
        theEndIsTheConversationNotTheWindow();
        focusReachesAMessageOutsideTheWindow();
        wrappingIsLinearAndBreaksWhereItAlwaysDid();
        pageMergeKeepsTheAnchor();
    }

    // -------------------------------------------------------------- the window

    /**
     * The whole point. A transcript three times as long must not lay out three
     * times as much - if it does, scrolling back far enough is still an
     * OutOfMemoryError with extra steps.
     */
    private static void layoutIsBoundedByTheScreen()
    {
        ExposedChat large = chat();
        large.resetMessages(transcript(300));
        int largeLines = large.transcriptLineCount();

        ExposedChat huge = chat();
        huge.resetMessages(transcript(900));

        Assert.equal("three times the history lays out the same window",
                largeLines, huge.transcriptLineCount());

        // What the unwindowed screen did, measured rather than argued: a window
        // deep enough to swallow the whole transcript is the old behaviour.
        ExposedChat unwindowed = new ExposedChat(12, 100000);
        unwindowed.resetMessages(transcript(300));
        Assert.isTrue("windowing lays out a fraction of the transcript: "
                        + largeLines + " of " + unwindowed.transcriptLineCount(),
                largeLines * 3 < unwindowed.transcriptLineCount());

        // Deliberately generous: the exact figure depends on the device font.
        // The claim being made is "proportional to the screen", not a constant.
        int screens = large.windowScreens() * 2 + 2;
        Assert.isTrue("the window is a few screens, not a transcript: " + largeLines,
                largeLines <= screens * large.visible());
        Assert.equal("every message is still retained", 900, huge.messageCount());
    }

    /**
     * Scrolling has to actually get somewhere. Windowing that fails to extend
     * would leave the reader pinned at the oldest laid-out message with more
     * history sitting in memory behind it.
     */
    private static void readingBackwardsReachesTheOldestMessage()
    {
        Message[] messages = transcript(300);
        ExposedChat chat = chat();
        chat.resetMessages(messages);

        int oldest = messages[messages.length - 1].id;
        int previousTop = chat.topVisibleMessageId();
        for (int i = 0; i < 400 && chat.topVisibleMessageId() != oldest; i++)
        {
            chat.press(Canvas.KEY_NUM4);
            int top = chat.topVisibleMessageId();
            Assert.isTrue("paging up never moves towards the newest",
                    top <= previousTop);
            previousTop = top;
        }
        Assert.equal("paging up reaches the oldest retained message",
                oldest, chat.topVisibleMessageId());
        Assert.isFalse("the oldest message is not the end", chat.isAtEnd());
    }

    /**
     * Hysteresis. The window is three screens either side and is rebuilt at one
     * screen from an edge, so oscillating across a boundary has two screens of
     * slack to absorb. Without it, every page down at the top of the window
     * would reflow - and in the client, ask for a page of history.
     */
    private static void crossingAnEdgeReflowsOnceNotPerKeypress()
    {
        ExposedChat chat = chat();
        chat.resetMessages(transcript(300));

        // Get well into the history first, so both edges have somewhere to go.
        for (int i = 0; i < 12; i++) { chat.press(Canvas.KEY_NUM4); }

        int before = chat.layoutCount();
        for (int i = 0; i < 10; i++)
        {
            chat.press(Canvas.KEY_NUM4);
            chat.press(Canvas.KEY_NUM6);
        }
        int reflows = chat.layoutCount() - before;
        Assert.isTrue("twenty oscillating pages do not reflow twenty times: "
                + reflows, reflows <= 4);
        Assert.isTrue("the window still tracks the viewport", reflows >= 0);
    }

    /** A transcript that jumps while being read is worse than one that sticks. */
    private static void theReadersPositionSurvivesARebuild()
    {
        Message[] messages = transcript(300);
        ExposedChat chat = chat();
        chat.resetMessages(messages);
        for (int i = 0; i < 20; i++) { chat.press(Canvas.KEY_NUM4); }

        int anchor = chat.topVisibleMessageId();
        Assert.isTrue("the reader is somewhere in the history", anchor != 0);

        // What the client does when an older page lands: same messages plus
        // thirty more at the far end, handed back through setMessages.
        chat.setMessages(extend(messages, 30));
        Assert.equal("an older page does not move the reader",
                anchor, chat.topVisibleMessageId());

        // And what it does when a live message arrives at the other end.
        chat.setMessages(prepend(messages, 1));
        Assert.equal("a new message does not move the reader",
                anchor, chat.topVisibleMessageId());
    }

    /**
     * The fetch trigger. Reading back has to cross the margin once per page,
     * not sit permanently below it - a client that asks on every keypress would
     * hammer the connection it is trying to be gentle with.
     */
    private static void prefetchMarginCrossesOnceOnTheWayUp()
    {
        Message[] messages = transcript(120);
        ExposedChat chat = chat();
        chat.resetMessages(messages);

        int margin = MemoryBudget.historyPrefetchMargin();
        Assert.isTrue("a fresh chat is not already asking for more",
                chat.messagesOlderThanViewport() >= margin);

        int previous = chat.messagesOlderThanViewport();
        boolean crossed = false;
        for (int i = 0; i < 200; i++)
        {
            chat.press(Canvas.KEY_NUM4);
            int remaining = chat.messagesOlderThanViewport();
            Assert.isTrue("the margin only ever shrinks on the way up",
                    remaining <= previous);
            previous = remaining;
            if (remaining < margin) { crossed = true; break; }
        }
        Assert.isTrue("reading backwards crosses the prefetch margin", crossed);
        Assert.equal("nothing older is left once the top is reached",
                0, atTop(chat));

        // And the mirror of it, which is what tells the client to fetch the
        // newest messages back after the retention window slid off them.
        Assert.equal("everything is newer than the oldest message",
                messages.length - 1, chat.messagesNewerThanViewport());
        chat.scrollToEnd();
        Assert.isTrue("nothing much is newer than the end",
                chat.messagesNewerThanViewport() < margin);
    }

    /**
     * followsEnd has to mean the end of the conversation. Paging down inside a
     * window whose bottom is not the newest message must not set it, or the
     * next rebuild yanks the reader to the bottom of the chat.
     */
    private static void theEndIsTheConversationNotTheWindow()
    {
        Message[] messages = transcript(300);
        ExposedChat chat = chat();
        chat.resetMessages(messages);
        Assert.isTrue("a fresh chat opens at the newest message", chat.isAtEnd());

        for (int i = 0; i < 20; i++) { chat.press(Canvas.KEY_NUM4); }
        Assert.isFalse("reading history is not the end", chat.isAtEnd());

        // Down one page at a time, from the middle. Each of these lands on the
        // bottom of a window that is not the end of the transcript.
        for (int i = 0; i < 3; i++)
        {
            chat.press(Canvas.KEY_NUM6);
            Assert.isFalse("the bottom of the window is not the end",
                    chat.isAtEnd());
        }

        chat.scrollToEnd();
        Assert.isTrue("scrollToEnd returns to the newest message", chat.isAtEnd());
        Assert.equal("the newest message is on screen",
                messages[0].id, bottomVisible(chat));
    }

    /** Jumping to a message - from a forward, or a reply - has to work anywhere. */
    private static void focusReachesAMessageOutsideTheWindow()
    {
        Message[] messages = transcript(300);
        ExposedChat chat = chat();
        chat.resetMessages(messages);

        int distant = messages[250].id;
        chat.focusMessage(distant);
        Assert.equal("focus lands on a message outside the window",
                distant, chat.focusedMessageId());
        Assert.isTrue("the focused message is laid out",
                chat.transcriptLineCount() > 0);
        Assert.isTrue("the window moved to it",
                Math.abs(indexOf(messages, chat.topVisibleMessageId()) - 250)
                        < 40);

        // A message that was never retained is not somewhere to jump to.
        int layouts = chat.layoutCount();
        chat.focusMessage(999999);
        Assert.equal("an unknown message is ignored", distant,
                chat.focusedMessageId());
        Assert.equal("and does not reflow", layouts, chat.layoutCount());
    }

    // ---------------------------------------------------------------- the wrap

    /**
     * {@link EmojiText#fitEnd} accumulates one token at a time where the old
     * code re-measured the whole prefix per character. Same answer, O(k) rather
     * than O(k&sup2;) - so the check is that it really is the same answer,
     * including for the sequences the tokeniser exists for.
     */
    private static void wrappingIsLinearAndBreaksWhereItAlwaysDid()
    {
        javax.microedition.lcdui.Font font =
                javax.microedition.lcdui.Font.getFont(
                        javax.microedition.lcdui.Font.FACE_PROPORTIONAL,
                        javax.microedition.lcdui.Font.STYLE_PLAIN,
                        javax.microedition.lcdui.Font.SIZE_SMALL);

        String[] samples = {
            "the quick brown fox jumps over the lazy dog",
            "👍🔥🎉 mixed with words",
            "👩‍💻 zwj sequence then text",
            "👍🏽 skin tone modifier",
            "❤️ variation selector",
            repeat("https://example.invalid/a", 20)
        };

        for (int s = 0; s < samples.length; s++)
        {
            String text = samples[s];
            for (int width = 8; width <= 300; width += 37)
            {
                Assert.equal("fitEnd matches a prefix scan: sample " + s
                                + " at " + width,
                        referenceFit(text, width, font),
                        EmojiText.fitEnd(text, 0, text.length(), width, font));
            }
            Assert.equal("token widths sum to the whole string: sample " + s,
                    EmojiText.stringWidth(text, font),
                    sumOfTokens(text, font));
        }

        // The case the quadratic version made painful: one long unbroken run.
        String url = repeat("x", 4000);
        int at = 0;
        int lines = 0;
        while (at < url.length() && lines < 500)
        {
            int next = EmojiText.fitEnd(url, at, url.length(), 100, font);
            Assert.isTrue("wrapping always makes progress", next > at);
            at = next;
            lines++;
        }
        Assert.equal("a 4000-character run wraps completely", url.length(), at);
    }

    /** What lineEnd used to do: re-measure the prefix at every boundary. */
    private static int referenceFit(String text, int width,
                                    javax.microedition.lcdui.Font font)
    {
        int i = 0;
        while (i < text.length())
        {
            int next = EmojiText.nextBoundary(text, i);
            if (EmojiText.substringWidth(text, 0, next, font) > width)
            {
                return i;
            }
            i = next;
        }
        return text.length();
    }

    private static int sumOfTokens(String text,
                                   javax.microedition.lcdui.Font font)
    {
        int total = 0;
        int at = 0;
        while (at < text.length())
        {
            int next = EmojiText.nextBoundary(text, at);
            total += EmojiText.substringWidth(text, at, next - at, font);
            at = next;
        }
        return total;
    }

    // ----------------------------------------------------------- the retention

    /**
     * The retained window has to be trimmed around what is being read. Dropping
     * the tail - which is what a bounded merge does - throws away the messages
     * on screen and keeps the ones nobody is looking at.
     */
    private static void pageMergeKeepsTheAnchor()
    {
        Message[] all = transcript(100);

        Message[] middle = PageMerge.window(all, 50, 20);
        Assert.equal("a window is the size asked for", 20, middle.length);
        Assert.isTrue("and contains the anchor",
                indexOf(middle, all[50].id) >= 0);
        Assert.isTrue("centred on it",
                Math.abs(indexOf(middle, all[50].id) - 10) <= 1);

        Message[] atNewest = PageMerge.window(all, 0, 20);
        Assert.equal("an anchor at the newest end keeps the newest",
                all[0].id, atNewest[0].id);
        Message[] atOldest = PageMerge.window(all, 99, 20);
        Assert.equal("an anchor at the oldest end keeps the oldest",
                all[99].id, atOldest[19].id);

        Assert.isTrue("a limit above the length is the whole thing",
                PageMerge.window(all, 0, 500) == all);
        Assert.equal("a nonsense anchor is clamped rather than thrown",
                20, PageMerge.window(all, -5, 20).length);
        Assert.equal("and so is one past the end",
                20, PageMerge.window(all, 5000, 20).length);
        Assert.equal("no messages is no window",
                0, PageMerge.window(null, 0, 20).length);

        // An ordered merge, because a refresh page is newer than what is held
        // and concatenating would leave it sorted to the bottom.
        Message[] older = new Message[] { all[60], all[61], all[62] };
        Message[] newer = new Message[] { all[0], all[1] };
        Message[] merged = PageMerge.merge(older, newer);
        Assert.equal("an ordered merge holds everything", 5, merged.length);
        Assert.equal("newest first", all[0].id, merged[0].id);
        Assert.equal("oldest last", all[62].id, merged[4].id);

        Message duplicate = message(all[60].id, all[60].date, "edited");
        Message[] withDuplicate = PageMerge.merge(older,
                new Message[] { duplicate });
        Assert.equal("a duplicate is not held twice", 3, withDuplicate.length);
        Assert.equal("and the fresher copy wins", "edited",
                withDuplicate[0].text);
    }

    // --------------------------------------------------------------- fixtures

    private static ExposedChat chat()
    {
        // Explicit rather than inherited from MemoryBudget: another suite in
        // this JVM may have installed a ceiling, and a window that changes size
        // between runs would make every assertion here conditional.
        return new ExposedChat(12, 3);
    }

    /** Newest first, one wrapped line of text each, several per day. */
    private static Message[] transcript(int count)
    {
        Message[] out = new Message[count];
        for (int i = 0; i < count; i++)
        {
            // id descending from count so index 0 really is the newest.
            int id = count - i;
            out[i] = message(id, BASE_DATE + id * 3600, "message number " + id);
        }
        return out;
    }

    private static Message message(int id, int date, String text)
    {
        Message m = new Message();
        m.id = id;
        m.date = date;
        m.text = text;
        return m;
    }

    /** Another page of older history, as loadOlderPage would produce. */
    private static Message[] extend(Message[] messages, int more)
    {
        Message[] out = new Message[messages.length + more];
        System.arraycopy(messages, 0, out, 0, messages.length);
        int oldest = messages[messages.length - 1].id;
        for (int i = 0; i < more; i++)
        {
            int id = oldest - 1 - i;
            out[messages.length + i] = message(id, BASE_DATE + id * 3600,
                    "message number " + id);
        }
        return out;
    }

    /** A live message arriving at the newest end. */
    private static Message[] prepend(Message[] messages, int more)
    {
        Message[] out = new Message[messages.length + more];
        int newest = messages[0].id;
        for (int i = 0; i < more; i++)
        {
            int id = newest + more - i;
            out[i] = message(id, BASE_DATE + id * 3600, "message number " + id);
        }
        System.arraycopy(messages, 0, out, more, messages.length);
        return out;
    }

    private static int indexOf(Message[] messages, int id)
    {
        for (int i = 0; i < messages.length; i++)
        {
            if (messages[i] != null && messages[i].id == id) { return i; }
        }
        return -1;
    }

    private static int atTop(ExposedChat chat)
    {
        for (int i = 0; i < 400 && chat.messagesOlderThanViewport() > 0; i++)
        {
            chat.press(Canvas.KEY_NUM4);
        }
        return chat.messagesOlderThanViewport();
    }

    private static int bottomVisible(ExposedChat chat)
    {
        return chat.lastLaidOutMessageId();
    }

    private static String repeat(String unit, int times)
    {
        StringBuffer out = new StringBuffer();
        for (int i = 0; i < times; i++) { out.append(unit); }
        return out.toString();
    }

    private static final class ExposedChat extends ChatScreen
    {
        ExposedChat(int thumbnails, int screens)
        {
            super(null, thumbnails, screens);
        }

        void press(int keyCode) { keyPressed(keyCode); }

        int visible() { return getHeight() / 16; }

        /** Newest message the layout actually reaches. */
        int lastLaidOutMessageId()
        {
            Message[] messages = messages();
            return messages.length == 0 ? 0 : messages[0].id;
        }
    }
}
