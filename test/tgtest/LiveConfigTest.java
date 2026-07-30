package tgtest;

import tg.crypto.Rng;
import tg.diag.Diag;
import tg.mt.AuthKey;
import tg.mt.Dc;
import tg.mt.Layer;
import tg.mt.MtClient;
import tg.tl.TlReader;
import tg.tl.TlWriter;

/**
 * The decisive proof of the architecture: an encrypted MTProto 2.0 session
 * carrying a real Telegram API call.
 *
 * help.getConfig is chosen because it needs everything to be right - the
 * auth_key, the message envelope, msg_key verification, initConnection with our
 * api_id, the layer, and gzip unpacking of the reply - but requires no user
 * authorization. If this returns a parsable Config, the transport and crypto
 * stack is done.
 *
 *     ./tools/live.ps1 config
 */
public final class LiveConfigTest
{
    private static final int HELP_GET_CONFIG = 0xc4f9186b;
    private static final int CONFIG = 0xcc1a241e;

    public static void main(String[] args) throws Exception
    {
        FileAuthKeyStore store = new FileAuthKeyStore();
        int dcId = Dc.BOOTSTRAP_DC_ID;
        boolean test = Dc.isTest();

        System.out.println("environment : " + (test ? "TEST" : "PRODUCTION"));
        System.out.println("data centre : dc" + dcId + " " + Dc.address(dcId));
        System.out.println("layer       : " + Layer.LAYER);
        System.out.println("api_id      : " + tg.app.Secrets.API_ID);
        System.out.println("key store   : " + store.file().getAbsolutePath());
        System.out.println();

        SeTransport transport = new SeTransport();
        transport.setReadTimeoutMs(60000);
        MtClient client = new MtClient(transport, new Rng());

        try
        {
            client.connect(dcId);

            AuthKey stored = store.load(dcId, test);
            if (stored != null)
            {
                System.out.println("reusing stored " + stored.describe());
                client.resume(stored, 0);
            }
            else
            {
                System.out.println("no stored key, running the handshake...");
                AuthKey fresh = client.authenticate();
                store.save(fresh);
                System.out.println("generated and stored " + fresh.describe());
            }
            System.out.println();

            TlWriter q = new TlWriter(8);
            q.writeInt(HELP_GET_CONFIG);

            long t0 = System.currentTimeMillis();
            byte[] result = client.invokeWithSaltRetry(q.toByteArray());
            long elapsed = System.currentTimeMillis() - t0;

            System.out.println("help.getConfig returned " + result.length
                               + " bytes in " + elapsed + " ms");
            System.out.println();
            describeConfig(result);

            // Same bytes through the generated schema table. Config is a good
            // stress case: sixty-odd fields, a dozen of them optional, and a
            // vector of a nested type - if the descriptor encoding is wrong
            // anywhere, this desynchronises and throws.
            System.out.println();
            System.out.println("re-parsing via the generated TL table...");
            tg.tl.TlObj cfg = tg.tl.TlParser.parse(new TlReader(result));
            System.out.println("  constructor  = 0x" + Integer.toHexString(cfg.id));
            System.out.println("  fields       = " + cfg.nums.length);
            System.out.println("  this_dc      = " + cfg.intAt(4 + 1));
            System.out.println("  dc_options   = " + cfg.vec(6).length + " entries");
            System.out.println("  table covers " + tg.api.TlSchema.constructorCount()
                               + " constructors");

            System.out.println();
            System.out.println("=== SUCCESS: encrypted MTProto 2.0 session works ===");
            System.out.println("bytes rx/tx : " + transport.bytesRead()
                               + " / " + transport.bytesWritten());
        }
        catch (Throwable t)
        {
            System.out.println();
            System.out.println("=== FAILED ===");
            System.out.println(t.getClass().getName() + ": " + t.getMessage());
            System.out.println();
            LiveHandshakeTest.dumpLog();
            t.printStackTrace(System.out);
            System.exit(1);
        }
        finally
        {
            client.close();
        }

        System.out.println();
        System.out.println("--- diagnostic log ---");
        LiveHandshakeTest.dumpLog();
    }

    /**
     * Reads only the leading fields. Config has around sixty of them and the
     * point here is to prove the bytes are genuine, not to consume the type -
     * that is the code generator's job.
     */
    private static void describeConfig(byte[] result) throws Exception
    {
        TlReader r = new TlReader(result);
        int id = r.readInt();
        if (id != CONFIG)
        {
            System.out.println("unexpected constructor 0x" + Integer.toHexString(id)
                               + " (expected config 0x" + Integer.toHexString(CONFIG) + ")");
            return;
        }

        int flags = r.readInt();
        int date = r.readInt();
        int expires = r.readInt();
        boolean testMode = r.readBool();
        int thisDc = r.readInt();
        int dcOptions = r.readVectorCount();

        System.out.println("config:");
        System.out.println("  flags        = 0x" + Integer.toHexString(flags));
        System.out.println("  date         = " + date);
        System.out.println("  expires      = " + expires);
        System.out.println("  test_mode    = " + testMode);
        System.out.println("  this_dc      = " + thisDc);
        System.out.println("  dc_options   = " + dcOptions + " entries");

        // The first few DC options, which is the list that should eventually
        // replace the compiled-in bootstrap table.
        for (int i = 0; i < dcOptions && i < 6; i++)
        {
            r.readInt();                        // dcOption constructor
            int optFlags = r.readInt();
            int optId = r.readInt();
            String ip = r.readString();
            int port = r.readInt();
            if ((optFlags & 0x400) != 0)        // secret
            {
                r.readBytes();
            }
            String tags = ((optFlags & 1) != 0 ? " ipv6" : "")
                        + ((optFlags & 2) != 0 ? " media" : "")
                        + ((optFlags & 4) != 0 ? " tcpo" : "")
                        + ((optFlags & 8) != 0 ? " cdn" : "")
                        + ((optFlags & 16) != 0 ? " static" : "");
            System.out.println("    dc" + optId + " " + ip + ":" + port + tags);
        }

        if (testMode != Dc.isTest())
        {
            System.out.println();
            System.out.println("  NOTE: server says test_mode=" + testMode
                               + " but this build targets "
                               + (Dc.isTest() ? "test" : "production"));
        }

        Diag.info("config parsed, this_dc=" + thisDc);
    }
}
