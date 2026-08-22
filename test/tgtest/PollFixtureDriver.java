package tgtest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import tg.api.Api;
import tg.api.Dialog;
import tg.api.DialogPage;
import tg.api.Message;
import tg.api.Peer;
import tg.api.Requests;
import tg.api.Telegram;
import tg.crypto.Rng;
import tg.mt.ConnectionConfig;
import tg.plat.RmsAuthKeyStore;
import tg.tl.TlWriter;

/** Server-side creator/revoter for the packaged poll emulator scenario. */
public final class PollFixtureDriver
{
    private static final int RPC_MS = 90000;

    public static void main(String[] args) throws Exception
    {
        if (args.length < 3)
        {
            throw new IllegalArgumentException(
                    "find|bump|delete-bump|create-change|delete state-dir chat-title");
        }
        EmulatorHarness.installRecordStore();
        String action = args[0];
        File state = new File(args[1]);
        String chatTitle = args[2];
        SeTransport transport = new SeTransport();
        transport.setReadTimeoutMs(60000);
        Telegram telegram = new Telegram(transport, new Rng(),
                new RmsAuthKeyStore());
        telegram.connectionConfig().mode = ConnectionConfig.DIRECT;
        try
        {
            telegram.connect();
            if (telegram.checkAuthorization() == null)
            {
                throw new Exception("fixture profile is not authorized");
            }
            Peer target = findDialog(telegram, chatTitle);
            if (!"find".equals(action))
            {
                write(new File(state, "target-id"), String.valueOf(target.id));
            }
            if ("find".equals(action))
            {
                System.out.println("fixture target found: " + target.key()
                        + " username=" + String.valueOf(target.username)
                        + " forum=" + target.forum);
            }
            else if ("bump".equals(action))
            {
                String marker = read(new File(state, "marker")) + "-wake";
                telegram.sendMessage(target, marker);
                Message message = awaitMessage(telegram, target, marker, false);
                write(new File(state, "bump-id"), String.valueOf(message.id));
                signal(state, "fixture-bumped");
            }
            else if ("delete-bump".equals(action))
            {
                int messageId = Integer.parseInt(read(
                        new File(state, "bump-id")));
                telegram.deleteMessage(target, messageId, true);
                signal(state, "fixture-bump-deleted");
            }
            else if ("create-change".equals(action))
            {
                createAndChange(telegram, target, state);
            }
            else if ("delete".equals(action))
            {
                int messageId = Integer.parseInt(read(
                        new File(state, "message-id")));
                telegram.deleteMessage(target, messageId, true);
                signal(state, "fixture-deleted");
            }
            else
            {
                throw new IllegalArgumentException("unknown fixture action");
            }
        }
        finally { telegram.close(); }
    }

    private static void createAndChange(Telegram telegram, Peer target,
                                        File state) throws Exception
    {
        String marker = read(new File(state, "marker"));
        long pollId = (System.currentTimeMillis() << 17)
                ^ System.nanoTime();
        if (pollId == 0) { pollId = 1; }
        long randomId = pollId ^ 0x504f4c4c225L;
        invoke(telegram, sendPoll(target, pollId, randomId, marker));

        Message created = awaitMessage(telegram, target, marker, true);
        write(new File(state, "message-id"), String.valueOf(created.id));
        write(new File(state, "poll-id"), String.valueOf(created.media.poll.id));
        signal(state, "fixture-created");

        awaitFile(new File(state, "client-voted"), RPC_MS);
        if (created.media.poll.options.length < 3)
        {
            throw new Exception("created poll has fewer than three options");
        }
        // A distinct fixture account first votes Alpha, then replaces that
        // complete selection with Gamma. The second call changes public
        // totals, so another connected account receives updateMessagePoll.
        telegram.sendVote(target, created.id, new byte[][] {
                created.media.poll.options[0].option
        });
        Thread.sleep(1500);
        telegram.sendVote(target, created.id, new byte[][] {
                created.media.poll.options[2].option
        });
        signal(state, "fixture-changed");
    }

