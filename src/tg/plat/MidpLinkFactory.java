package tg.plat;

import java.io.IOException;

import tg.crypto.Rng;
import tg.io.FakeTlsTransport;
import tg.io.ObfuscatedTransport;
import tg.io.Transport;
import tg.mt.AbridgedLink;
import tg.mt.ConnectionConfig;
import tg.mt.Dc;
import tg.mt.DcEndpoint;
import tg.mt.HttpLink;
import tg.mt.IntermediateLink;
import tg.mt.LinkSpec;
import tg.mt.MtLinkFactory;
import tg.mt.ProxySecret;

/** Creates all device-side Phase-1 route variants. */
public final class MidpLinkFactory implements MtLinkFactory
{
    public LinkSpec create(int mode, int dcId, DcEndpoint endpoint,
                           ConnectionConfig config, Rng rng)
            throws IOException
    {
        String dcHost = endpoint == null ? Dc.address(dcId) : endpoint.host;
        int dcPort = endpoint == null ? Dc.PORT : endpoint.port;
        if (dcHost == null) { throw new IOException("no bootstrap address for dc" + dcId); }

        if (mode == ConnectionConfig.DIRECT)
        {
            return new LinkSpec(mode, new AbridgedLink(new MidpTransport()), dcHost, dcPort);
        }
        if (mode == ConnectionConfig.DIRECT_OBFUSCATED)
        {
            Transport obfs = new ObfuscatedTransport(new MidpTransport(), rng,
                    ObfuscatedTransport.PROTOCOL_ABRIDGED, 0, null);
            return new LinkSpec(mode,
                    new AbridgedLink(obfs, true, "direct/obfuscated2/abridged"),
                    dcHost, dcPort);
        }
        if (mode == ConnectionConfig.MTPROXY)
        {
            if (!config.hasProxy()) { throw new IOException("MTProxy is not configured"); }
            ProxySecret secret = ProxySecret.parse(config.proxySecret);
            Transport stream = new MidpTransport();
            if (secret.fakeTls())
            {
                stream = new FakeTlsTransport(stream, rng, secret.key(), secret.domain());
            }
            int protocol = secret.padded()
                    ? ObfuscatedTransport.PROTOCOL_PADDED_INTERMEDIATE
                    : ObfuscatedTransport.PROTOCOL_INTERMEDIATE;
            stream = new ObfuscatedTransport(stream, rng, protocol, Dc.rawId(dcId), secret);
            return new LinkSpec(mode,
                    new IntermediateLink(stream, rng, secret.padded(), true,
                            secret.fakeTls() ? "mtproxy/faketls" :
                            (secret.padded() ? "mtproxy/padded" : "mtproxy/intermediate")),
                    config.proxyHost, config.proxyPort);
        }
        if (mode == ConnectionConfig.HTTP)
        {
            return new LinkSpec(mode,
                    // Old WAP/WSP gateways frequently reject an origin written
                    // as a literal IPv4 address.  Telegram explicitly supports
                    // the named HTTP endpoint, including the _test suffix.
                    new HttpLink(new MidpHttpExecutor(), Dc.httpDomainUrl(dcId)),
                    Dc.httpHost(dcId), 80);
        }
        throw new IOException("unsupported route mode " + mode);
    }
}
