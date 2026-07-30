package tgtest;

import tg.crypto.Rng;
import tg.io.FakeTlsTransport;
import tg.io.ObfuscatedTransport;
import tg.io.Transport;
import tg.mt.AbridgedLink;
import tg.mt.AuthKey;
import tg.mt.Dc;
import tg.mt.HttpLink;
import tg.mt.IntermediateLink;
import tg.mt.MtClient;
import tg.mt.MtLink;
import tg.mt.ProxySecret;
import tg.tl.TlObj;
import tg.tl.TlParser;
import tg.tl.TlWriter;

/**
 * Live help.getConfig through a selected Phase-1 carrier.
 *
 * Proxy credentials are accepted only via TG_PROXY_URI so the secret never
 * appears in a checked-in file or command line.
 */
public final class LiveRouteTest
{
    private static final int HELP_GET_CONFIG = 0xc4f9186b;

    public static void main(String[] args) throws Exception
    {
        if (args.length != 1)
        {
            throw new IllegalArgumentException("route must be obfuscated, http or proxy");
        }
        String route = args[0];
        int dc = Dc.BOOTSTRAP_DC_ID;
        Rng rng = new Rng();
        MtLink link;
        String host;
        int port;

        if ("obfuscated".equals(route))
        {
            Transport obfs = new ObfuscatedTransport(socket(), rng,
                    ObfuscatedTransport.PROTOCOL_ABRIDGED, 0, null);
            link = new AbridgedLink(obfs, true, "direct/obfuscated2/abridged");
            host = Dc.address(dc);
            port = Dc.PORT;
        }
        else if ("http".equals(route))
        {
            String override = System.getenv("TG_HTTP_URL");
            String url = override == null || override.length() == 0
                    ? Dc.httpUrl(dc) : override;
            link = new HttpLink(new SeHttpExecutor(), url);
            host = Dc.address(dc);
            port = 80;
        }
        else if ("proxy".equals(route))
        {
            String uri = System.getenv("TG_PROXY_URI");
            if (uri == null || uri.length() == 0)
            {
                throw new IllegalArgumentException("set TG_PROXY_URI in the current shell");
            }
            ProxySecret.ParsedLink parsed = ProxySecret.parseLink(uri);
            ProxySecret secret = parsed.secret;
            Transport stream = socket();
            if (secret.fakeTls())
            {
                stream = new FakeTlsTransport(stream, rng, secret.key(), secret.domain());
            }
            int protocol = secret.padded()
                    ? ObfuscatedTransport.PROTOCOL_PADDED_INTERMEDIATE
                    : ObfuscatedTransport.PROTOCOL_INTERMEDIATE;
            stream = new ObfuscatedTransport(stream, rng, protocol, Dc.rawId(dc), secret);
            link = new IntermediateLink(stream, rng, secret.padded(), true,
                    secret.fakeTls() ? "mtproxy/faketls" :
                    (secret.padded() ? "mtproxy/padded" : "mtproxy/intermediate"));
            host = parsed.host;
            port = parsed.port;
        }
        else
        {
            throw new IllegalArgumentException("unknown route " + route);
        }

        System.out.println("route       : " + link.description());
        System.out.println("environment : " + (Dc.isTest() ? "TEST" : "PRODUCTION"));
        System.out.println("endpoint    : " + host + ":" + port);
        FileAuthKeyStore store = new FileAuthKeyStore();
        MtClient client = new MtClient(link, rng);
        try
        {
            client.connect(dc, host, port, 30000);
            // A stored key skips straight to encrypted frames. Forcing the
            // handshake is the only way to exercise the unencrypted ones, and
            // is worth an env switch rather than moving the secrets file.
            boolean forceHandshake = System.getenv("TG_FORCE_HANDSHAKE") != null;
            AuthKey key = forceHandshake ? null : store.load(dc, Dc.isTest());
            if (key == null)
            {
                System.out.println("no stored key; running auth_key handshake");
                key = client.authenticate();
                // A forced handshake is a throwaway probe; persisting it would
                // discard the session the store already holds.
                if (!forceHandshake) { store.save(key); }
            }
            else
            {
                System.out.println("reusing stored auth_key");
                client.resume(key, 0);
            }

            TlWriter query = new TlWriter(4);
            query.writeInt(HELP_GET_CONFIG);
            long started = System.currentTimeMillis();
            byte[] response = client.invokeWithSaltRetry(query.toByteArray());
            TlObj config = TlParser.parse(new tg.tl.TlReader(response));
            if (config == null)
            {
                throw new IllegalStateException("help.getConfig did not parse");
            }
            System.out.println("help.getConfig: " + response.length + " bytes, "
                               + (System.currentTimeMillis() - started) + " ms");
            System.out.println("constructor : 0x" + Integer.toHexString(config.id));
            System.out.println("bytes rx/tx : " + link.bytesRead() + "/" + link.bytesWritten());
            System.out.println("=== SUCCESS ===");
        }
        finally
        {
            client.close();
            String[] lines = tg.diag.Diag.snapshot();
            System.out.println("--- diagnostic log ---");
            for (int i = 0; i < lines.length; i++) { System.out.println(lines[i]); }
        }
    }

    private static SeTransport socket()
    {
        SeTransport transport = new SeTransport();
        transport.setReadTimeoutMs(60000);
        return transport;
    }
}
