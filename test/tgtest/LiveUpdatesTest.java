package tgtest;

import tg.api.Dialog;
import tg.api.Message;
import tg.api.Peer;
import tg.api.Telegram;
import tg.api.UpdateBatch;
import tg.crypto.Rng;

/**
 * Wait for live updates while another Telegram client acts.
 *
 *   ./tools/live.ps1 updates 60
 *   ./tools/live.ps1 updates 120 3   # short-poll dialog 3 if it is a channel
 */
public final class LiveUpdatesTest
{
    public static void main(String[] args) throws Exception
    {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 60;
        int activeIndex = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        FileAuthKeyStore store = new FileAuthKeyStore();
        SeTransport transport = new SeTransport();
        transport.setReadTimeoutMs(60000);
        final Telegram tg = new Telegram(transport, new Rng(), store);
        tg.setUpdateListener(new Telegram.UpdateListener()
        {
            public void onUpdates(UpdateBatch batch)
            {
                if (batch.syncState != null)
                {
                    System.out.println("[sync] " + batch.syncState + " "
                            + String.valueOf(batch.detail));
                }
                for (int i = 0; i < batch.messages.length; i++)
                {
                    Message m = batch.messages[i];
                    System.out.println("[message] " + (m.peer == null ? "?"
                            : m.peer.key()) + " #" + m.id + " " + m.text);
                }
                for (int i = 0; i < batch.reads.length; i++)
                {
                    System.out.println("[read] "
                            + (batch.reads[i].peer == null ? "?"
                                    : batch.reads[i].peer.key())
                            + " inbox=" + batch.reads[i].inboxMaxId
                            + " outbox=" + batch.reads[i].outboxMaxId
                            + " unread=" + batch.reads[i].unreadCount);
                }
                if (batch.fullRefresh)
                {
                    System.out.println("[refresh] snapshot required");
                }
            }
        });

        try
        {
            tg.connect();
            Peer me = tg.checkAuthorization();
            if (me == null)
            {
                System.out.println("Not signed in. Run: ./tools/live.ps1 login");
                System.exit(2);
            }
            Dialog[] dialogs = tg.getDialogs(40).dialogs;
            if (activeIndex >= 0)
            {
                if (activeIndex >= dialogs.length)
                {
                    throw new IllegalArgumentException("only " + dialogs.length
                            + " dialogs available");
                }
                tg.setActivePeer(dialogs[activeIndex].peer);
                System.out.println("active peer: " + dialogs[activeIndex].peer);
            }
            System.out.println("Waiting " + seconds
                    + " seconds. Send/read a uniquely marked message now.");
            Thread.sleep(seconds * 1000L);
            System.out.println("final sync state: " + tg.updateSyncState()
                    + " (" + tg.updateSyncDetail() + ")");
        }
        finally
        {
            tg.close();
        }
    }
}
