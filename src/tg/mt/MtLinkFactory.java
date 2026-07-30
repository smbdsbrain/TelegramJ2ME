package tg.mt;

import java.io.IOException;

import tg.crypto.Rng;

/** Creates a fresh, disconnected packet link for one route attempt. */
public interface MtLinkFactory
{
    LinkSpec create(int mode, int dcId, DcEndpoint endpoint,
                    ConnectionConfig config, Rng rng)
            throws IOException;
}
