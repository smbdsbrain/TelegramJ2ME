package tg.mt;

/**
 * The outcome of asking storage for a stored authorization key.
 *
 * The distinction this class exists to make is between "there is no key for
 * this data centre", "there is one and it is damaged" and "the store could not
 * be read at all". All three used to arrive at the connect path as a null
 * {@link AuthKey}, and the connect path treats a missing key as a first launch:
 * it runs a full handshake and stores the result over whatever was there. So a
 * single unreadable {@code tgkeys} - a store the AMS had not finished mounting,
 * a record system busy behind a media scan - was indistinguishable from a fresh
 * install, and the user was signed out with a perfectly good key still on the
 * handset.
 *
 * <h3>Carrying no key material</h3>
 * {@link #detail} describes the <em>shape</em> of what was wrong - a length, an
 * exception class - and never the value. It reaches diagnostics and the crash
 * log, both of which can be uploaded, and an auth_key is the session.
 *
 * Modelled on {@code tg.api.AuthCheck}, which makes the same three-way
 * distinction for the authorization question one layer up.
 */
public final class AuthKeyLoad
{
    /** A key was stored, validated and decoded. */
    public static final int FOUND = 1;
    /** Nothing is stored for this data centre and environment. */
    public static final int NOT_FOUND = 2;
    /** Something is stored, and it is not a usable key. */
    public static final int CORRUPT = 3;
    /** The store could not be read. Says nothing about what it holds. */
    public static final int IO_ERROR = 4;

    /** Non-null exactly when {@link #outcome} is {@link #FOUND}. */
    public final AuthKey key;
    public final int outcome;
    /** Why, for diagnostics. Never null, never carries key material. */
    public final String detail;

    private AuthKeyLoad(AuthKey key, int outcome, String detail)
    {
        this.key = key;
        this.outcome = outcome;
        this.detail = detail == null ? "" : detail;
    }

    public static AuthKeyLoad found(AuthKey key)
    {
        return new AuthKeyLoad(key, FOUND, key.describe());
    }

    public static AuthKeyLoad notFound()
    {
        return new AuthKeyLoad(null, NOT_FOUND, "no key stored");
    }

    /** @param detail the shape of the damage, never the stored value */
    public static AuthKeyLoad corrupt(String detail)
    {
        return new AuthKeyLoad(null, CORRUPT, detail);
    }

    public static AuthKeyLoad ioError(String detail)
    {
        return new AuthKeyLoad(null, IO_ERROR, detail);
    }

    public boolean isFound()    { return outcome == FOUND; }
    public boolean isNotFound() { return outcome == NOT_FOUND; }
    public boolean isCorrupt()  { return outcome == CORRUPT; }
    public boolean isIoError()  { return outcome == IO_ERROR; }

    public String describe()
    {
        return name(outcome) + ": " + detail;
    }

    public static String name(int outcome)
    {
        if (outcome == FOUND)     { return "found"; }
        if (outcome == NOT_FOUND) { return "not stored"; }
        if (outcome == CORRUPT)   { return "corrupt"; }
        return "unreadable";
    }
}