    private static Message awaitMessage(Telegram telegram, Peer target,
                                        String marker, boolean poll)
            throws Exception
    {
        long until = System.currentTimeMillis() + RPC_MS;
        while (System.currentTimeMillis() < until)
        {
            Message[] history = telegram.getHistory(target, 30);
            for (int i = 0; i < history.length; i++)
            {
                Message message = history[i];
                boolean matches = poll ? message != null
                        && message.media != null && message.media.poll != null
                        && marker.equals(message.media.poll.question)
                        : message != null && marker.equals(message.text);
                if (matches) { return message; }
            }
            Thread.sleep(500);
        }
        throw new Exception(poll ? "created poll not in history"
                : "bump message not in history");
    }

    /** Layer-225 messages.sendMedia with a bounded multiple-choice poll. */
    private static byte[] sendPoll(Peer peer, long pollId, long randomId,
                                   String question)
    {
        TlWriter w = new TlWriter(320);
        w.writeInt(Api.MESSAGES_SEND_MEDIA);
        w.writeInt(0);                         // sendMedia flags
        Requests.writeInputPeer(w, peer);
        w.writeInt(Api.INPUT_MEDIA_POLL);
        w.writeInt(0);                         // inputMediaPoll flags
        w.writeInt(Api.POLL);
        w.writeLong(pollId);
        w.writeInt(4);                         // multiple_choice
        writeText(w, question);
        w.writeVectorHeader(3);
        writeAnswer(w, "Alpha", new byte[] { 1 });
        writeAnswer(w, "Beta", new byte[] { 2 });
        writeAnswer(w, "Gamma", new byte[] { 3 });
        w.writeLong(0);                        // poll hash
        w.writeString("");
        w.writeLong(randomId);
        return w.toByteArray();
    }

    private static void writeAnswer(TlWriter w, String text, byte[] option)
    {
        w.writeInt(Api.POLL_ANSWER);
        w.writeInt(0);
        writeText(w, text);
        w.writeBytes(option);
    }

    private static void writeText(TlWriter w, String text)
    {
        w.writeInt(Api.TEXT_WITH_ENTITIES);
        w.writeString(text);
        w.writeVectorHeader(0);
    }

    private static Peer findDialog(Telegram telegram, String title)
            throws Exception
    {
        DialogPage page = telegram.getDialogs(100);
        for (int request = 0; request < 30; request++)
        {
            Dialog[] dialogs = page.dialogs;
            for (int i = 0; i < dialogs.length; i++)
            {
                if (dialogs[i] != null && dialogs[i].peer != null
                        && title.equals(dialogs[i].peer.title))
                {
                    return dialogs[i].peer;
                }
            }
            if (page.complete || dialogs.length == 0) { break; }
            page = telegram.getDialogsAfter(dialogs[dialogs.length - 1], 100);
        }
        throw new Exception("fixture target group was not found");
    }

    private static byte[] invoke(Telegram telegram, byte[] query)
            throws Exception
    {
        Method method = Telegram.class.getDeclaredMethod("invoke",
                new Class[] { byte[].class });
        method.setAccessible(true);
        try
        {
            return (byte[]) method.invoke(telegram, new Object[] { query });
        }
        catch (InvocationTargetException wrapped)
        {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) { throw (Exception) cause; }
            throw wrapped;
        }
    }

    private static void awaitFile(File file, int timeout) throws Exception
    {
        long until = System.currentTimeMillis() + timeout;
        while (!file.isFile() && System.currentTimeMillis() < until)
        {
            Thread.sleep(100);
        }
        if (!file.isFile()) { throw new Exception("timed out waiting for signal"); }
    }

    private static void signal(File state, String name) throws Exception
    {
        write(new File(state, name), "ok");
    }

    private static String read(File file) throws Exception
    {
        FileInputStream in = new FileInputStream(file);
        try
        {
            byte[] bytes = new byte[(int) file.length()];
            int at = 0;
            while (at < bytes.length)
            {
                int n = in.read(bytes, at, bytes.length - at);
                if (n < 0) { break; }
                at += n;
            }
            return new String(bytes, 0, at, "UTF-8").trim();
        }
        finally { in.close(); }
    }

    private static void write(File file, String value) throws Exception
    {
        FileOutputStream out = new FileOutputStream(file);
        try { out.write(value.getBytes("UTF-8")); }
        finally { out.close(); }
    }
}
