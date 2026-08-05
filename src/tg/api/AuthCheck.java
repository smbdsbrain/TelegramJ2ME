package tg.api;

/**
 * The outcome of asking Telegram whether a stored session still works.
 *
 * The distinction this class exists to make is between "the server told us this
 * key has no account" and "we never got an answer". Both used to arrive at the
 * UI as a null {@code Peer}, so a single lost RPC on a slow link was
 * indistinguishable from a logged-out account and sent the user back to the
 * phone-number screen with a perfectly good auth_key still in RMS.
 *
 * Measured on a Nokia C3-00 through an MTProxy: socket open took 9-18 s and
 * {@code users.getSelf} did not answer inside the 60 s reply timeout, on a
 * launch where the stored key had loaded and {@code help.getConfig} had already
 * succeeded through the same route.
 */
public final class AuthCheck
{
    /** The server answered and the session is usable. */
    public static final int YES = 1;
    /** The server answered: this key carries no account, or no longer does. */
    public static final int NO = 2;
    /** No usable answer. Says nothing about the session either way. */
    public static final int UNKNOWN = 3;

    /** Non-null exactly when {@link #verdict} is {@link #YES}. */
    public final Peer peer;
    public final int verdict;
    /** Why, for diagnostics. Never null. */
    public final String detail;
    /**
     * What stopped the check. Non-null exactly when {@link #verdict} is
     * {@link #UNKNOWN}. Carried rather than flattened to text so the UI can
     * name the class - "timed out" and "connection reset" are different things
     * to a person deciding whether to retry.
     */
    public final java.io.IOException error;

    private AuthCheck(Peer peer, int verdict, String detail,
                      java.io.IOException error)
    {
        this.peer = peer;
        this.verdict = verdict;
        this.detail = detail == null ? "" : detail;
        this.error = error;
    }

    public static AuthCheck yes(Peer peer)
    {
        return new AuthCheck(peer, YES, "signed in", null);
    }

    public static AuthCheck no(String detail)
    {
        return new AuthCheck(null, NO, detail, null);
    }

    public static AuthCheck unknown(java.io.IOException error)
    {
        // Never null for UNKNOWN: the UI hands this straight to an error screen
        // that names the class, and a null there would fail while reporting a
        // failure.
        java.io.IOException cause = error != null ? error
                : new java.io.IOException("no answer");
        return new AuthCheck(null, UNKNOWN, String.valueOf(cause.getMessage()),
                             cause);
    }

    public boolean isYes()     { return verdict == YES; }
    public boolean isNo()      { return verdict == NO; }
    public boolean isUnknown() { return verdict == UNKNOWN; }

    public String describe()
    {
        return name(verdict) + ": " + detail;
    }

    public static String name(int verdict)
    {
        if (verdict == YES) { return "authorized"; }
        if (verdict == NO)  { return "not authorized"; }
        return "inconclusive";
    }
}
