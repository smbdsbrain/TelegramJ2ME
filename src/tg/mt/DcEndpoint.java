package tg.mt;

/** One IPv4 Telegram endpoint learned from help.getConfig. */
public final class DcEndpoint
{
    public final int dcId;
    public final String host;
    public final int port;
    public final boolean mediaOnly;

    public DcEndpoint(int dcId, String host, int port, boolean mediaOnly)
    {
        this.dcId = dcId;
        this.host = host;
        this.port = port;
        this.mediaOnly = mediaOnly;
    }

    public static DcEndpoint builtin(int dcId)
    {
        String host = Dc.address(dcId);
        return host == null ? null : new DcEndpoint(dcId, host, Dc.PORT, false);
    }
}
