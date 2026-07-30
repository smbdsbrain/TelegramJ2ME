package tgtest;

import tg.mt.RsaKey;
import tg.mt.ServerKeys;

/**
 * Pins the shipped Telegram server RSA keys.
 *
 * These are the one piece of protocol data the desktop suite could otherwise
 * never check: {@link RsaKey} recomputes a fingerprint from the modulus rather
 * than storing it, and the handshake matches that against what resPQ names, so
 * a single mistyped hex digit changes the fingerprint and no key ever matches.
 * That failure only appears against a live data centre.
 *
 * The build also selects one array or the other from {@code BuildInfo.ENV}, so
 * whichever environment the suite happens to be built for, the other key set
 * is never exercised. This test deliberately parses both.
 *
 * The expected values are the fingerprints the production and test data centres
 * were observed offering, and must only change when
 * {@code python tools/fetch-server-keys.py} pulls new keys.
 */
public final class ServerKeysTest implements Test
{
    private static final long PRODUCTION_FINGERPRINT = -3414540481677951611L;
    private static final long TEST_FINGERPRINT = -5595554452916591101L;

    public String name() { return "mt/server-keys"; }

    public void run() throws Exception
    {
        Assert.equal("one production key ships", 1, ServerKeys.PRODUCTION_MODULUS.length);
        Assert.equal("one test key ships", 1, ServerKeys.TEST_MODULUS.length);

        RsaKey production = key(ServerKeys.PRODUCTION_MODULUS[0]);
        Assert.equal("production key fingerprint",
                     PRODUCTION_FINGERPRINT, production.fingerprint());

        RsaKey test = key(ServerKeys.TEST_MODULUS[0]);
        Assert.equal("test key fingerprint", TEST_FINGERPRINT, test.fingerprint());

        Assert.equal("production modulus is 2048-bit", 2048,
                     production.modulus().bitLength());
        Assert.equal("test modulus is 2048-bit", 2048, test.modulus().bitLength());
        Assert.equal("published exponent", "010001", ServerKeys.EXPONENT);

        // The environments must not be confusable: picking the wrong one is
        // exactly what a mismatched build looks like on the wire.
        Assert.isFalse("environments use different keys",
                       production.fingerprint() == test.fingerprint());

        RsaKey[] both = { production, test };
        long[] offered = { 847625836280919973L, PRODUCTION_FINGERPRINT };
        Assert.equal("select finds the key the server named",
                     PRODUCTION_FINGERPRINT, RsaKey.select(both, offered).fingerprint());
        Assert.isTrue("select rejects an unknown fingerprint list",
                      RsaKey.select(both, new long[] { 1L, 2L }) == null);
    }

    private static RsaKey key(String modulus)
    {
        return new RsaKey(modulus, ServerKeys.EXPONENT);
    }
}
