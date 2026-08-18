package tgtest;

import tg.api.Dialog;
import tg.api.Peer;
import tg.app.LocalReads;

/**
 * The badge the reader just cleared, and the refresh that used to bring it back.
 *
 * {@code Mark all read} clears the count on the retained {@code Dialog} and
 * sends the acknowledgement. Seconds later a snapshot refresh replaces that row
 * object outright - {@code PageMerge.restate} assigns the fresh one,
 * {@code PageMerge.refresh} prefers the fresh head-page entry - and the badge
 * is back, until the server's own read cursor catches up and the refresh after
 * that reports it. On a connection where a round trip is measured in seconds,
 * that is the client visibly contradicting the user.
 *
 * The fix is a small bounded record of what was cleared, applied after every
 * refresh and dropped the moment the server agrees. Two properties matter and
 * both are easy to lose: it has to expire, or a chat that genuinely becomes
 * unread again never shows it; and it has to be bounded, because it is fed by
 * a key the user can hold down.
 */
public final class LocalReadsTest implements Test
{
    public String name() { return "ui/local-reads"; }

    public void run() throws Exception
    {
        aClearedBadgeSurvivesARefresh();
        itExpiresWhenTheServerAgrees();
        aChatThatBecomesUnreadAgainShowsIt();
        itIsBounded();
        itIgnoresChatsItWasNeverToldAbout();
        aHigherClearReplacesALowerOne();
        loggingOutForgetsEverything();
    }

    /**
     * The defect, stated directly: the row the refresh handed back is a
     * different object with the server's stale count on it.
     */
    private static void aClearedBadgeSurvivesARefresh()
    {
        LocalReads reads = new LocalReads();
        reads.cleared(peer(7), 0, 4711);

        Dialog fromServer = dialog(7, 12, 4000);   // 12 unread, cursor behind
        reads.apply(fromServer);

        Assert.equal("the badge stays cleared", 0, fromServer.unreadCount);
        Assert.equal("and the read cursor is the one we sent", 4711,
                fromServer.readInboxMaxId);
    }

    /**
     * Once the server reports a cursor that reaches what we sent, it knows. Its
     * answer is newer than ours from that point, and holding on would suppress
     * real news.
     */
    private static void itExpiresWhenTheServerAgrees()
    {
        LocalReads reads = new LocalReads();
        reads.cleared(peer(7), 0, 4711);
        Assert.equal("one chat is waiting", 1, reads.pending());

        Dialog caughtUp = dialog(7, 0, 4711);
        reads.apply(caughtUp);
        Assert.equal("the entry is dropped", 0, reads.pending());

        Dialog later = dialog(7, 3, 4711);
        reads.apply(later);
        Assert.equal("so three new messages show as three", 3,
                later.unreadCount);
    }

    private static void aChatThatBecomesUnreadAgainShowsIt()
    {
        LocalReads reads = new LocalReads();
        reads.cleared(peer(7), 0, 100);

        // The server has moved past what we acknowledged and there is new mail.
        Dialog fresh = dialog(7, 5, 140);
        reads.apply(fresh);

        Assert.equal("a badge earned after the clear is not suppressed", 5,
                fresh.unreadCount);
        Assert.equal("and nothing is left waiting", 0, reads.pending());
    }

    /** Fed by a key the user can hold down, so it has to have a ceiling. */
    private static void itIsBounded()
    {
        LocalReads reads = new LocalReads();
        for (int i = 1; i <= 40; i++) { reads.cleared(peer(i), 0, 100 + i); }

        Assert.isTrue("bounded, whatever the user does: " + reads.pending(),
                reads.pending() <= 16);

        // The most recent are the ones kept, because they are the ones whose
        // acknowledgements are still in flight.
        Dialog newest = dialog(40, 9, 0);
        reads.apply(newest);
        Assert.equal("the newest clear is still remembered", 0,
                newest.unreadCount);
    }

    private static void itIgnoresChatsItWasNeverToldAbout()
    {
        LocalReads reads = new LocalReads();
        reads.cleared(peer(7), 0, 4711);

        Dialog other = dialog(8, 6, 0);
        reads.apply(other);
        Assert.equal("another chat is untouched", 6, other.unreadCount);

        reads.apply(null);
        reads.apply(new Dialog());
        Assert.equal("and nothing was recorded by asking", 1, reads.pending());
    }

    private static void aHigherClearReplacesALowerOne()
    {
        LocalReads reads = new LocalReads();
        reads.cleared(peer(7), 0, 100);
        reads.cleared(peer(7), 0, 250);
        Assert.equal("still one entry", 1, reads.pending());

        Dialog fresh = dialog(7, 4, 180);
        reads.apply(fresh);
        Assert.equal("the later clear wins", 0, fresh.unreadCount);
        Assert.equal("and carries its own cursor", 250, fresh.readInboxMaxId);
    }

    private static void loggingOutForgetsEverything()
    {
        LocalReads reads = new LocalReads();
        reads.cleared(peer(7), 0, 100);
        reads.clear();

        Assert.equal("nothing is held", 0, reads.pending());
        Dialog next = dialog(7, 2, 0);
        reads.apply(next);
        Assert.equal("and the next account starts clean", 2, next.unreadCount);
    }

    private static Peer peer(long id)
    {
        return new Peer(Peer.USER, id);
    }

    private static Dialog dialog(long id, int unread, int readInboxMaxId)
    {
        Dialog dialog = new Dialog();
        dialog.peer = peer(id);
        dialog.unreadCount = unread;
        dialog.readInboxMaxId = readInboxMaxId;
        return dialog;
    }
}
