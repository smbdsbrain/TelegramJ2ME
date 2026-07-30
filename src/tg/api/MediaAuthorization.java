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
        return "imported." + (test ? "test." : "prod.") + targetDc;
    }

    public static boolean needsImport(int mainDc, int targetDc, AuthKey key,
                                      String marker)
    {
        return targetDc != mainDc && key != null
                && !String.valueOf(key.keyId()).equals(marker);
    }
}
