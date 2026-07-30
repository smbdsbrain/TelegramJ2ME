package tgtest;

import tg.ui.ChatScrollState;

/** Scroll intent must survive every asynchronous chat-screen rebuild. */
public final class ChatScrollStateTest implements Test
{
    public String name() { return "ui/chat-scroll"; }

    public void run()
    {
        followsNewMessagesAtEnd();
        preservesLogicalAnchorWhileReading();
        preservesIntentAcrossPendingAndResize();
        resetsForDifferentPeer();
    }

    private static void followsNewMessagesAtEnd()
    {
        ChatScrollState state = new ChatScrollState();
        state.reset(10);
        Assert.equal("starts at end", 10, state.top());
        Assert.isTrue("end following enabled", state.followsEnd());

        state.replace(new int[] { 1 }, new int[] { 0 },
                new int[] { 1, 2 }, new int[] { 0, 0 }, 12);
        Assert.equal("live message follows end", 12, state.top());

        state.appended(15);
        Assert.equal("queued message follows end", 15, state.top());
    }

    private static void preservesLogicalAnchorWhileReading()
    {
        ChatScrollState state = new ChatScrollState();
        state.reset(6);
        state.userScroll(-3, 6);
        Assert.equal("user moved into history", 3, state.top());
        Assert.isFalse("history disables end following", state.followsEnd());

        // The oldest wrapped lines were evicted by the 30-message bound. The
        // same logical line (message 20, offset 1) moved from index 3 to 1.
        state.replace(
                new int[] { 10, 10, 20, 20, 30, 30, 40 },
                new int[] { 0, 1, 0, 1, 0, 1, 0 },
                new int[] { 20, 20, 30, 30, 40, 50, 50 },
                new int[] { 0, 1, 0, 1, 0, 0, 1 }, 3);
        Assert.equal("logical line anchor follows reflow", 1, state.top());
        Assert.isFalse("replace keeps reading intent", state.followsEnd());

        state.replace(
                new int[] { 20, 20, 30 },
                new int[] { 0, 1, 0 },
                new int[] { 30, 40, 50 },
                new int[] { 0, 0, 0 }, 1);
        Assert.equal("evicted anchor falls to oldest survivor", 0, state.top());
    }

    private static void preservesIntentAcrossPendingAndResize()
    {
        ChatScrollState state = new ChatScrollState();
        state.reset(8);
        state.userScroll(-5, 8);
        state.appended(12);
        Assert.equal("pending overlay does not steal focus", 3, state.top());
        Assert.isFalse("append does not change reading intent",
                state.followsEnd());

        state.resized(2);
        Assert.equal("viewport resize only clamps", 2, state.top());
        Assert.isFalse("resize does not enable following", state.followsEnd());

        state.userScroll(99, 12);
        Assert.equal("explicit down reaches end", 12, state.top());
        Assert.isTrue("explicit end resumes following", state.followsEnd());
    }

    private static void resetsForDifferentPeer()
    {
        ChatScrollState state = new ChatScrollState();
        state.reset(9);
        state.userScroll(-4, 9);
        state.reset(20);
        Assert.equal("different peer opens newest", 20, state.top());
        Assert.isTrue("different peer follows end", state.followsEnd());
    }
}
