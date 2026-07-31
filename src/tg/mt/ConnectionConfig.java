package tg.mt;

import tg.app.DevProxy;
import tg.diag.Diag;
import tg.mt.AuthKeyStore;

/** Runtime reachability settings persisted through AuthKeyStore string values. */
public final class ConnectionConfig
{
    public static final int AUTO = 0;
    public static final int DIRECT = 1;
    public static final int DIRECT_OBFUSCATED = 2;
    public static final int MTPROXY = 3;
    public static final int HTTP = 4;

    public int mode = AUTO;
    public int lastSuccessful;
    public String proxyHost = "";
    public int proxyPort = 443;
    public String proxySecret = "";

    /**
     * This handset can hold only one socket at a time.
     *
     * Off by default: on normal hardware a media transfer opens its own
     * connection and the session keeps running. Measured on a Samsung GT-C3592,
     * where the platform refuses a second concurrent socket outright and the
     * attempt also desynchronises the connection already in use.
     *
     * When on, a file that lives on another data centre parks the session for
     * the duration of the transfer instead of opening a second connection.
     * Files on the session's own data centre never need either - they reuse the
     * connection that is already there.
     */
    public boolean singleSocket;

    public void load(AuthKeyStore store)
    {
        mode = integer(store.loadString("net.mode"), AUTO);
        lastSuccessful = integer(store.loadString("net.last"), 0);
        String host = store.loadString("net.proxy.host");
        String port = store.loadString("net.proxy.port");
        String secret = store.loadString("net.proxy.secret");
        if (host != null) { proxyHost = host; }
        proxyPort = integer(port, 443);
        if (secret != null) { proxySecret = secret; }
        singleSocket = "1".equals(store.loadString("net.single.socket"));

        if (host == null && secret == null) { applyBuiltInProxy(); }

        validate();
    }

    /**
     * Seed the proxy from the build when the handset has none stored.
     *
     * Only when nothing is stored: a value entered in Settings is persisted and
     * must keep winning, otherwise reinstalling a build would silently undo the
     * user's choice. Absent from every public artifact, which is built without
     * secrets/proxy.yaml and gets an empty link.
     *
     * Typing a base64 proxy secret on a numeric keypad is not a workflow, which
     * is the whole reason this exists.
     */
    private void applyBuiltInProxy()
    {
        if (!DevProxy.CONFIGURED) { return; }
        try
        {
            ProxySecret.ParsedLink parsed = ProxySecret.parseLink(DevProxy.LINK);
            proxyHost = parsed.host;
            proxyPort = parsed.port;
            proxySecret = parsed.secret.encode();
            if (mode == AUTO) { mode = MTPROXY; }
        }
        catch (Throwable t)
        {
            // A malformed link in the build must not stop the client starting;
            // the user can still enter one on the device.
            Diag.warn("built-in proxy link unusable: " + Diag.className(t));
        }
    }

    public void save(AuthKeyStore store)
    {
        validate();
        store.saveString("net.mode", String.valueOf(mode));
        store.saveString("net.last", String.valueOf(lastSuccessful));
        store.saveString("net.proxy.host", proxyHost.length() == 0 ? null : proxyHost);
        store.saveString("net.proxy.port", String.valueOf(proxyPort));
        store.saveString("net.proxy.secret", proxySecret.length() == 0 ? null : proxySecret);
        store.saveString("net.single.socket", singleSocket ? "1" : "0");
    }

    public boolean hasProxy()
    {
        if (proxyHost.length() == 0 || proxyPort < 1 || proxySecret.length() == 0) { return false; }
        try { ProxySecret.parse(proxySecret); return true; }
        catch (Throwable t) { return false; }
    }

    public int[] attempts()
    {
        if (mode != AUTO) { return new int[] { mode }; }
        int[] candidates = {
            lastSuccessful, DIRECT, DIRECT_OBFUSCATED,
            hasProxy() ? MTPROXY : 0, HTTP
        };
        int[] unique = new int[5];
        int count = 0;
        for (int i = 0; i < candidates.length; i++)
        {
            int v = candidates[i];
            if (v < DIRECT || v > HTTP) { continue; }
            boolean seen = false;
            for (int j = 0; j < count; j++) { if (unique[j] == v) { seen = true; break; } }
            if (!seen) { unique[count++] = v; }
        }
        int[] out = new int[count];
        System.arraycopy(unique, 0, out, 0, count);
        return out;
    }

    public static String name(int mode)
    {
        switch (mode)
        {
            case AUTO: return "auto";
            case DIRECT: return "direct";
            case DIRECT_OBFUSCATED: return "direct-obfuscated";
            case MTPROXY: return "mtproxy";
            case HTTP: return "http";
            default: return "unknown";
        }
    }

    private void validate()
    {
        if (mode < AUTO || mode > HTTP) { mode = AUTO; }
        if (lastSuccessful < DIRECT || lastSuccessful > HTTP) { lastSuccessful = 0; }
        if (proxyHost == null) { proxyHost = ""; }
        if (proxySecret == null) { proxySecret = ""; }
        if (proxyPort < 1 || proxyPort > 65535) { proxyPort = 443; }
    }

    private static int integer(String s, int fallback)
    {
        try { return s == null ? fallback : Integer.parseInt(s); }
        catch (Throwable t) { return fallback; }
    }
}
