package tg.mt;

/**
 * Telegram server RSA public keys - GENERATED, do not edit by hand.
 *
 * Regenerate with:  python tools/fetch-server-keys.py
 *
 * Source: telegramdesktop/tdesktop
 *         Telegram/SourceFiles/mtproto/mtproto_dc_options.cpp
 *         commit e6b9a07163fee6721478ab982edb6211498a816c (2023-12-21T23:50:04Z)
 *         sha256 520b5b1c2d7813de49158b0880f3934e8f97ebecdf7f1d7a29ca24462800189e
 *
 * Telegram stopped publishing these in the documentation; they now live only
 * in official client source. A public key is public by construction - it ships
 * inside every Telegram binary - so unlike api_id/api_hash this file is
 * committed.
 *
 * The auth_key handshake encrypts p_q_inner_data to whichever of these the
 * server names in resPQ.server_public_key_fingerprints. Production and test
 * data centres use DIFFERENT keys; using the wrong one yields a blob the
 * server rejects without a useful error.
 *
 * Fingerprints are not stored: {@link RsaKey} recomputes them from the
 * modulus and exponent, so a transcription error cannot go unnoticed.
 */
public final class ServerKeys
{
    /** Public exponent, 65537 for every key Telegram publishes. */
    public static final String EXPONENT = "010001";

    /** production data centres. */
    public static final String[] PRODUCTION_MODULUS = {
        // fingerprint -3414540481677951611, exponent 0x010001
        "e8bb3305c0b52c6cf2afdf7637313489e63e05268e5badb601af417786472e5f" +
        "93b85438968e20e6729a301c0afc121bf7151f834436f7fda680847a66bf64ac" +
        "cec78ee21c0b316f0edafe2f41908da7bd1f4a5107638eeb67040ace472a14f9" +
        "0d9f7c2b7def99688ba3073adb5750bb02964902a359fe745d8170e36876d4fd" +
        "8a5d41b2a76cbff9a13267eb9580b2d06d10357448d20d9da2191cb5d8c93982" +
        "961cdfdeda629e37f1fb09a0722027696032fe61ed663db7a37f6f263d370f69" +
        "db53a0dc0a1748bdaaff6209d5645485e6e001d1953255757e4b8e42813347b1" +
        "1da6ab500fd0ace7e6dfa3736199ccaf9397ed0745a427dcfa6cd67bcb1acff3",
    };

    /** test data centres. */
    public static final String[] TEST_MODULUS = {
        // fingerprint -5595554452916591101, exponent 0x010001
        "c8c11d635691fac091dd9489aedced2932aa8a0bcefef05fa800892d9b52ed03" +
        "200865c9e97211cb2ee6c7ae96d3fb0e15aeffd66019b44a08a240cfdd2868a8" +
        "5e1f54d6fa5deaa041f6941ddf302690d61dc476385c2fa655142353cb4e4b59" +
        "f6e5b6584db76fe8b1370263246c010c93d011014113ebdf987d093f9d37c2be" +
        "48352d69a1683f8f6e6c2167983c761e3ab169fde5daaa12123fa1beab621e4d" +
        "a5935e9c198f82f35eae583a99386d8110ea6bd1abb0f568759f62694419ea5f" +
        "69847c43462abef858b4cb5edc84e7b9226cd7bd7e183aa974a712c079dde85b" +
        "9dc063b8a5c08e8f859c0ee5dcd824c7807f20153361a7f63cfd2a433a1be7f5",
    };

    private ServerKeys() { }

    /** Moduli for the environment this build targets. */
    public static String[] moduli()
    {
        return Dc.isTest() ? TEST_MODULUS : PRODUCTION_MODULUS;
    }
}
