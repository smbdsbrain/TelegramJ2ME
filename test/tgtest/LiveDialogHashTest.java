package tgtest;

import tg.api.Dialog;
import tg.api.DialogPage;
import tg.api.PageHash;
import tg.api.Telegram;
import tg.crypto.Rng;
import tg.mt.ConnectionConfig;
import tg.mt.Dc;

/**
 * Does {@code messages.getDialogs} honour a hash, and if so, of what?
 *
 * The folding function is documented at
 * <a href="https://core.telegram.org/api/offsets#hash-generation">api/offsets</a>.
 * What is <i>fed</i> into it for this method is not. The page names
 * {@code messages.getHistory} and {@code channels.getParticipants} and says
 * "the IDs of the returned objects", which for a {@code Dialog} is ambiguous:
 * it has a peer, a top message and a read state, and no field called id.
 *
 * Nor do the official clients settle it. TDLib's dialog handling has no branch
 * for {@code messages.dialogsNotModified} at all - only for the saved-dialogs
 * variant, and it logs an error on anything else - and Telegram for Android
 * mentions the constructor only in generated model code. Both therefore send 0.
 *
 * So this measures instead of assuming. Each candidate vector is folded over
 * one page and immediately sent back with the same offsets. Two identical
 * requests a second apart means an unchanged list, so a
 * {@code messages.dialogsNotModified} reply is proof the vector is right, and
 * a full response from all of them is a clean negative.
 *
 * Read-only: it lists dialogs and nothing else.
 *
 *     ./tools/live.ps1 dialog-hash -Env production        desktop session
 *     ./tools/drive-emulator.ps1 -Scenario hashprobe      emulator RMS session
 */
public final class LiveDialogHashTest
{
    /** No candidate produced a notModified reply. */
    public static final int NONE = -1;

    /** The server said notModified to hash 0, so nothing can be concluded. */
    public static final int INCONCLUSIVE = -2;

    /** No session, or no chats to hash. */
    public static final int UNAVAILABLE = -3;

    private static final int[] CANDIDATES = {
        PageHash.BY_TOP_MESSAGE, PageHash.BY_PEER, PageHash.BY_DIALOG_STATE
    };

    private static final String[] NAMES = {
        "BY_TOP_MESSAGE", "BY_PEER", "BY_DIALOG_STATE"
    };

    public static void main(String[] args) throws Exception
    {
        int limit = args.length > 0 ? Integer.parseInt(args[0]) : 30;

        SeTransport transport = new SeTransport();
        transport.setReadTimeoutMs(60000);
        Telegram tg = new Telegram(transport, new Rng(), new FileAuthKeyStore());
        // The desktop transport is a plain socket and says so - "fixed
        // transport supports direct mode only". The constructor asks for
        // DIRECT and then load() replaces it with whatever mode the stored
        // session last used, which on a build carrying a compiled-in MTProxy
        // is MTProxy. Set it back after the load rather than before.
        tg.connectionConfig().mode = ConnectionConfig.DIRECT;

        int winner;
        try
        {
            tg.connect();
            System.out.println("connected to dc" + tg.dcId()
                               + " (" + (Dc.isTest() ? "test" : "production") + ")");
            if (tg.checkAuthorization() == null)
            {
                System.out.println("Not signed in. Run:  ./tools/live.ps1 login");
                System.exit(2);
            }
            winner = probe(tg, transport, limit);
        }
        catch (Throwable t)
        {
            System.out.println();
            System.out.println("=== FAILED ===");
            System.out.println(t.getClass().getName() + ": " + t.getMessage());
            LiveHandshakeTest.dumpLog();
            t.printStackTrace(System.out);
            System.exit(1);
            return;
        }
        finally
        {
            tg.close();
        }
        System.exit(winner >= 0 ? 0 : 3);
    }

    /**
     * Run the experiment on an already-connected, signed-in client.
     *
     * Split out so the emulator driver can run the same probe against the RMS
     * session, which is where the only signed-in production account lives.
     *
     * @return the winning {@code PageHash} mode, or one of {@link #NONE},
     *         {@link #INCONCLUSIVE}, {@link #UNAVAILABLE}
     */
    public static int probe(Telegram tg, SeTransport transport, int limit)
            throws Exception
    {
        DialogPage first = tg.getDialogs(limit);
        System.out.println("baseline: " + first.size() + " dialog(s)"
                           + " total=" + first.total
                           + " complete=" + first.complete);
        if (first.size() == 0)
        {
            System.out.println("nothing to hash; this account has no chats");
            return UNAVAILABLE;
        }

        // A control first. Hash 0 must come back full, or the experiment below
        // cannot distinguish "the vector is right" from "this server says
        // notModified to anything".
        long before = rx(transport);
        DialogPage control = tg.getDialogs(limit, 0);
        System.out.println("control  hash=0"
                           + "  notModified=" + control.notModified
                           + "  dialogs=" + control.size()
                           + "  rx=" + (rx(transport) - before) + " bytes");
        if (control.notModified)
        {
            System.out.println();
            System.out.println("INCONCLUSIVE: the server answered notModified"
                    + " to hash=0, so a match proves nothing");
            return INCONCLUSIVE;
        }

        System.out.println();
        int winner = NONE;
        for (int i = 0; i < CANDIDATES.length; i++)
        {
            long hash = PageHash.dialogs(first.dialogs, CANDIDATES[i]);
            long rxBefore = rx(transport);
            DialogPage reply = tg.getDialogs(limit, hash);
            System.out.println(pad(NAMES[i], 16)
                               + " notModified=" + reply.notModified
                               + "  dialogs=" + reply.size()
                               + "  rx=" + (rx(transport) - rxBefore) + " bytes");
            if (reply.notModified && winner < 0) { winner = CANDIDATES[i]; }
        }

        System.out.println();
        if (winner >= 0)
        {
            System.out.println("=== RESULT: the server honours the hash ===");
            System.out.println("winning vector: " + NAMES[winner]
                    + " (PageHash mode " + winner + ")");
        }
        else
        {
            System.out.println("=== RESULT: no candidate matched ===");
            System.out.println("Every vector came back as a full response,"
                    + " which is also what hash=0 does. Keep sending 0;"
                    + " nothing is lost but the saving.");
            System.out.println("A negative, not a failure: it is what TDLib and"
                    + " Telegram for Android both do.");
        }

        // A shape check on the page itself while a real reply is in hand: the
        // preview clip is what gives a Dialog a fixed size, and the retention
        // cap is derived from that.
        Dialog[] page = first.dialogs;
        int longest = 0;
        for (int i = 0; i < page.length; i++)
        {
            if (page[i] != null && page[i].lastMessage != null
                    && page[i].lastMessage.length() > longest)
            {
                longest = page[i].lastMessage.length();
            }
        }
        System.out.println("longest retained preview: " + longest
                           + " chars (cap " + Dialog.PREVIEW_MAX + ")");
        int pinned = 0;
        for (int i = 0; i < page.length; i++)
        {
            if (page[i] != null && page[i].pinned) { pinned++; }
        }
        System.out.println("pinned in the first page: " + pinned);
        return winner;
    }

    /** Byte counter, when the caller has one. */
    private static long rx(SeTransport transport)
    {
        return transport == null ? 0 : transport.bytesRead();
    }

    private static String pad(String s, int width)
    {
        StringBuffer sb = new StringBuffer(s == null ? "" : s);
        while (sb.length() < width) { sb.append(' '); }
        return sb.toString();
    }
}
