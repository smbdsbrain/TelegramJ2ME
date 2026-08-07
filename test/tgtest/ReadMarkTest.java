package tgtest;

import tg.api.Api;
import tg.api.Message;
import tg.api.PageMerge;
import tg.api.Peer;
import tg.api.Requests;
import tg.app.ReadMark;

/**
 * "Read up to here" is the newest message the conversation ever had, and it
 * belongs to that conversation.
 *
 * Two defects, one state. {@code TgMidlet.markAllReadNow} computed its max_id
 * from the retained dialog's {@code topMessageId} and then fell back to
 * {@code openHistory[0].id} - the head of an array that is a *window*. Reading
 * backwards slides that window off the newest end, which is the whole reason
 * {@code newestKnownId} exists, and the explicit Mark all read was the one path
 * that never consulted it. A reader who scrolled back a few hundred messages and
 * pressed it marked read up to wherever they had got to, and left everything
 * after it unread.
 *
 * The second is the mirror image: the mark only ever rises, and
 * {@code TgMidlet.restoreScreen} re-adopts whichever ChatScreen is topmost on
 * the navigation stack without resetting it. Opening a forwarded message's
 * source pushes a second chat over the first, so Back could return to a chat
 * carrying the other one's high-water mark - and a channel's message ids say
 * nothing at all about a one-to-one chat.
 *
 * None of that is reachable from a desktop suite: {@code TgMidlet} is a MIDlet
 * and is only ever instantiated by the emulator harness. What *is* reachable is
 * the state, which is why it became a value bound to its peer - the same move
 * {@link tg.app.ComposerState} made for the composer. These cases drive the
 * sequences from the handoff through the same object {@code TgMidlet} holds, and
 * through the same {@link PageMerge} calls that do the eviction in production.
 *
 * The wiring - that every peer change reaches {@code rebindReadMark} - is a
 * review property, not one this suite can observe.
 */
public final class ReadMarkTest implements Test
{
    /** A page of history, as {@code MemoryBudget.historyPageSize()} sizes it. */
    private static final int PAGE = 40;

    /** What the retained window holds, as {@code MemoryBudget.maxHistory()} does. */
    private static final int WINDOW = 60;

    /** 2026-01-01T00:00:00Z, so the ordering merge has real dates to sort on. */
    private static final int BASE_DATE = 1767225600;

    public String name() { return "ui/read-mark"; }

    public void run()
    {
        theNewestIdSurvivesTheWindowSlidingOffIt();
        aDialogTopAboveTheHistoryWins();
        retainedHistoryAboveStaleDialogMetadataWins();
        nothingButAMessageIdIsAMark();
        theMarkBelongsToOneChat();
        theMarkOnlyEverMovesForward();
        bothKindsOfChatStillCarryTheIdWeChose();
    }

    // ------------------------------------------------------- the sliding window

    /**
     * The defect, end to end: open the newest history, read far enough back that
     * the newest messages are evicted, lose the dialog out of the retained chat
     * list as well, and press Mark all read.
     */
    private static void theNewestIdSurvivesTheWindowSlidingOffIt()
    {
        Peer channel = channel(1001, "Announcements");
        ReadMark mark = ReadMark.forPeer(channel);

        // Opening the chat: the newest page, as loadOpenHistory installs it.
        Message[] retained = page(5000, PAGE);
        int newest = retained[0].id;
        mark.note(retained);
        Assert.equal("opening a chat records the newest message", newest,
                mark.newestKnownId());

        // Reading backwards. This is mergeHistoryPage: merge, note the merged
        // set *before* windowing, then slide the window onto what is being read,
        // which while paging back is the oldest end.
        for (int i = 0; i < 8; i++)
        {
            Message[] older = page(retained[retained.length - 1].id - 1, PAGE);
            Message[] merged = PageMerge.merge(retained, older);
            mark.note(merged);
            retained = PageMerge.window(merged, merged.length - 1, WINDOW);
        }

        Assert.equal("the window is still the size it is bounded to", WINDOW,
                retained.length);
        Assert.isTrue("the newest message has been evicted from the window",
                retained[0].id < newest);
        Assert.isTrue("and by a long way, not a rounding error",
                newest - retained[0].id > PAGE);

        // The chat list is a window too, and this conversation is no longer in
        // it: findDialog answers -1, so there is no topMessageId to fall back to.
        int dialogTop = 0;

        Assert.isTrue("the old computation marks read behind the reader: "
                        + legacyMaxId(dialogTop, retained) + " of " + newest,
                legacyMaxId(dialogTop, retained) < newest);
        Assert.equal("the mark still knows what the newest message is", newest,
                mark.newestKnownId());
        Assert.equal("mark all read submits it rather than the head of the window",
                newest,
                ReadMark.highest(mark.newestKnownId(), dialogTop, retained));
    }

