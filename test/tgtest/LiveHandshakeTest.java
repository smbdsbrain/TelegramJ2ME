package tgtest;

import tg.crypto.AuthKeySeeding;
import tg.crypto.Rng;
import tg.diag.Diag;
import tg.mt.Abridged;
import tg.mt.Dc;
import tg.mt.Handshake;
import tg.mt.MsgIdGen;
import tg.mt.MtPlain;

/**
 * The real thing: a complete authorization-key exchange against a live Telegram
 * data centre, driven from the desktop through {@link SeTransport}.
 *
 * Everything exercised here - framing, TL, RSA_PAD, the pq factorisation, the
 * Diffie-Hellman, the nonce and hash verification - is the same CLDC-subset
 * code that ships to the handset. Only the socket differs.
 *
 * This is not a unit test and is not part of the default suite: it needs the
 * network, it talks to somebody else's servers, and it takes seconds. Run it
 * explicitly:
 *
 *     ./tools/live.ps1 handshake
 *
 * It targets whichever environment the build was configured for
 * ({@code build.ps1 -Env}), which defaults to the test data centres.
 */
public final class LiveHandshakeTest
{
    public static void main(String[] args) throws Exception
    {
        String host = Dc.bootstrapAddress();
        int port = Dc.PORT;
        int dcId = Dc.BOOTSTRAP_DC_ID;

        System.out.println("environment : " + (Dc.isTest() ? "TEST" : "PRODUCTION"));
        System.out.println("data centre : dc" + dcId + " " + host + ":" + port);
        System.out.println();

        SeTransport transport = new SeTransport();
        transport.setReadTimeoutMs(30000);

        try
        {
            long t0 = System.currentTimeMillis();
            transport.connect(host, port, 20000);
            System.out.println("connected in " + (System.currentTimeMillis() - t0) + " ms");

            Abridged frame = new Abridged(transport);
            MsgIdGen ids = new MsgIdGen();
            MtPlain plain = new MtPlain(frame, ids);

            Rng rng = new Rng();
            Handshake handshake = new Handshake(plain, rng, dcId, Dc.isTest());
            Handshake.Result result = handshake.run();

            System.out.println();
            System.out.println("=== SUCCESS ===");
            System.out.println("auth_key      : " + result.authKey.describe());
            System.out.println("auth_key_id   : " + result.authKey.keyId());
            System.out.println("server_salt   : " + result.serverSalt);
            System.out.println("server_time   : " + result.serverTimeSeconds
                               + " (local offset " + ids.timeOffsetSeconds() + "s)");
            System.out.println("known prime   : " + result.usedKnownGoodPrime);
            // Reported apart from the exchange: this is the seeding barrier, and
            // it is the number a device run is meant to record.
            System.out.println("entropy barrier: " + result.entropyMillis + " ms ("
                               + AuthKeySeeding.GATHERS + " gathers)");
            System.out.println("elapsed       : " + result.elapsedMillis + " ms");
            System.out.println("bytes rx/tx   : " + transport.bytesRead()
                               + " / " + transport.bytesWritten());
        }
        catch (Throwable t)
        {
            System.out.println();
            System.out.println("=== FAILED ===");
            System.out.println(t.getClass().getName() + ": " + t.getMessage());
            System.out.println();
            System.out.println("--- diagnostic log ---");
            dumpLog();
            t.printStackTrace(System.out);
            System.exit(1);
        }
        finally
        {
            transport.close();
        }

        System.out.println();
        System.out.println("--- diagnostic log ---");
        dumpLog();
    }

    static void dumpLog()
    {
        String[] lines = Diag.snapshot();
        for (int i = 0; i < lines.length; i++)
        {
            System.out.println("  " + lines[i]);
        }
    }
}
