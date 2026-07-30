package tgtest;

import tg.api.Dialog;
import tg.api.Peer;
import tg.api.ReactionCatalog;
import tg.api.Telegram;
import tg.crypto.Rng;

/** Read-only production check of global and per-peer reaction policies. */
public final class LiveReactionsTest
{
    public static void main(String[] args) throws Exception
    {
        FileAuthKeyStore store = new FileAuthKeyStore();
        SeTransport transport = new SeTransport();
        transport.setReadTimeoutMs(60000);
        Telegram tg = new Telegram(transport, new Rng(), store);
        try
        {
            tg.connect();
            if (tg.checkAuthorization() == null)
            {
                throw new IllegalStateException(
                        "not signed in; run ./tools/live.ps1 login");
            }
            Dialog[] dialogs = tg.getDialogs(40);
            Peer[] samples = new Peer[3];
            for (int i = 0; i < dialogs.length; i++)
            {
                Peer peer = dialogs[i].peer;
                if (peer != null && samples[peer.kind] == null)
                {
                    samples[peer.kind] = peer;
                }
            }
            for (int kind = 0; kind < samples.length; kind++)
            {
                if (samples[kind] == null) { continue; }
                String[] allowed = tg.getAllowedReactions(samples[kind]);
                System.out.println(kindName(kind) + ": " + join(allowed));
                if (kind == Peer.USER
                        && (!contains(allowed, "\ud83e\udd23")
                            || !contains(allowed, "\ud83d\ude31")))
                {
                    throw new AssertionError(
                            "global catalog lacks canonical Laugh or Wow");
                }
            }
            System.out.println("reaction policy read-only check passed");
        }
        finally
        {
            tg.close();
        }
    }

    private static boolean contains(String[] values, String wanted)
    {
        for (int i = 0; i < values.length; i++)
        {
            if (wanted.equals(values[i])) { return true; }
        }
        return false;
    }

    private static String join(String[] values)
    {
        StringBuffer out = new StringBuffer();
        for (int i = 0; i < values.length; i++)
        {
            if (i > 0) { out.append(' '); }
            out.append(values[i]);
        }
        return out.toString();
    }

    private static String kindName(int kind)
    {
        switch (kind)
        {
            case Peer.USER: return "user";
            case Peer.CHAT: return "basic group";
            case Peer.CHANNEL: return "channel/supergroup";
            default: return "peer";
        }
    }
}
