package tg.mt;

import java.io.IOException;

import tg.crypto.Rng;
import tg.io.Transport;

/** Compatibility factory for desktop tests and callers that supply one socket. */
public final class FixedLinkFactory implements MtLinkFactory
{
    private final Transport transport;

    public FixedLinkFactory(Transport transport) { this.transport = transport; }

    public LinkSpec create(int mode, int dcId, DcEndpoint endpoint,
                           ConnectionConfig config, Rng rng)
            throws IOException
    {
        if (mode != ConnectionConfig.DIRECT)
        {
            throw new IOException("fixed transport supports direct mode only");
        }
        String host = endpoint == null ? Dc.address(dcId) : endpoint.host;
        if (host == null) { throw new IOException("no address for dc" + dcId); }
        int port = endpoint == null ? Dc.PORT : endpoint.port;
        return new LinkSpec(mode, new AbridgedLink(transport), host, port);
    }
}
