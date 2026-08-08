package tg.app;

import tg.api.Peer;

/**
 * Which account, and which chat, an asynchronous result was asked for.
 *
 * <h3>The problem</h3>
 * Serializing callbacks onto the display thread decides <em>when</em> a result
 * is applied. It does not decide <em>whether</em> it still should be. A
 * {@code messages.getDialogs} on GPRS takes seconds, and in those seconds the
 * user can log out, sign in as someone else, or open a different chat. The
 * result then arrives correct, complete, and about a world that no longer
 * exists - and the code that applies it has no way to tell.
 *
 * The shapes that produces are specific rather than theoretical: a dialog list
 * repopulated over the phone box after a logout; a delete that strips a message
 * from whichever transcript is open, because message ids are only unique per
 * peer; a profile screen pushed on top of a chat the user opened while it
 * loaded.
 *
 * <h3>The mechanism</h3>
 * Two counters and a captured peer. {@link #capture} takes them at submit time,
 * the callback asks the resulting {@link Token} whether they still hold, and
 * drops its UI work if they do not.
 *
 * <ul>
 * <li><b>session</b> - bumped when the signed-in account changes: a local
 *     logout, a wipe, a different authorization becoming active. Everything
 *     that touches the model is scoped to it.</li>
 * <li><b>chat</b> - bumped when a <em>different</em> chat becomes the open one.
 *     Not on close, and not on rebinding to the same chat: an in-flight history
 *     page for the chat the user is still in is not stale, and a reader who
 *     leaves and comes straight back should not have to press Refresh. Closing
 *     is covered without a bump, because the peer identity in the token no
 *     longer matches a null current peer.</li>
 * </ul>
 *
 * A session change bumps the chat counter too - a chat under one account is not
 * the same chat under another, even at the same id.
 *
 * <h3>Not a cancellation mechanism</h3>
 * A stale result still finishes, and its durable half still counts. An outbox
 * row that reached RMS stays there whether or not the composer it came from is
 * still open; what the guard stops is the <em>screen</em> half - navigating,
 * appending to the wrong transcript, clearing another request's latch. Deciding
 * those separately is the point, not an oversight.
 *
 * <h3>Wraparound</h3>
 * Signed ints, incremented, compared for equality. A session bump costs a
 * logout or a sign-in; a chat bump costs opening a different conversation.
 * Reaching 2^31 needs one of those every second for 68 years, and equality
 * comparison means even then only a token captured at exactly the wrapped value
 * would be mistaken for current. Not persisted: process restart starts from
 * zero, and nothing outlives the process to be confused by it.
 *
 * <h3>Threading</h3>
 * Every mutation happens on the display thread, which is where the state it
 * describes lives. A {@link Token} is immutable and can be read anywhere.
 */
public final class AsyncScope
{
    private volatile int session;
    private volatile int chat;

    /**
     * The last conversation that was open, as kind and id rather than as a
     * {@link Peer}.
     *
     * Two ints, not the object: a {@code Peer} carries a contact's name, and a
     * logout is supposed to leave none of those behind. {@code kind} is -1 when
     * no chat has been open in this session yet.
     */
    private int lastKind = -1;
    private long lastId;

    /**
     * The context an asynchronous request was made in.
     *
     * Two ints and a peer - deliberately not a screen, a message array or a
     * page. A token can outlive everything it was captured beside, so it must
     * not be what keeps any of it alive.
     */
    public static final class Token
    {
        private final AsyncScope scope;
        private final int session;
        private final int chat;
        private final Peer peer;

        Token(AsyncScope scope, int session, int chat, Peer peer)
        {
            this.scope = scope;
            this.session = session;
            this.chat = chat;
            this.peer = peer;
        }

        /** The peer this was captured for, or null when it was not about one. */
        public Peer peer()
        {
            return peer;
        }

        /**
         * Is the same account still signed in?
         *
         * The weaker of the two questions, and the right one for anything that
         * belongs to the session rather than to a conversation: a pagination
         * latch, an account-level alert, a dialog page.
         */
        public boolean sameSession()
        {
            return session == scope.session;
        }

        /**
         * Same account, same open chat, and the chat has not been swapped for
         * another and back.
         *
         * @param open the chat that is open now, usually {@code openPeer}
         */
        public boolean sameChat(Peer open)
        {
            return sameSession() && chat == scope.chat && samePeer(peer, open);
        }
    }

    /** The context to guard a request about {@code peer} with; null for none. */
    public Token capture(Peer peer)
    {
        return new Token(this, session, chat, peer);
    }

    /** The context to guard an account-level request with. */
    public Token capture()
    {
        return capture(null);
    }

    /**
     * A different account is now signed in, or none is.
     *
     * Called before the local state of the old one is torn down, so that a
     * result already on its way cannot rebuild any of it afterwards.
     */
    public void newSession()
    {
        session++;
        chat++;
        lastKind = -1;
        lastId = 0;
    }

    /**
     * {@code to} is now the open chat, or nothing is when it is null.
     *
     * Only a move to a <em>different</em> conversation bumps.
     *
     * Closing does not, and neither does reopening the same chat afterwards.
     * That is why the mark survives a null rather than being cleared by it: a
     * reader who backs out of a chat and comes straight in again - or lands
     * there through the dialog list, which closes the chat on the way past -
     * would otherwise have to press Refresh for a page that was already on its
     * way to them. Closing needs no generation of its own: a token holds a
     * peer, and a peer never matches a null current one.
     *
     * A detour <em>through</em> another chat does bump, and the page from the
     * first visit is then dropped even though the peer matches again. That one
     * is worth losing: it was requested against paging offsets the second visit
     * does not share.
     */
    public void chatChanged(Peer to)
    {
        if (to == null) { return; }
        if (lastKind >= 0 && !(lastKind == to.kind && lastId == to.id))
        {
            chat++;
        }
        lastKind = to.kind;
        lastId = to.id;
    }

    /**
     * The same conversation, by identity rather than by reference.
     *
     * Peer instances are rebuilt from every response, so reference equality
     * says nothing. Kind and id are what Telegram addresses a conversation by;
     * access_hash is a credential for reaching it, not part of its identity,
     * and it can be refreshed while the conversation stays the same.
     */
    public static boolean samePeer(Peer a, Peer b)
    {
        return a != null && b != null && a.kind == b.kind && a.id == b.id;
    }
}
