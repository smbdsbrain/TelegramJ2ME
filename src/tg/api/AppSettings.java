package tg.api;

import tg.mt.AuthKeyStore;
import tg.ui.Theme;

/** Small non-network UI preferences persisted beside connection settings. */
public final class AppSettings
{
    /** Loopback and tools/log-server.py's port: a desktop developer's defaults. */
    public static final String DEFAULT_REMOTE_LOG_HOST = "127.0.0.1";
    public static final int DEFAULT_REMOTE_LOG_PORT = 7778;

    public boolean mediaPreviews = true;

    /**
     * Download avatars for the dialog list.
     *
     * Separate from {@link #mediaPreviews}, which only gates the stripped
     * thumbnails inside a conversation. An avatar is far more expensive: it
     * opens a second MTProto connection to the media data centre, and on a
     * handset that cannot hold two sockets at once the attempt breaks the
     * connection already in use. Turning this off costs a picture; leaving it
     * on can cost the conversation.
     */
    public boolean loadAvatars = true;
    public int logLevel;
    public boolean remoteLog;
    public String remoteLogHost = DEFAULT_REMOTE_LOG_HOST;
    public int remoteLogPort = DEFAULT_REMOTE_LOG_PORT;
    public int themeId = Theme.LIGHT;

    /**
     * True while the remote-log destination is still the built-in default.
     *
     * Lets the caller prefer a destination compiled into the build over
     * loopback, without overriding a host the tester actually typed in.
     */
    public boolean remoteLogIsDefault()
    {
        return remoteLogPort == DEFAULT_REMOTE_LOG_PORT
                && (remoteLogHost == null
                    || DEFAULT_REMOTE_LOG_HOST.equals(remoteLogHost));
    }

    public void load(AuthKeyStore store)
    {
        String value = store.loadString("ui.media.previews");
        mediaPreviews = value == null || !"0".equals(value);
        value = store.loadString("ui.avatars");
        loadAvatars = value == null || !"0".equals(value);
        value = store.loadString("log.level");
        try { logLevel = value == null ? 0 : Integer.parseInt(value); }
        catch (Throwable ignored) { logLevel = 0; }
        if (logLevel < 0 || logLevel > 2) { logLevel = 0; }
        remoteLog = "1".equals(store.loadString("log.remote.enabled"));
        value = store.loadString("log.remote.host");
        remoteLogHost = value == null ? DEFAULT_REMOTE_LOG_HOST : value;
        value = store.loadString("log.remote.port");
        try
        {
            remoteLogPort = value == null ? DEFAULT_REMOTE_LOG_PORT
                                          : Integer.parseInt(value);
        }
        catch (Throwable ignored) { remoteLogPort = DEFAULT_REMOTE_LOG_PORT; }
        if (remoteLogPort < 1 || remoteLogPort > 65535)
        {
            remoteLogPort = DEFAULT_REMOTE_LOG_PORT;
        }
        value = store.loadString("ui.theme");
        try { themeId = value == null ? Theme.LIGHT : Integer.parseInt(value); }
        catch (Throwable ignored) { themeId = Theme.LIGHT; }
        if (!Theme.isValid(themeId)) { themeId = Theme.LIGHT; }
    }

    public void save(AuthKeyStore store)
    {
        store.saveString("ui.media.previews", mediaPreviews ? "1" : "0");
        store.saveString("ui.avatars", loadAvatars ? "1" : "0");
        store.saveString("log.level", String.valueOf(logLevel));
        store.saveString("log.remote.enabled", remoteLog ? "1" : "0");
        store.saveString("log.remote.host",
                remoteLogHost == null ? "" : remoteLogHost);
        store.saveString("log.remote.port", String.valueOf(remoteLogPort));
        store.saveString("ui.theme", String.valueOf(themeId));
    }
}
