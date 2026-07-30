package tg.mt;

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
        validate();
    }

    public void save(AuthKeyStore store)
    {
        validate();
        store.saveString("net.mode", String.valueOf(mode));
        store.saveString("net.last", String.valueOf(lastSuccessful));
        store.saveString("net.proxy.host", proxyHost.length() == 0 ? null : proxyHost);
        store.saveString("net.proxy.port", String.valueOf(proxyPort));
        store.saveString("net.proxy.secret", proxySecret.length() == 0 ? null : proxySecret);
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
