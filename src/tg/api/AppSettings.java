package tg.api;

import tg.mt.AuthKeyStore;
import tg.ui.Theme;

/** Small non-network UI preferences persisted beside connection settings. */
public final class AppSettings
{
    public boolean mediaPreviews = true;
    public int logLevel;
    public boolean remoteLog;
    public String remoteLogHost = "127.0.0.1";
    public int remoteLogPort = 7778;
    public int themeId = Theme.LIGHT;

    public void load(AuthKeyStore store)
    {
        String value = store.loadString("ui.media.previews");
        mediaPreviews = value == null || !"0".equals(value);
        value = store.loadString("log.level");
        try { logLevel = value == null ? 0 : Integer.parseInt(value); }
        catch (Throwable ignored) { logLevel = 0; }
        if (logLevel < 0 || logLevel > 2) { logLevel = 0; }
        remoteLog = "1".equals(store.loadString("log.remote.enabled"));
        value = store.loadString("log.remote.host");
        remoteLogHost = value == null ? "127.0.0.1" : value;
        value = store.loadString("log.remote.port");
        try { remoteLogPort = value == null ? 7778 : Integer.parseInt(value); }
        catch (Throwable ignored) { remoteLogPort = 7778; }
        if (remoteLogPort < 1 || remoteLogPort > 65535) { remoteLogPort = 7778; }
        value = store.loadString("ui.theme");
        try { themeId = value == null ? Theme.LIGHT : Integer.parseInt(value); }
        catch (Throwable ignored) { themeId = Theme.LIGHT; }
        if (!Theme.isValid(themeId)) { themeId = Theme.LIGHT; }
    }

    public void save(AuthKeyStore store)
    {
        store.saveString("ui.media.previews", mediaPreviews ? "1" : "0");
        store.saveString("log.level", String.valueOf(logLevel));
        store.saveString("log.remote.enabled", remoteLog ? "1" : "0");
        store.saveString("log.remote.host",
                remoteLogHost == null ? "" : remoteLogHost);
        store.saveString("log.remote.port", String.valueOf(remoteLogPort));
        store.saveString("ui.theme", String.valueOf(themeId));
    }
}
