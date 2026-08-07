package tgtest;

import tg.api.MemoryDraftStore;
import tg.api.Peer;
import tg.app.ComposerState;

/**
 * Transient composer state belongs to the chat it was opened for.
 *
 * The defect this covers was three fields in {@code TgMidlet} that nothing tied
 * together: a {@code Message replyTarget}, the mutable {@code openPeer} and a
 * reused {@code TextBox}. Reply in one chat, press Send on an empty box - the
 * one exit that cleared nothing - walk to another chat, press Write, and the
 * composer came up still holding the first chat's message id. Sending then put
 * chat A's reply id on a message addressed to chat B.
 *
 * None of that is reachable from a desktop suite: the composer is an lcdui
 * {@code TextBox} on a screen stack inside a MIDlet, and showing one needs a
 * signed-in session and two conversations. What *is* reachable is the state
 * itself, which is why it became a value class. These cases drive the exact
 * sequences from the handoff through the same object {@code TgMidlet} holds.
 *
 * The wiring - that every exit path assigns null and every open assigns a fresh
 * value - is a review property, not one this suite can observe.
 */
public final class ComposerStateTest implements Test
{
    public String name() { return "ui/composer-state"; }

    public void run() throws Exception
    {
        writeStartsWithoutAReply();
        replyCapturesThePeerAndTheMessage();
        invalidOpensAreRefused();
        blankSendThenWriteCarriesNoReply();
        aReplyIsNotOwnedByAnotherChat();
        identityIsFrozenAtCapture();
        theReplyLabelDoesNotNeedTheMessage();
        aRefusedSendKeepsTheStateForRetry();
        draftsFollowTheComposerOwner();
    }

    // ------------------------------------------------------------ the shape

    private static void writeStartsWithoutAReply()
    {
        Peer anna = user(10, "Anna");
        ComposerState composer = ComposerState.write(anna);

        Assert.equal("write carries no reply id", 0, composer.replyToMessageId());
        Assert.equal("write has the ordinary title", "Message", composer.title());
        Assert.isTrue("write is owned by the chat it was opened for",
                composer.ownedBy(anna));
        Assert.isTrue("write keeps the peer it can address",
                composer.peer() == anna);
    }

    private static void replyCapturesThePeerAndTheMessage()
    {
        Peer anna = user(10, "Anna");
        ComposerState composer = ComposerState.reply(anna, 100);

        Assert.equal("reply carries the message id", 100,
                composer.replyToMessageId());
        Assert.equal("reply names the message it answers", "Reply to #100",
                composer.title());
        Assert.isTrue("reply is owned by the chat it was opened for",
                composer.ownedBy(anna));
    }

    private static void invalidOpensAreRefused()
    {
        Peer anna = user(10, "Anna");

        // A composer with no chat to send to has nothing to be bound to, and a
        // non-positive id is not a message - both are refused at the factory so
        // no caller has to remember to check.
        Assert.isTrue("write without a chat is refused",
                ComposerState.write(null) == null);
        Assert.isTrue("reply without a chat is refused",
                ComposerState.reply(null, 100) == null);
        Assert.isTrue("reply to message 0 is refused",
                ComposerState.reply(anna, 0) == null);
        Assert.isTrue("reply to a negative id is refused",
                ComposerState.reply(anna, -1) == null);
    }

    // ------------------------------------------------------- the transitions

    /**
     * Reply -> blank Send -> Write, all in one chat. The blank Send is the exit
     * that used to clear nothing.
     */
    private static void blankSendThenWriteCarriesNoReply()
    {
        Peer anna = user(10, "Anna");

        ComposerState composer = ComposerState.reply(anna, 100);
        Assert.equal("armed with a reply", 100, composer.replyToMessageId());

        composer = null;                        // blank Send: closeComposer()
        composer = ComposerState.write(anna);   // Write

        Assert.equal("an ordinary Write after a reply carries no reply id", 0,
                composer.replyToMessageId());
        Assert.equal("an ordinary Write after a reply has a normal title",
                "Message", composer.title());
    }

