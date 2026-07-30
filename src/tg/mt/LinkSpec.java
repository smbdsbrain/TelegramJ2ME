package tg.mt;

/** Fully resolved link and network endpoint for one connection attempt. */
public final class LinkSpec
{
    public final int mode;
    public final MtLink link;
    public final String host;
    public final int port;
    /** A media/file connection, which announces itself with a negated dc id. */
    public final boolean media;

    public LinkSpec(int mode, MtLink link, String host, int port)
    {
        this(mode, link, host, port, false);
    }

    public LinkSpec(int mode, MtLink link, String host, int port, boolean media)
    {
        this.mode = mode;
        this.link = link;
        this.host = host;
        this.port = port;
        this.media = media;
    }
}
