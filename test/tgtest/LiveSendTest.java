package tgtest;

import tg.api.Dialog;
import tg.api.Message;
import tg.api.Peer;
import tg.api.Telegram;
import tg.crypto.Rng;

/**
 * Send a message, then read the history back to confirm it arrived.
 *
 * Defaults to Saved Messages - the user's own chat with themselves - so a test
 * run cannot disturb anybody else.
 *
 *     ./tools/live.ps1 send                       "hello" to Saved Messages
 *     ./tools/live.ps1 send "some text"
 *     ./tools/live.ps1 send "some text" 3         to dialog #3 from the list
 */
public final class LiveSendTest
{
    public static void main(String[] args) throws Exception
    {
        String text = args.length > 0 ? args[0]
                : "Sent from a Java ME client over direct MTProto 2.0";
        int dialogIndex = args.length > 1 ? Integer.parseInt(args[1]) : -1;

        FileAuthKeyStore store = new FileAuthKeyStore();
        SeTransport transport = new SeTransport();
        transport.setReadTimeoutMs(60000);
        Telegram tg = new Telegram(transport, new Rng(), store);

        try
        {
            tg.connect();
            Peer me = tg.checkAuthorization();
            if (me == null)
            {
                System.out.println("Not signed in. Run:  ./tools/live.ps1 login");
                System.exit(2);
            }
            System.out.println("signed in as " + me.title);

            Peer target;
            if (dialogIndex >= 0)
            {
                Dialog[] dialogs = tg.getDialogs(40).dialogs;
                if (dialogIndex >= dialogs.length)
                {
                    System.out.println("only " + dialogs.length + " dialog(s) available");
                    System.exit(2);
                }
                target = dialogs[dialogIndex].peer;
            }
            else
            {
                // Saved Messages is the chat with oneself; getDialogs is still
                // called so the peer cache has our access_hash.
                tg.getDialogs(40);
                target = tg.peers().self();
                if (target == null)
                {
                    target = me;
                }
            }

            System.out.println("target      : " + target);
            System.out.println("addressable : " + tg.peers().isAddressable(target));
            System.out.println("text        : " + text);
            System.out.println();

            long t0 = System.currentTimeMillis();
            tg.sendMessage(target, text);
            System.out.println("sent in " + (System.currentTimeMillis() - t0) + " ms");

            System.out.println();
            System.out.println("=== reading the history back ===");
            Message[] history = tg.getHistory(target, 5);
            for (int i = history.length - 1; i >= 0; i--)
            {
                Message m = history[i];
                System.out.println("  " + LiveDialogsTest.pad(m.senderName(), 14)
                                   + " | " + LiveDialogsTest.trim(m.text, 60));
            }

            boolean found = false;
            for (int i = 0; i < history.length; i++)
            {
                if (history[i] != null && text.equals(history[i].text))
                {
                    found = true;
                    break;
                }
            }
            System.out.println();
            System.out.println(found
                    ? "=== SUCCESS: the message is in the history ==="
                    : "sent, but not visible in the last few messages yet");
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
}
