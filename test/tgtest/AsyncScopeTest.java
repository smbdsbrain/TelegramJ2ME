package tgtest;

import tg.api.Peer;
import tg.app.AsyncScope;

/**
 * Whether a result that took four seconds to arrive still belongs anywhere.
 *
 * Serializing callbacks onto the display thread decided <em>when</em> they are
 * applied. It did not decide <em>whether</em> they should be, and on GPRS the
 * gap is long enough to log out, sign in as someone else, or open two other
 * chats. The result then arrives correct and complete and about a world that is
 * gone - and the code applying it had no way to tell.
 *
 * The shapes are specific. A {@code messages.getDialogs} asked for before a
 * logout repopulating the list and resetting the navigation root on top of the
 * phone box. A delete stripping id 4711 from whichever transcript happens to be
 * open, because message ids are unique per peer and not globally. A profile
 * pushed over a chat the user opened while it loaded.
 *
 * What cannot be tested here is the wiring - that every submission captures and
 * every callback asks. That is a review property, the way {@code ComposerState}
 * documents about its own. What is testable is the rule the wiring encodes, and
 * the decision inside it that is easy to get wrong in the safe-looking
 * direction: a reader who backs out of a chat and comes straight in again must
 * not lose the page that was already on its way to them.
 *
 * Every case below is written as the navigation sequence that produces it,
 * through {@link #open}, because a generation is only meaningful against the
 * order the screens actually move in.
 */
public final class AsyncScopeTest implements Test
{
    public String name() { return "app/async-scope"; }

    public void run() throws Exception
    {
        aFreshCaptureIsCurrent();
        aLogoutInvalidatesEverythingInFlight();
        openingAnotherChatInvalidatesTheFirst();
        closingAChatIsEnoughOnItsOwn();
        comingStraightBackToTheSameChatKeepsItsPageInFlight();
        aChatIsNotTheSameChatUnderAnotherAccount();
        anAccountLevelRequestIgnoresTheChat();
        peerIdentityIsKindAndId();
        aDetourThroughAnotherChatIsASecondVisit();
        theFirstChatEverOpenedIsNotAChange();
    }

    private static Peer user(long id)
    {
        return new Peer(Peer.USER, id);
    }

    /** What {@code TgMidlet.bindOpenPeer} does; null means the chat closed. */
    private static void open(AsyncScope scope, Peer peer)
    {
        scope.chatChanged(peer, 0);
    }

    private static void aFreshCaptureIsCurrent()
    {
        AsyncScope scope = new AsyncScope();
        Peer chat = user(7);
        open(scope, chat);
        AsyncScope.Token asked = scope.capture(chat, 0);

        Assert.isTrue("nothing has happened, so the session holds",
                asked.sameSession());
        Assert.isTrue("and so does the chat", asked.sameChat(chat, 0));
        Assert.isTrue("the token remembers what it was asked for",
                asked.peer() == chat);
    }

    /**
     * The one that matters most. Everything in flight belonged to the account
     * that no longer exists on this phone.
     */
    private static void aLogoutInvalidatesEverythingInFlight()
    {
        AsyncScope scope = new AsyncScope();
        Peer chat = user(7);
        open(scope, chat);
        AsyncScope.Token dialogs = scope.capture();
        AsyncScope.Token history = scope.capture(chat, 0);

        scope.newSession();

        Assert.isFalse("a dialog page from the previous account is stale",
                dialogs.sameSession());
        Assert.isFalse("so is a history page", history.sameSession());
        Assert.isFalse("and it fails the chat check too, even against the same"
                + " peer object", history.sameChat(chat, 0));
    }

    private static void openingAnotherChatInvalidatesTheFirst()
    {
        AsyncScope scope = new AsyncScope();
        Peer first = user(7);
        Peer second = user(9);
        open(scope, first);
        AsyncScope.Token asked = scope.capture(first, 0);

        open(scope, second);

        Assert.isTrue("the account did not change", asked.sameSession());
        Assert.isFalse("but the chat did", asked.sameChat(second, 0));
        Assert.isFalse("and the first chat is no longer the open one",
                asked.sameChat(first, 0));
    }

    /**
     * Closing needs no generation of its own: a token holds a peer, and a peer
     * never matches a null current one. Bumping anyway would be free here and
     * is exactly what breaks the next case.
     */
    private static void closingAChatIsEnoughOnItsOwn()
    {
        AsyncScope scope = new AsyncScope();
        Peer chat = user(7);
        open(scope, chat);
        AsyncScope.Token asked = scope.capture(chat, 0);

        open(scope, null);

        Assert.isFalse("a history page for a chat nobody is in is stale",
                asked.sameChat(null, 0));
        Assert.isTrue("but the session is untouched", asked.sameSession());
    }

