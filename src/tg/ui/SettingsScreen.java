package tg.ui;

import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.TextField;

import tg.mt.ConnectionConfig;
import tg.mt.ProxySecret;
import tg.api.AppSettings;

/** Runtime transport and MTProxy settings persisted in RMS. */
public final class SettingsScreen extends Form
{
    // Command.SCREEN so the handset sorts it by priority alongside the other
    // menu entries; see the note on the command block in TgMidlet.
    public static final Command CMD_SAVE = new Command("Save", Command.SCREEN, 1);

    private final ChoiceGroup mode;
    private final TextField proxyLink;
    private final TextField host;
    private final TextField port;
    private final TextField secret;
    private final ChoiceGroup mediaPreviews;
    private final ChoiceGroup theme;
    private final ChoiceGroup logLevel;
    private final ChoiceGroup remoteLog;
    private final TextField remoteHost;
    private final TextField remotePort;

    public SettingsScreen(ConnectionConfig config, AppSettings app)
    {
        super("Settings");
        mode = new ChoiceGroup("Mode", Choice.EXCLUSIVE,
                new String[] { "Auto", "Direct", "Direct obfuscated",
                               "MTProxy", "HTTP" }, null);
        mode.setSelectedIndex(config.mode, true);
        proxyLink = new TextField("tg://proxy link (optional)", "", 512, TextField.ANY);
        host = new TextField("Proxy host", config.proxyHost, 128, TextField.ANY);
        port = new TextField("Proxy port", String.valueOf(config.proxyPort),
                             6, TextField.NUMERIC);
        secret = new TextField("Proxy secret", config.proxySecret, 512, TextField.ANY);
        mediaPreviews = new ChoiceGroup("Media previews", Choice.EXCLUSIVE,
                new String[] { "Inline stripped thumbnail", "Text only" }, null);
        mediaPreviews.setSelectedIndex(app.mediaPreviews ? 0 : 1, true);
        theme = new ChoiceGroup("Theme", Choice.EXCLUSIVE, Theme.names(), null);
        theme.setSelectedIndex(Theme.isValid(app.themeId)
                ? app.themeId : Theme.LIGHT, true);
        logLevel = new ChoiceGroup("Log level", Choice.EXCLUSIVE,
                new String[] { "Info", "Warnings", "Errors" }, null);
        logLevel.setSelectedIndex(app.logLevel, true);
        remoteLog = new ChoiceGroup("Remote log", Choice.MULTIPLE,
                new String[] { "Stream diagnostics over TCP" }, null);
        remoteLog.setSelectedIndex(0, app.remoteLog);
        remoteHost = new TextField("Remote log host", app.remoteLogHost,
                128, TextField.ANY);
        remotePort = new TextField("Remote log port",
                String.valueOf(app.remoteLogPort), 6, TextField.NUMERIC);
        append(mode);
        append(proxyLink);
        append(host);
        append(port);
        append(secret);
        append("Auto order: last successful, direct, obfuscated, MTProxy, HTTP.");
        append(mediaPreviews);
        append(theme);
        append(logLevel);
        append(remoteLog);
        append(remoteHost);
        append(remotePort);
        append("Remote logging is development-only and may use metered data.");
    }

    public void apply(ConnectionConfig config, AppSettings app)
    {
        config.mode = mode.getSelectedIndex();
        String link = proxyLink.getString().trim();
        if (link.length() > 0)
        {
            ProxySecret.ParsedLink parsed = ProxySecret.parseLink(link);
            config.proxyHost = parsed.host;
            config.proxyPort = parsed.port;
            config.proxySecret = parsed.secret.encode();
            host.setString(config.proxyHost);
            port.setString(String.valueOf(config.proxyPort));
            secret.setString(config.proxySecret);
        }
        else
        {
            config.proxyHost = host.getString().trim();
            try { config.proxyPort = Integer.parseInt(port.getString().trim()); }
            catch (Throwable t) { throw new IllegalArgumentException("invalid proxy port"); }
            config.proxySecret = secret.getString().trim();
            if (config.proxySecret.length() > 0)
            {
                config.proxySecret = ProxySecret.parse(config.proxySecret).encode();
            }
        }
        if (config.mode == ConnectionConfig.MTPROXY && !config.hasProxy())
        {
            throw new IllegalArgumentException("MTProxy mode needs host, port and secret");
        }
        app.mediaPreviews = mediaPreviews.getSelectedIndex() == 0;
        app.themeId = theme.getSelectedIndex();
        app.logLevel = logLevel.getSelectedIndex();
        app.remoteLog = remoteLog.isSelected(0);
        app.remoteLogHost = remoteHost.getString().trim();
        try
        {
            app.remoteLogPort = Integer.parseInt(remotePort.getString().trim());
        }
        catch (Throwable t)
        {
            throw new IllegalArgumentException("invalid remote log port");
        }
        if (app.remoteLogPort < 1 || app.remoteLogPort > 65535)
        {
            throw new IllegalArgumentException("invalid remote log port");
        }
        if (app.remoteLog && app.remoteLogHost.length() == 0)
        {
            throw new IllegalArgumentException("remote log needs a host");
        }
    }
}
