package tgtest;

import org.microemu.device.DeviceFactory;

import tg.api.ForumTopic;
import tg.api.Peer;
import tg.api.TopicWindow;
import tg.ui.TopicListScreen;
import tg.ui.Theme;

/**
 * The topic list's window arithmetic and its screen.
 *
 * The dialog list's contract one screen down: the window is bounded whichever
 * way the reader moves, a page the window already holds refreshes rows in
 * place rather than reordering them, and the reader's selection survives both.
 */
public final class TopicWindowTest implements Test
{
    private static final int WIDTH = 240;
    private static final int HEIGHT = 320;

    public String name() { return "ui/topic-window"; }

    public void run()
    {
        DeviceFactory.setDevice(new UiTestDevice("topic-list", WIDTH, HEIGHT));

        theWindowIsBoundedWhicheverWayItMoves();
        anOverlappingPageRefreshesInPlace();
        countNewIgnoresWhatIsHeld();
        theAnchoredRowSurvivesAReorder();
        theHeaderCountsPositionNotRetention();
    }

    private static void theWindowIsBoundedWhicheverWayItMoves()
    {
        final int cap = 60;
        final int page = 20;

        ForumTopic[] window = topics(range(0, page));
        int above = 0;
        for (int fetched = page; fetched < 400; fetched += page)
        {
            ForumTopic[] merged = TopicWindow.merge(window,
                    topics(range(fetched, page)));
            int drop = merged.length - cap;
            if (drop > 0) { above += drop; }
            window = TopicWindow.keepLast(merged, cap);
            Assert.isTrue("the window never exceeds the cap: " + window.length,
                    window.length <= cap);
        }

        Assert.equal("the window is full and no larger", cap, window.length);
        Assert.equal("and it sits where the reader is", 400 - cap, above);
        Assert.equal("holding the last rows fetched",
                400, window[window.length - 1].id);
        Assert.equal("and not the first", 341, window[0].id);

        // Back up: a restored run goes on the front and the bottom is given
        // up, which needs no restore point - the last retained row is itself
        // the offset the next downward request is made from.
        ForumTopic[] restored = topics(range(320, page));
        ForumTopic[] merged = TopicWindow.merge(restored, window);
        int gained = merged.length - window.length;
        window = TopicWindow.keepFirst(merged, cap);
        above -= gained;

        Assert.equal("still bounded going the other way", cap, window.length);
        Assert.equal("the restored run is on the front", 321, window[0].id);
        Assert.equal("the window start moved up by what was gained",
                320, above);

        Assert.equal("keepLast of a short list is the list",
                3, TopicWindow.keepLast(topics(range(0, 3)), 10).length);
        Assert.equal("keepFirst of a short list is the list",
                3, TopicWindow.keepFirst(topics(range(0, 3)), 10).length);
    }

    private static void anOverlappingPageRefreshesInPlace()
    {
        ForumTopic[] window = topics(range(0, 5));
        ForumTopic fresher = topic(3, "Topic 3");
        fresher.unreadCount = 9;
        fresher.topMessageId = 900;
        ForumTopic[] merged = TopicWindow.merge(window,
                new ForumTopic[] { fresher, topic(6, "Topic 6") });

        Assert.equal("only the genuinely new row was appended",
                6, merged.length);
        Assert.equal("the held row kept its place", 3, merged[2].id);
        Assert.equal("and took the fresher counts", 9, merged[2].unreadCount);
        Assert.equal("the new row went to the back", 6,
                merged[merged.length - 1].id);
    }

    private static void countNewIgnoresWhatIsHeld()
    {
        ForumTopic[] window = topics(range(0, 5));
        ForumTopic[] page = new ForumTopic[] {
            topic(2, "held"), topic(9, "new"), null, topic(3, "held too")
        };
        Assert.equal("one genuinely new row", 1,
                TopicWindow.countNew(window, page));
        Assert.equal("an all-held page carries nothing", 0,
                TopicWindow.countNew(window, topics(range(0, 5))));
        Assert.equal("indexOf finds by id", 2, TopicWindow.indexOf(window, 3));
        Assert.equal("and answers -1 for a stranger",
                -1, TopicWindow.indexOf(window, 77));
    }

    /**
     * A message arriving in another topic promotes its row; the reader's
     * selection has to follow the topic they were on, not the row number.
     */
    private static void theAnchoredRowSurvivesAReorder()
    {
        TopicListScreen screen = newScreen();
        screen.setTopics(topics(range(0, 6)), 0, 6, 0);
        // Select topic id 3 (row index 2).
        screen.setTopics(topics(range(0, 6)), 0, 6, 3);
        Assert.equal("the anchor selects its row", 3,
                screen.selectedTopic().id);

        // Topic 6 is promoted to the top; every row shifts down by one.
        ForumTopic[] reordered = new ForumTopic[] {
            topic(6, "promoted"), topic(1, "t"), topic(2, "t"),
            topic(3, "t"), topic(4, "t"), topic(5, "t")
        };
        screen.setTopics(reordered, 0, 6, 3);
        Assert.equal("the selection followed the topic, not the row",
                3, screen.selectedTopic().id);
    }

    private static void theHeaderCountsPositionNotRetention()
    {
        TopicListScreen screen = newScreen();
        screen.setTopics(topics(range(100, 20)), 100, 300, 0);
        Assert.equal("the window knows where it sits", 100,
                screen.windowStart());
        Assert.equal("and what it counts against", 300, screen.totalCount());
        Assert.equal("rows held is the window, not the list", 20,
                screen.topicCount());
        Assert.isTrue("visible edges are inside the window",
                screen.firstVisibleIndex() >= 0
                && screen.lastVisibleIndex() < screen.topicCount());
    }

    // ------------------------------------------------------------ fixtures

    private static TopicListScreen newScreen()
    {
        Peer forum = new Peer(Peer.CHANNEL, 77);
        forum.accessHash = 88;
        forum.forum = true;
        return new TopicListScreen(Theme.byId(Theme.LIGHT), forum);
    }

    private static ForumTopic topic(int id, String title)
    {
        ForumTopic t = new ForumTopic();
        t.id = id;
        t.title = title;
        t.lastDate = 1000 + id;
        t.topMessageId = id * 10;
        return t;
    }

    private static ForumTopic[] topics(int[] ids)
    {
        ForumTopic[] out = new ForumTopic[ids.length];
        for (int i = 0; i < ids.length; i++)
        {
            out[i] = topic(ids[i], "Topic " + ids[i]);
        }
        return out;
    }

    /** Ids {@code from + 1 .. from + count}, so id 0 never appears. */
    private static int[] range(int from, int count)
    {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) { out[i] = from + i + 1; }
        return out;
    }
}
