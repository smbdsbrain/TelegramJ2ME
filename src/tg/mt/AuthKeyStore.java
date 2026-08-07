package tg.mt;

/**
 * Persistence for authorization keys and the session state that goes with them.
 *
 * Generating an auth_key costs two 2048-bit modular exponentiations plus a
 * factorisation - the most expensive thing this client ever does, and on a
 * 208 MHz handset potentially tens of seconds. Doing it on every launch would
 * make the app unusable and would pile up abandoned keys in Telegram's records.
 * So the key is stored and reused, and this interface is what the MTProto layer
 * talks to.
 *
 * Implementations:
 * <ul>
 *   <li>{@code tg.plat.RmsAuthKeyStore} - RMS, on the device</li>
 *   <li>{@code tgtest.FileAuthKeyStore} - a file, for desktop live tests</li>
 * </ul>
 *
 * <h3>What a key is bound to</h3>
 * A key belongs to exactly one data centre in exactly one environment, so
 * lookups are keyed on both. Handing a production key to a test DC produces a
 * bare -404 with no explanation.
 *
 * <h3>Why loading returns an outcome rather than a key or null</h3>
 * "Nothing is stored" and "the store would not open" are the same value to a
 * caller that reads a null, and the connect path answers the first by running a
 * handshake and overwriting whatever is there. Conflating them turns one
 * transient storage failure into a lost session. See {@link AuthKeyLoad}.
 *
 * <h3>Confidentiality</h3>
 * RMS on a feature phone offers no encryption and no meaningful access control
 * beyond "other MIDlet suites cannot read it". Anyone with the handset in hand
 * and the right tools can extract the key, which is equivalent to having the
 * user's session. That is a real limitation, not a solved problem - it is
 * recorded in docs/architecture.md rather than papered over.
 */
public interface AuthKeyStore
{
    /**
     * @return what storage had to say about this data centre and environment;
     *         never null
     */
    AuthKeyLoad load(int dcId, boolean testEnvironment);

    void save(AuthKey key);

    /** Called when the server rejects a key as unregistered. */
    void clear(int dcId, boolean testEnvironment);

    /**
     * Opaque per-account state: the user id and which DC owns the account.
     * Stored alongside the keys so a restart can go straight to the right one.
     *
     * @return stored value, or null
     */
    String loadString(String name);

    void saveString(String name, String value);

    /**
     * Delete entries by exact name and by name prefix, and say whether they went.
     *
     * This exists for logging out. Keys, the account's data centre and the
     * connection settings share one store, so erasing an account cannot be a
     * delete of the store - and it cannot be a walk over the data centres this
     * build knows either, because the authoritative list arrives from
     * {@code help.getConfig} and a key is written for whichever data centre a
     * file happened to live in. A prefix sweep removes the ones nobody
     * enumerated, and naming the environment in the prefix keeps the other one -
     * a separate account, with its own key - out of it.
     *
     * The two forms are kept apart rather than folded into one: an exact name
     * must not be allowed to take a longer one that merely starts the same way,
     * or a setting added later disappears at the next logout.
     *
     * @param names    entries to delete outright; may be null or hold nulls
     * @param prefixes every entry whose name starts with one of these goes;
     *                 may be null or hold nulls
     * @return true when none of them remain; false when at least one could not
     *         be removed - which the caller must report rather than swallow
     */
    boolean clearEntries(String[] names, String[] prefixes);
}
