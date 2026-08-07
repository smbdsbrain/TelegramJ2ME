package tg.api;

import tg.mt.AuthKey;

/** Deterministic key/import policy for independent file transport sessions. */
public final class MediaAuthorization
{
    private MediaAuthorization() { }

    public static AuthKey select(int mainDc, int targetDc, AuthKey primary,
                                 AuthKey persisted)
    {
        return targetDc == mainDc && primary != null ? primary : persisted;
    }

    public static String markerName(int targetDc, boolean test)
    {
        return markerPrefix(test) + targetDc;
    }

    /**
     * What every import marker for one environment starts with.
     *
     * Logging out has to take these with the keys. A marker left behind says
     * "this data centre already holds an imported authorization for the key you
     * are about to use", and {@link #needsImport} believes it - so a marker that
     * outlives its account tells the <em>next</em> account not to import its
     * own, and its file requests travel on the previous one's session.
     */
    public static String markerPrefix(boolean test)
    {
        return "imported." + (test ? "test." : "prod.");
    }

    public static boolean needsImport(int mainDc, int targetDc, AuthKey key,
                                      String marker)
    {
        return targetDc != mainDc && key != null
                && !String.valueOf(key.keyId()).equals(marker);
    }
}
