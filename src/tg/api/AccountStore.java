package tg.api;

import java.io.IOException;

/**
 * Local storage whose entire contents belong to one signed-in account.
 *
 * Drafts, the outbox, the update cursors and the two offline caches each hold
 * only what the current account put there, so logging out means emptying all of
 * them - which is the one thing they have in common and the only thing
 * {@link AccountWipe} needs to know about them.
 *
 * Deliberately not implemented by {@code tg.mt.AuthKeyStore}. That store shares
 * its record store with the proxy settings, the theme and the measured heap
 * ceiling, so "clear it" is not an operation it can offer; the wipe names the
 * account-bound entries there explicitly instead.
 */
public interface AccountStore
{
    /**
     * Remove everything this store holds.
     *
     * Must succeed on a store that was never created: a wipe runs on handsets
     * where the user never opened a chat, and "there was nothing to delete" is
     * not a failure.
     */
    void clear() throws IOException;
}