    /**
     * Reply in chat A, then chat B. The reply state can neither name B nor be
     * sent to B: the send path refuses when the composer is not owned by the
     * chat that is open.
     */
    private static void aReplyIsNotOwnedByAnotherChat()
    {
        Peer anna = user(10, "Anna");
        Peer group = new Peer(Peer.CHAT, 77);

        ComposerState composer = ComposerState.reply(anna, 100);
        Assert.isTrue("owned by the chat it was opened in", composer.ownedBy(anna));
        Assert.isFalse("not owned by the chat that is open now",
                composer.ownedBy(group));
        Assert.isTrue("still addresses the chat it was opened in",
                composer.peer() == anna);

        composer = null;                         // navigation reset closes it
        composer = ComposerState.write(group);   // Write, now in the group

        Assert.isTrue("the new composer addresses the new chat",
                composer.peer() == group);
        Assert.equal("no reply id crossed the chat boundary", 0,
                composer.replyToMessageId());
        Assert.isFalse("and it is not owned by the previous chat",
                composer.ownedBy(anna));
    }

    /**
     * {@code Peer} is a mutable class with public fields, and the same
     * conversation arrives as a fresh instance from every dialog page. So
     * ownership compares the kind and id copied at capture, not the object.
     */
    private static void identityIsFrozenAtCapture()
    {
        Peer anna = user(10, "Anna");
        ComposerState composer = ComposerState.reply(anna, 100);

        Assert.isTrue("a later instance of the same chat is the owner",
                composer.ownedBy(user(10, "Anna Smith")));
        Assert.isFalse("the same id in another kind of chat is not",
                composer.ownedBy(new Peer(Peer.CHAT, 10)));
        Assert.isFalse("nothing is owned by no chat", composer.ownedBy(null));

        anna.id = 11;
        Assert.isFalse("mutating the captured peer does not move ownership",
                composer.ownedBy(anna));
        Assert.isTrue("ownership stayed where it was captured",
                composer.ownedBy(user(10, "Anna")));
    }

    /**
     * The label is built from the id alone, so it stays correct and bounded
     * after the message it answers has been evicted from the retained history
     * window - which is also why no {@code Message} is retained for it.
     */
    private static void theReplyLabelDoesNotNeedTheMessage()
    {
        Peer anna = user(10, "Anna");

        Assert.equal("largest possible id still renders",
                "Reply to #2147483647",
                ComposerState.reply(anna, Integer.MAX_VALUE).title());
        Assert.isTrue("the label is bounded",
                ComposerState.reply(anna, Integer.MAX_VALUE).title().length() <= 24);
    }

    /**
     * A refused or failed enqueue must not close the composer: the user's text
     * and their reply target have to survive for the retry.
     */
    private static void aRefusedSendKeepsTheStateForRetry()
    {
        Peer anna = user(10, "Anna");
        ComposerState composer = ComposerState.reply(anna, 100);

        // Worker.submit answered false; nothing was assigned.
        Assert.isTrue("the composer is still open", composer != null);
        Assert.equal("the reply target survives a refusal", 100,
                composer.replyToMessageId());
        Assert.isTrue("and still addresses the same chat", composer.ownedBy(anna));
    }

    // ------------------------------------------------------------- the draft

    /**
     * The draft is keyed on the chat the composer was opened for. It used to be
     * keyed on {@code openPeer}, which a background callback can move while the
     * three-second autosave thread is running.
     */
    private static void draftsFollowTheComposerOwner() throws Exception
    {
        MemoryDraftStore drafts = new MemoryDraftStore();
        Peer anna = user(10, "Anna");
        Peer group = new Peer(Peer.CHAT, 77);

        ComposerState composer = ComposerState.reply(anna, 100);

        // openPeer has moved on to the group; the autosave thread fires now.
        drafts.save(composer.peer(), "half a sentence");

        Assert.equal("the draft went to the chat being composed for",
                "half a sentence", drafts.load(anna));
        Assert.equal("and not to whichever chat is open now", "",
                drafts.load(group));
    }

    // ------------------------------------------------------------- helpers

    private static Peer user(long id, String title)
    {
        Peer p = new Peer(Peer.USER, id);
        p.accessHash = 0x5eed0000L + id;
        p.title = title;
        return p;
    }
}
