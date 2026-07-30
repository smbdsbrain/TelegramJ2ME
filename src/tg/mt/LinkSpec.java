package tg.mt;

/** Fully resolved link and network endpoint for one connection attempt. */
public final class LinkSpec
{
    public final int mode;
    public final MtLink link;
    public final String host;
    public final int port;

    public LinkSpec(int mode, MtLink link, String host, int port)
    {
        this.mode = mode;
        this.link = link;
        this.host = host;
        this.port = port;
    }
}
