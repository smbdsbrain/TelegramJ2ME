package tg.api;

/**
 * Telegram's pagination hash, so a page the client already holds can come back
 * as "not modified" instead of as bytes.
 *
 * The algorithm is documented at
 * <a href="https://core.telegram.org/api/offsets#hash-generation">api/offsets</a>
 * and is reproduced here verbatim - the shifts, their order and the unsigned
 * right shift are all load-bearing, and a hash that is merely close is a hash
 * that never matches.
 *
 * What is <i>fed</i> into it is per-method and is documented only for some of
 * them. {@link #dialogs} carries its own note about which of those this client
 * has actually observed the server accept.
 */
public final class PageHash
{
    private PageHash() { }

    /**
     * Fold a vector of ids into the 64-bit pagination hash.
     *
     * <pre>
     *   hash = 0
     *   for id in ids:
     *       hash = hash ^ (hash &gt;&gt; 21)
     *       hash = hash ^ (hash &lt;&lt; 35)
     *       hash = hash ^ (hash &gt;&gt; 4)
     *       hash = hash + id
     * </pre>
     *
     * @param ids in the order the server returned them; order is part of the
     *            hash, so sorting them would produce a different answer
     */
    public static long fold(long[] ids)
    {
        long hash = 0;
        if (ids == null) { return hash; }
        for (int i = 0; i < ids.length; i++)
        {
            hash ^= hash >>> 21;
            hash ^= hash << 35;
            hash ^= hash >>> 4;
            hash += ids[i];
        }
        return hash;
    }

    /** Which vector {@link #dialogs} folds. Used by the driver's hash probe. */
    public static final int BY_TOP_MESSAGE = 0;
    public static final int BY_PEER = 1;
    public static final int BY_DIALOG_STATE = 2;

    /**
     * Hash of a held dialog list, for the {@code hash} field of
     * {@code messages.getDialogs}.
     *
     * Only meaningful for the offset-free first page: every later page carries
     * {@code (offset_date, offset_id, offset_peer)}, and a hash describes a list
     * from its start.
     *
     * The input vector is the part nobody documents. {@code api/offsets} says
     * "the IDs of the returned objects", which for a {@code Dialog} is
     * ambiguous - it has a peer, a top message and a read state, and no field
     * called id. Neither TDLib nor Telegram for Android settles it, because
     * neither sends a non-zero hash here at all: TDLib handles only
     * {@code savedDialogsNotModified} and logs an error on anything else. So
     * the candidates are enumerated rather than assumed, and which one the
     * server accepts is a measurement - {@code tgtest.LiveDialogHashTest},
     * reachable as {@code drive-emulator.ps1 -Scenario hashprobe}.
     *
     * <b>Measured, and the answer is none of them.</b> Production DC2 on
     * 2026-08-01 returned a full page to all three, against a control with
     * hash 0 that also returned full. So nothing here is wired into a request
     * yet: {@code Requests.getDialogs} sends 0 and says why. This class is kept
     * because the fold itself is certain and reusable, and because a future
     * attempt should start from a recorded negative rather than from the same
     * three guesses.
     *
     * @param mode one of the {@code BY_*} constants
     * @return the hash, or 0 for an empty list - which is also the value that
     *         asks the server for a full response
     */
    public static long dialogs(Dialog[] held, int mode)
    {
        if (held == null || held.length == 0) { return 0; }
        long[] ids = new long[mode == BY_DIALOG_STATE
                ? held.length * 4 : held.length];
        int w = 0;
        for (int i = 0; i < held.length; i++)
        {
            Dialog d = held[i];
            if (d == null || d.peer == null) { continue; }
            if (mode == BY_PEER)
            {
                ids[w++] = d.peer.id;
            }
            else if (mode == BY_DIALOG_STATE)
            {
                ids[w++] = d.pinned ? 1 : 0;
                ids[w++] = d.peer.id;
                ids[w++] = d.topMessageId;
                ids[w++] = d.unreadCount;
            }
            else
            {
                ids[w++] = d.topMessageId;
            }
        }
        if (w == ids.length) { return fold(ids); }
        long[] exact = new long[w];
        System.arraycopy(ids, 0, exact, 0, w);
        return fold(exact);
    }
}