    /**
     * A reader who leaves a chat and comes straight back should not have to
     * press Refresh. This is not a rare path: every pop back onto a
     * conversation rebinds the peer, and the dialog list closes the chat on the
     * way past, so a generation that moved on either would throw away the page
     * that conversation is still waiting for.
     */
    private static void comingStraightBackToTheSameChatKeepsItsPageInFlight()
    {
        AsyncScope scope = new AsyncScope();
        Peer chat = user(7);
        open(scope, chat);
        AsyncScope.Token asked = scope.capture(chat, 0);

        open(scope, null);
        open(scope, chat);

        Assert.isTrue("the page is still for the chat that is open",
                asked.sameChat(chat, 0));

        // And again with a fresh Peer instance, because every response builds
        // new ones and reference equality would say no.
        Assert.isTrue("identity, not reference", asked.sameChat(user(7), 0));
    }

    private static void aChatIsNotTheSameChatUnderAnotherAccount()
    {
        AsyncScope scope = new AsyncScope();
        Peer chat = user(7);
        open(scope, chat);
        AsyncScope.Token asked = scope.capture(chat, 0);

        scope.newSession();
        open(scope, user(7));

        Assert.isFalse("same id, different account, different conversation",
                asked.sameChat(user(7), 0));
    }

    private static void anAccountLevelRequestIgnoresTheChat()
    {
        AsyncScope scope = new AsyncScope();
        AsyncScope.Token asked = scope.capture();

        open(scope, user(1));
        open(scope, user(2));

        Assert.isTrue("a dialog page does not care which chat is open",
                asked.sameSession());
        Assert.isTrue("it was not captured for one", asked.peer() == null);
        Assert.isFalse("and it can never satisfy the chat check",
                asked.sameChat(user(2), 0));
    }

    private static void peerIdentityIsKindAndId()
    {
        Assert.isTrue("same kind and id",
                AsyncScope.samePeer(user(7), user(7)));
        Assert.isFalse("different id",
                AsyncScope.samePeer(user(7), user(8)));
        Assert.isFalse("a user 7 is not a channel 7",
                AsyncScope.samePeer(user(7), new Peer(Peer.CHANNEL, 7)));
        Assert.isFalse("null is nobody", AsyncScope.samePeer(null, user(7)));
        Assert.isFalse("in either position", AsyncScope.samePeer(user(7), null));
        Assert.isFalse("not even two nulls - there is no conversation there",
                AsyncScope.samePeer(null, null));

        // access_hash is a credential for reaching a peer, not part of what
        // makes it that peer, and it is refreshed while the chat stays the same.
        Peer withHash = user(7);
        withHash.accessHash = 0x1234;
        Assert.isTrue("a refreshed access_hash is the same conversation",
                AsyncScope.samePeer(user(7), withHash));
    }

    /**
     * A page asked for during the first visit is not applied after a detour
     * through another chat, even though the peer matches again. That one is
     * worth losing: it was requested against paging offsets the second visit
     * does not share.
     */
    private static void aDetourThroughAnotherChatIsASecondVisit()
    {
        AsyncScope scope = new AsyncScope();
        Peer first = user(7);
        Peer second = user(9);
        open(scope, first);
        AsyncScope.Token asked = scope.capture(first, 0);

        open(scope, second);
        open(scope, first);

        Assert.isTrue("the account never moved", asked.sameSession());
        Assert.isFalse("but this is a second visit, not the first",
                asked.sameChat(first, 0));
    }

    /**
     * Opening the very first chat of a session is not a change of chat. It
     * matters because a dialog page requested from the list - before any chat
     * exists - would otherwise be discarded the moment the user opened one.
     */
    private static void theFirstChatEverOpenedIsNotAChange()
    {
        AsyncScope scope = new AsyncScope();
        AsyncScope.Token dialogs = scope.capture();

        open(scope, user(7));

        Assert.isTrue("the dialog page still applies", dialogs.sameSession());

        // Same after a logout: the mark is cleared with everything else, so the
        // first chat of the next session is a first chat again.
        scope.newSession();
        AsyncScope.Token next = scope.capture();
        open(scope, user(9));
        Assert.isTrue("and again in the next session", next.sameSession());
    }
}
