package tg.mt;

import tg.app.BuildInfo;

/**
 * Telegram data centre addresses.
 *
 * Public information, unlike api_id/api_hash - these are committed. See
 * <a href="https://core.telegram.org/api/datacenter">core.telegram.org/api/datacenter</a>.
 *
 * <h3>Bootstrap only</h3>
 * These are the addresses used to open the <em>first</em> connection. The
 * authoritative list comes from {@code help.getConfig} and must replace this one
 * as soon as a session exists; hardcoding beyond bootstrap is how a client
 * breaks when Telegram renumbers.
 *
 * <h3>Test versus production</h3>
 * An auth_key is bound to one data centre <em>and</em> one environment. A key
 * generated against a test DC is worthless in production and vice versa, so the
 * environment is baked in at build time ({@code tools/build.ps1 -Env}) and
 * exposed here rather than being a runtime toggle someone can flip with a stale
 * key in RMS.
 *
 * Test DCs come first because authorization work there has no flood limits and
 * cannot disturb a real account. Test accounts use phone numbers of the form
 * 99966XXXX; see <a href="https://core.telegram.org/api/auth">core.telegram.org/api/auth</a>.
 *
 * Port 443 carries raw MTProto here, not TLS.
 */
public final class Dc
{
    public static final int PORT = 443;

    /** Where an unauthorized client starts, per Telegram's documentation. */
    public static final int BOOTSTRAP_DC_ID = 2;

    // Transcribed from Telegram Desktop's kBuiltInDcsTest / kBuiltInDcs
    // (Telegram/SourceFiles/mtproto/mtproto_dc_options.cpp) by
    // tools/fetch-server-keys.py, which prints them on every run.
    private static final String[] TEST_IPV4 = {
        null,                   // index 0 unused: DC ids are 1-based
        "149.154.175.10",
        "149.154.167.40",
        "149.154.175.117"
    };

    private static final String[] PROD_IPV4 = {
        null,
        "149.154.175.50",
        "149.154.167.51",
        "149.154.175.100",
        "149.154.167.91",
        "149.154.171.5"
    };

    private Dc() { }

    /** True when this build targets the test data centres. */
    public static boolean isTest()
    {
        return "test".equals(BuildInfo.ENV);
    }

    /**
     * Bootstrap address for a data centre.
     *
     * @param dcId 1-based, as Telegram numbers them
     * @return dotted-quad address, or null if this build has no bootstrap entry
     *         for that id - in which case the address must come from
     *         {@code help.getConfig}
     */
    public static String address(int dcId)
    {
        String[] table = isTest() ? TEST_IPV4 : PROD_IPV4;
        if (dcId < 1 || dcId >= table.length)
        {
            return null;
        }
        return table[dcId];
    }

    public static String bootstrapAddress()
    {
        return address(BOOTSTRAP_DC_ID);
    }

    /** Official MTProto-over-HTTP hostname for a data centre. */
    public static String httpHost(int dcId)
    {
        String[] names = { null, "pluto", "venus", "aurora", "vesta", "flora" };
        if (dcId < 1 || dcId >= names.length) { return null; }
        return names[dcId] + ".web.telegram.org";
    }

    public static String httpUrl(int dcId)
    {
        String host = address(dcId);
        if (host == null) { throw new IllegalArgumentException("no HTTP address for dc" + dcId); }
        // Telegram Desktop also uses the concrete DC address here. The domain
        // form documented by Telegram currently redirects these binary POSTs
        // to core.telegram.org (302), while the DC IP serves the carrier.
        return "http://" + host + ":80/api";
    }

    /** Documented domain URI, retained for diagnostics and future probing. */
    public static String httpDomainUrl(int dcId)
    {
        String host = httpHost(dcId);
        if (host == null) { throw new IllegalArgumentException("no HTTP name for dc" + dcId); }
        return "http://" + host + ":80/api" + (isTest() ? "_test" : "");
    }

    /**
     * The value the {@code dc} field of p_q_inner_data_dc must carry.
     *
     * Telegram adds 10000 for test data centres and negates the id for media
     * (non-CDN) DCs. Getting it wrong is answered with a bare -444 and no
     * explanation, so the encoding lives here rather than at the call site.
     */
    public static int rawId(int dcId, boolean media)
    {
        int raw = dcId + (isTest() ? 10000 : 0);
        return media ? -raw : raw;
    }

    public static int rawId(int dcId)
    {
        return rawId(dcId, false);
    }

    /** For the diagnostics screen. */
    public static String describe()
    {
        return BuildInfo.ENV + " dc" + BOOTSTRAP_DC_ID + " "
               + bootstrapAddress() + ":" + PORT;
    }
}