    // --------------------------------------------------------- the three sources

    /**
     * The dialog row is the server's word on where the conversation ends, and it
     * runs ahead of anything fetched while the chat has been sitting open.
     */
    private static void aDialogTopAboveTheHistoryWins()
    {
        Message[] retained = page(500, 10);
        Assert.equal("the retained history reaches 500", 500, retained[0].id);

        Assert.equal("a dialog that has moved on wins", 900,
                ReadMark.highest(500, 900, retained));
        Assert.equal("and so does one with nothing retained at all", 900,
                ReadMark.highest(0, 900, new Message[0]));
    }

    /**
     * And the other way round. The retained chat list is refreshed on a timer,
     * so between refreshes its {@code topMessageId} is the stale one.
     */
    private static void retainedHistoryAboveStaleDialogMetadataWins()
    {
        // Deliberately not in id order: the answer is a numeric maximum, and
        // nothing here may assume the caller handed over a sorted array.
        Message[] retained = new Message[] {
            message(120), message(517), message(43), message(300)
        };

        Assert.equal("the newest retained message wins over stale metadata", 517,
                ReadMark.highest(0, 100, retained));
        Assert.equal("a mark already ahead of both wins over both", 900,
                ReadMark.highest(900, 100, retained));

        // The same array through note(), which is the path the client takes.
        ReadMark mark = ReadMark.forPeer(user(10, "Anna"));
        mark.note(retained);
        Assert.equal("note() finds the maximum, not the first element", 517,
                mark.newestKnownId());
    }

    /**
     * Only a server message id is a mark. A queued send has none - it is an
     * {@code OutgoingMessage} and never enters the retained history - so
     * anything that is not a positive id is a hole in the array, not a message.
     */
    private static void nothingButAMessageIdIsAMark()
    {
        Message[] holes = new Message[] { null, message(0), message(-7), null };

        Assert.equal("nothing to mark is nothing to submit", 0,
                ReadMark.highest(0, 0, holes));
        Assert.equal("an empty history is nothing to submit", 0,
                ReadMark.highest(0, 0, new Message[0]));
        Assert.equal("and neither is no history at all", 0,
                ReadMark.highest(0, 0, null));
        Assert.equal("a negative dialog top is not an id", 0,
                ReadMark.highest(0, -3, null));
        Assert.equal("nor is a negative mark", 0,
                ReadMark.highest(-9, 0, null));
        Assert.equal("a real message beside the holes is still found", 44,
                ReadMark.highest(0, 0, new Message[] { null, message(44), message(-7) }));

        ReadMark mark = ReadMark.forPeer(user(10, "Anna"));
        Assert.isFalse("a page of holes does not move the mark", mark.note(holes));
        Assert.equal("and leaves nothing to submit", 0, mark.newestKnownId());
    }

    // ------------------------------------------------------------- the ownership

    /**
     * The navigation stack can hold two chats at once: opening a forwarded
     * message's source pushes one over the chat it was read in, and Back
     * re-adopts the one underneath. A mark taken in the channel must not be able
     * to mark the conversation below it read up to a channel message id.
     */
    private static void theMarkBelongsToOneChat()
    {
        Peer anna = user(10, "Anna");
        Peer announcements = channel(1001, "Announcements");

        ReadMark inChannel = ReadMark.forPeer(announcements);
        inChannel.note(page(900000, 3));
        Assert.equal("the channel's mark is the channel's newest", 900000,
                inChannel.newestKnownId());

        Assert.isTrue("owned by the chat it was taken in",
                inChannel.ownedBy(announcements));
        Assert.isFalse("not by the chat underneath it on the stack",
                inChannel.ownedBy(anna));
        Assert.equal("so it offers that chat nothing to mark read", 0,
                ReadMark.newestKnownIdFor(inChannel, anna));
        Assert.equal("and no conversation at all offers nothing either", 0,
                ReadMark.newestKnownIdFor(null, anna));
        Assert.equal("while its own chat still gets the real answer", 900000,
                ReadMark.newestKnownIdFor(inChannel, announcements));

        // Peer is mutable with public fields and the same conversation arrives as
        // a fresh instance from every dialog page, so ownership is the kind and
        // id copied at capture - the pair TgMidlet.samePeer compares.
        ReadMark forAnna = ReadMark.forPeer(anna);
        Assert.isTrue("a later instance of the same chat is the owner",
                forAnna.ownedBy(user(10, "Anna Smith")));
        Assert.isFalse("the same id in another kind of chat is not",
                forAnna.ownedBy(new Peer(Peer.CHAT, 10)));
        Assert.isFalse("nothing is owned by no chat", forAnna.ownedBy(null));

        anna.id = 11;
        Assert.isFalse("mutating the captured peer does not move ownership",
                forAnna.ownedBy(anna));
        Assert.isTrue("ownership stayed where it was captured",
                forAnna.ownedBy(user(10, "Anna")));

        Assert.isTrue("a mark without a chat is not a mark",
                ReadMark.forPeer(null) == null);
    }

