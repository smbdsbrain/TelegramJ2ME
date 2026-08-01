package tgtest;

import tg.api.Dialog;
import tg.api.Message;
import tg.api.Peer;
import tg.api.Telegram;
import tg.crypto.Rng;
import tg.mt.Dc;

/**
 * List dialogs, and open the most recent one, using a stored session.
 *
 * Run {@code ./tools/live.ps1 login} first. This is the desktop equivalent of
 * the dialog list screen, so a parsing problem shows up here with a full stack
 * trace instead of as an error line on a handset.
 *
 *     ./tools/live.ps1 dialogs
 *     ./tools/live.ps1 dialogs 60          number of dialogs to request
 */
public final class LiveDialogsTest
{
    public static void main(String[] args) throws Exception
    {
        int limit = args.length > 0 ? Integer.parseInt(args[0]) : 30;

        FileAuthKeyStore store = new FileAuthKeyStore();
        SeTransport transport = new SeTransport();
        transport.setReadTimeoutMs(60000);
        Telegram tg = new Telegram(transport, new Rng(), store);

        try
        {
            tg.connect();
            System.out.println("connected to dc" + tg.dcId()
                               + " (" + (Dc.isTest() ? "test" : "production") + ")");

            Peer me = tg.checkAuthorization();
            if (me == null)
            {
                System.out.println();
                System.out.println("Not signed in. Run:  ./tools/live.ps1 login");
                System.exit(2);
            }
            System.out.println("signed in as " + me.title + " (id " + me.id + ")");
            System.out.println();

            long t0 = System.currentTimeMillis();
            Dialog[] dialogs = tg.getDialogs(limit).dialogs;
            long elapsed = System.currentTimeMillis() - t0;

            System.out.println("=== " + dialogs.length + " dialog(s) in " + elapsed + " ms ===");
            for (int i = 0; i < dialogs.length; i++)
            {
                Dialog d = dialogs[i];
                StringBuffer sb = new StringBuffer();
                sb.append(pad(String.valueOf(i), 3));
                sb.append(kindOf(d.peer));
                sb.append(' ').append(pad(d.title(), 28));
                if (d.unreadCount > 0)
                {
                    sb.append(" [").append(d.unreadCount).append(" unread]");
                }
                sb.append("  ").append(trim(d.preview(), 40));
                System.out.println(sb.toString());
            }

            System.out.println();
            System.out.println("peer cache holds " + tg.peers().size() + " entries");

            if (dialogs.length > 0)
            {
                Peer peer = dialogs[0].peer;
                System.out.println();
                System.out.println("=== history of \"" + peer.title + "\" ===");
                Message[] history = tg.getHistory(peer, 15);
                for (int i = history.length - 1; i >= 0; i--)
                {
                    Message m = history[i];
                    System.out.println("  " + pad(m.senderName(), 16) + " | "
                                       + trim(m.text, 60));
                }
                System.out.println();
                System.out.println(history.length + " message(s)");
            }

            System.out.println();
            System.out.println("bytes rx/tx : " + transport.bytesRead()
                               + " / " + transport.bytesWritten());
        }
        catch (Throwable t)
        {
            System.out.println();
            System.out.println("=== FAILED ===");
            System.out.println(t.getClass().getName() + ": " + t.getMessage());
            LiveHandshakeTest.dumpLog();
            t.printStackTrace(System.out);
            System.exit(1);
        }
        finally
        {
            tg.close();
        }
    }

    private static String kindOf(Peer p)
    {
        if (p == null) { return "?"; }
        switch (p.kind)
        {
            case Peer.USER:    return "@";
            case Peer.CHAT:    return "#";
            case Peer.CHANNEL: return "^";
            default:           return "?";
        }
    }

    static String pad(String s, int width)
    {
        StringBuffer sb = new StringBuffer(s == null ? "" : s);
        while (sb.length() < width) { sb.append(' '); }
        return sb.toString();
    }

    static String trim(String s, int max)
    {
        if (s == null) { return ""; }
        String flat = s.replace('\n', ' ');
        return flat.length() <= max ? flat : (flat.substring(0, max - 3) + "...");
    }
}
