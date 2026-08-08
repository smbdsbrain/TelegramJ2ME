package tgtest;

import tg.api.Message;
import tg.app.UnreadPick;

/**
 * What "first unread" means when ids have gaps and the page is bounded.
 *
 * Two obvious answers are both wrong. It is not {@code readInboxMaxId + 1}:
 * Telegram allocates ids per peer and they are not contiguous - service
 * messages, deletions and messages this client never fetched all leave holes -
 * so the id one past the marker usually does not exist and jumping to it lands
 * nowhere. And it is not the first row of the page: a page comes back newest
 * first and contains the reader's own outgoing messages, which they have read
 * by definition.
 *
 * The answer is the oldest incoming message above the marker that is actually
 * in the page. When the boundary message has been deleted that is the earliest
 * unread still available, which the caller labels as such rather than claiming
 * to have found the first.
 */
public final class UnreadPickTest implements Test
{
    public String name() { return "ui/unread-pick"; }

    public void run() throws Exception
    {
        itPicksTheOldestUnreadNotTheNewest();
        itSkipsTheReadersOwnMessages();
        aDeletedBoundaryFallsBackToWhatIsLeft();
        nothingAboveTheMarkerIsNone();
        anEmptyOrNullPageIsNone();
        itKnowsWhetherThePageReachedTheMarker();
        theHighWaterMarkComesFromThePage();
    }

    /**
     * The page arrives newest first. Taking the first row would drop the reader
     * at the bottom of everything they have not read, which is where they
     * already were.
     */
    private static void itPicksTheOldestUnreadNotTheNewest()
    {
        Message[] page = new Message[] {
            incoming(140), incoming(131), incoming(124), incoming(118),
        };
        Assert.equal("the oldest one above the marker", 118,
                UnreadPick.firstUnread(page, 117));
    }

    private static void itSkipsTheReadersOwnMessages()
    {
        Message[] page = new Message[] {
            incoming(140), outgoing(133), outgoing(126), incoming(131),
        };
        Assert.equal("a reply of the reader is not something to catch up on",
                131, UnreadPick.firstUnread(page, 120));

        Message[] mine = new Message[] { outgoing(130), outgoing(131) };
        Assert.equal("a page of only their own messages has no unread",
                UnreadPick.NONE, UnreadPick.firstUnread(mine, 120));
    }

    /**
     * Ids are not contiguous, and the message at the boundary may be gone. The
     * earliest one that still exists is the honest best effort.
     */
    private static void aDeletedBoundaryFallsBackToWhatIsLeft()
    {
        // The marker is 200; 201 through 219 no longer exist.
        Message[] page = new Message[] { incoming(240), incoming(220) };
        Assert.equal("the earliest unread still available", 220,
                UnreadPick.firstUnread(page, 200));
    }

    private static void nothingAboveTheMarkerIsNone()
    {
        Message[] page = new Message[] { incoming(90), incoming(80) };
        Assert.equal("everything is read", UnreadPick.NONE,
                UnreadPick.firstUnread(page, 100));
        Assert.equal("the marker itself is read", UnreadPick.NONE,
                UnreadPick.firstUnread(new Message[] { incoming(100) }, 100));
    }

    private static void anEmptyOrNullPageIsNone()
    {
        Assert.equal("null", UnreadPick.NONE,
                UnreadPick.firstUnread(null, 100));
        Assert.equal("empty", UnreadPick.NONE,
                UnreadPick.firstUnread(new Message[0], 100));
        Assert.equal("holes in the array", UnreadPick.NONE,
                UnreadPick.firstUnread(new Message[2], 100));
    }

    /**
     * "You are up to date" is a claim about everything below the page as well.
     * A page that did not reach back past the marker cannot support it.
     */
    private static void itKnowsWhetherThePageReachedTheMarker()
    {
        Message[] reaches = new Message[] { incoming(140), incoming(95) };
        Assert.isTrue("it contains something at or below the marker",
                UnreadPick.pageReachesMarker(reaches, 100));

        Message[] doesNot = new Message[] { incoming(140), incoming(131) };
        Assert.isFalse("everything in it is above the marker, so what is below"
                + " is unknown", UnreadPick.pageReachesMarker(doesNot, 100));

        Assert.isFalse("an empty page reaches nothing",
                UnreadPick.pageReachesMarker(new Message[0], 100));
    }

    private static void theHighWaterMarkComesFromThePage()
    {
        Message[] page = new Message[] { incoming(131), incoming(140), null };
        Assert.equal("the highest id present", 140, UnreadPick.newestId(page));
        Assert.equal("nothing present", 0, UnreadPick.newestId(new Message[0]));
        Assert.equal("null", 0, UnreadPick.newestId(null));
    }

    private static Message incoming(int id)
    {
        Message message = new Message();
        message.id = id;
        message.outgoing = false;
        return message;
    }

    private static Message outgoing(int id)
    {
        Message message = new Message();
        message.id = id;
        message.outgoing = true;
        return message;
    }
}