    /**
     * Merging an older page must not walk the mark backwards - which is the
     * reason it cannot simply be recomputed from what is retained.
     */
    private static void theMarkOnlyEverMovesForward()
    {
        ReadMark mark = ReadMark.forPeer(user(10, "Anna"));

        Assert.isTrue("the first page moves the mark", mark.note(page(500, 10)));
        Assert.equal("to the newest message in it", 500, mark.newestKnownId());

        Assert.isFalse("an older page does not move it", mark.note(page(300, 10)));
        Assert.equal("and does not lower it", 500, mark.newestKnownId());

        Assert.isFalse("neither does the same page again",
                mark.note(page(500, 10)));
        Assert.equal("still the newest seen", 500, mark.newestKnownId());

        Assert.isTrue("a live message moves it again", mark.note(page(501, 1)));
        Assert.equal("to the new newest", 501, mark.newestKnownId());
    }

    // ---------------------------------------------------------------- the wire

    /**
     * Which request a chat gets is not this class's decision and did not change:
     * a channel reads through {@code channels.readHistory} and everything else
     * through {@code messages.readHistory}. Both carry the id chosen here as the
     * trailing {@code max_id}, which is what pins the two together.
     */
    private static void bothKindsOfChatStillCarryTheIdWeChose()
    {
        Peer anna = user(10, "Anna");
        Peer announcements = channel(1001, "Announcements");
        int maxId = 900000;

        byte[] ordinary = Requests.readHistory(anna, maxId);
        Assert.equal("an ordinary chat reads through messages.readHistory",
                Api.MESSAGES_READ_HISTORY, intAt(ordinary, 0));
        Assert.equal("carrying the id that was chosen", maxId,
                intAt(ordinary, ordinary.length - 4));

        byte[] broadcast = Requests.readChannelHistory(announcements, maxId);
        Assert.equal("a channel reads through channels.readHistory",
                Api.CHANNELS_READ_HISTORY, intAt(broadcast, 0));
        Assert.equal("carrying the same id", maxId,
                intAt(broadcast, broadcast.length - 4));

        Assert.isTrue("the two are not the same request",
                Api.MESSAGES_READ_HISTORY != Api.CHANNELS_READ_HISTORY);
    }

    // ---------------------------------------------------------------- fixtures

    /** {@code markAllReadNow} before this change: the dialog, then the array head. */
    private static int legacyMaxId(int dialogTopMessageId, Message[] retained)
    {
        int maxId = dialogTopMessageId;
        if (maxId <= 0 && retained.length > 0) { maxId = retained[0].id; }
        return maxId;
    }

    /** A page of history, newest first, ending at {@code newestId}. */
    private static Message[] page(int newestId, int count)
    {
        Message[] out = new Message[count];
        for (int i = 0; i < count; i++)
        {
            out[i] = message(newestId - i);
        }
        return out;
    }

    private static Message message(int id)
    {
        Message m = new Message();
        m.id = id;
        m.date = BASE_DATE + id;
        m.text = "message number " + id;
        return m;
    }

    private static Peer user(long id, String title)
    {
        Peer p = new Peer(Peer.USER, id);
        p.accessHash = 0x5eed0000L + id;
        p.title = title;
        return p;
    }

    private static Peer channel(long id, String title)
    {
        Peer p = new Peer(Peer.CHANNEL, id);
        p.accessHash = 0x5eed0000L + id;
        p.title = title;
        return p;
    }

    /** Little-endian int, which is how TL writes one. */
    private static int intAt(byte[] bytes, int offset)
    {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | (bytes[offset + 3] << 24);
    }
}
