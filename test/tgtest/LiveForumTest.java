package tgtest;

import tg.api.Dialog;
import tg.api.ForumTopic;
import tg.api.ForumTopicPage;
import tg.api.Message;
import tg.api.Peer;
import tg.api.Telegram;
import tg.crypto.Rng;
import tg.mt.Dc;

/**
 * End-to-end forum topics against a prepared account.
 *
 * The account behind {@code secrets/live-session.properties} holds a forum
 * supergroup with the General topic and at least one named topic. This walks
 * the whole feature the way the client does: find the forum in the dialog
 * list, list its topics, read one topic's transcript, send into it, prove the
 * send landed in that topic and nowhere else, and move the per-topic read
 * cursor - including on General, whose root id 1 was flagged for live
 * verification.
 *
 * The fixture names are real chat titles on a real account, so they live in
 * gitignored {@code secrets/live-fixtures.properties} rather than here:
 *
 *     forum.title=&lt;the forum supergroup's title&gt;
 *     topic.title=&lt;a topic inside it, not General&gt;
 *
 * Run {@code ./tools/live.ps1 login} first.
 *
 *     ./tools/live.ps1 forum
 *     ./tools/live.ps1 forum "<forum title>" "<topic title>"
 */
public final class LiveForumTest
{
    private static int failures;

    public static void main(String[] args) throws Exception
    {
        java.util.Properties fixtures = new java.util.Properties();
        java.io.File names = new java.io.File("secrets/live-fixtures.properties");
        if (names.isFile())
        {
            java.io.InputStream in = new java.io.FileInputStream(names);
            try
            {
                fixtures.load(new java.io.InputStreamReader(in, "UTF-8"));
            }
            finally { in.close(); }
        }
        String forumTitle = args.length > 0 ? args[0]
                : fixtures.getProperty("forum.title");
        String topicTitle = args.length > 1 ? args[1]
                : fixtures.getProperty("topic.title");
        if (forumTitle == null || topicTitle == null)
        {
            System.out.println("The prepared group's titles are account data"
                    + " and are not checked in.");
            System.out.println("Provide them as arguments, or create "
                    + names.getPath() + " with:");
            System.out.println("    forum.title=<the forum supergroup's title>");
            System.out.println("    topic.title=<a topic inside it>");
            System.exit(2);
        }

        FileAuthKeyStore store = new FileAuthKeyStore();
        SeTransport transport = new SeTransport();
        transport.setReadTimeoutMs(60000);
        Telegram tg = new Telegram(transport, new Rng(), store);

        try
        {
            tg.connect();
            System.out.println("connected to dc" + tg.dcId()
                    + " (" + (Dc.isTest() ? "test" : "production") + ")");
            tg.api.AuthCheck auth = tg.verifyAuthorization();
            if (auth.peer == null)
            {
                System.out.println("Not signed in (" + auth.describe()
                        + "). Run:  ./tools/live.ps1 login");
                System.exit(2);
            }
            Peer me = auth.peer;
            System.out.println("signed in as " + me.title);
            System.out.println();

            // --- the forum is in the dialog list, and carries its flag ------
            Dialog[] dialogs = tg.getDialogs(60).dialogs;
            Peer forum = null;
            for (int i = 0; i < dialogs.length; i++)
            {
                if (dialogs[i] != null
                        && forumTitle.equals(dialogs[i].title().trim()))
                {
                    forum = dialogs[i].peer;
                    break;
                }
            }
            if (forum == null)
            {
                System.out.println("no dialog titled \"" + forumTitle
                        + "\" in the first " + dialogs.length
                        + "; is the account prepared?");
                System.exit(2);
            }
            System.out.println("forum       : " + forum);
            check("channel.forum is set on the prepared group", forum.forum);
            check("the forum is addressable",
                    tg.peers().isAddressable(forum));

            // --- the topic list ---------------------------------------------
            ForumTopicPage page = tg.getForumTopics(forum, null, 20);
            System.out.println();
            System.out.println("=== " + page.topics.length + " topic(s), server total "
                    + page.total + " ===");
            ForumTopic general = null;
            ForumTopic named = null;
            for (int i = 0; i < page.topics.length; i++)
            {
                ForumTopic t = page.topics[i];
                System.out.println("  #" + t.id + "  "
                        + LiveDialogsTest.pad(t.title, 24)
                        + (t.pinned ? " [pinned]" : "")
                        + (t.closed ? " [closed]" : "")
                        + (t.unreadCount > 0 ? (" [" + t.unreadCount + " unread]") : "")
                        + "  " + LiveDialogsTest.trim(t.preview(), 36));
                if (t.id == ForumTopic.GENERAL_ID) { general = t; }
                if (topicTitle.equals(t.title.trim())) { named = t; }
            }
            check("General is listed", general != null);
            check("\"" + topicTitle + "\" is listed", named != null);
            if (named == null) { finish(); return; }

            // --- one topic's transcript is that topic's ---------------------
            System.out.println();
            System.out.println("=== transcript of \"" + named.title + "\" ===");
            Message[] before = tg.getHistory(forum, named.id, 15);
            printTranscript(before);
            boolean allInTopic = true;
            for (int i = 0; i < before.length; i++)
            {
                Message m = before[i];
                // The root service message answers General - it has no reply
                // header - so it is its own membership proof by id.
                if (m != null && m.id != named.id
                        && m.threadRootIn(true) != named.id)
                {
                    allInTopic = false;
                }
            }
            check("every fetched message maps to the topic", allInTopic);

            // --- send into the topic, and only into it ----------------------
            String probe = "e2e topics " + System.currentTimeMillis();
            tg.sendMessage(forum, probe, named.id);
            System.out.println();
            System.out.println("sent into the topic: " + probe);

            Message[] after = tg.getHistory(forum, named.id, 15);
            Message landed = find(after, probe);
            check("the probe is in the topic transcript", landed != null);
            if (landed != null)
            {
                check("its reply header names the topic",
                        landed.threadRootIn(true) == named.id);
            }
            Message[] generalAfter = tg.getHistory(forum,
                    ForumTopic.GENERAL_ID, 20);
            check("the probe did not leak into General",
                    find(generalAfter, probe) == null);
            Message flatCopy = find(tg.getHistory(forum, 20), probe);
            check("the whole-peer history still carries it", flatCopy != null);
            if (flatCopy != null)
            {
                check("with a header that maps it to the topic",
                        flatCopy.threadRootIn(true) == named.id);
            }

            // --- the degenerate send: General is a plain send ---------------
            String generalProbe = "e2e general " + System.currentTimeMillis();
            tg.sendMessage(forum, generalProbe, ForumTopic.GENERAL_ID);
            Message inGeneral = find(tg.getHistory(forum,
                    ForumTopic.GENERAL_ID, 20), generalProbe);
            check("a send into General lands in General", inGeneral != null);
            if (inGeneral != null)
            {
                check("and maps to General",
                        inGeneral.threadRootIn(true) == ForumTopic.GENERAL_ID);
            }

            // --- per-topic read cursors, General included -------------------
            int newestInTopic = after.length > 0 && after[0] != null
                    ? after[0].id : 0;
            if (newestInTopic > 0)
            {
                tg.markRead(forum, named.id, newestInTopic);
                System.out.println();
                System.out.println("readDiscussion(topic " + named.id
                        + ") accepted up to " + newestInTopic);
            }
            // The case the plan flagged for live verification: the General
            // topic's root is the forum's creation service message, id 1.
            Message[] generalNow = tg.getHistory(forum,
                    ForumTopic.GENERAL_ID, 5);
            int newestInGeneral = generalNow.length > 0
                    && generalNow[0] != null ? generalNow[0].id : 0;
            if (newestInGeneral > 0)
            {
                try
                {
                    tg.markRead(forum, ForumTopic.GENERAL_ID, newestInGeneral);
                    System.out.println("readDiscussion(General) accepted up to "
                            + newestInGeneral);
                }
                catch (Throwable refused)
                {
                    fail("readDiscussion on General was refused: "
                            + refused.getMessage());
                }
            }

            // --- topic list reflects the writes -----------------------------
            ForumTopicPage refreshed = tg.getForumTopics(forum, null, 20);
            ForumTopic row = null;
            for (int i = 0; i < refreshed.topics.length; i++)
            {
                if (refreshed.topics[i].id == named.id)
                {
                    row = refreshed.topics[i];
                    break;
                }
            }
            check("the topic row survived a refresh", row != null);
            if (row != null && landed != null)
            {
                check("and its top message reached the probe",
                        row.topMessageId >= landed.id);
                System.out.println();
                System.out.println("row after refresh: top " + row.topMessageId
                        + ", read up to " + row.readInboxMaxId
                        + ", unread " + row.unreadCount
                        + ", preview \"" + LiveDialogsTest.trim(row.preview(), 40)
                        + "\"");
            }

            System.out.println();
            System.out.println("bytes rx/tx : " + transport.bytesRead()
                    + " / " + transport.bytesWritten());
            finish();
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

    private static void printTranscript(Message[] messages)
    {
        for (int i = messages.length - 1; i >= 0; i--)
        {
            Message m = messages[i];
            if (m == null) { continue; }
            System.out.println("  #" + LiveDialogsTest.pad(String.valueOf(m.id), 5)
                    + LiveDialogsTest.pad(m.senderName(), 14) + " | "
                    + LiveDialogsTest.trim(m.text, 56));
        }
        System.out.println("  " + messages.length + " message(s)");
    }

    private static Message find(Message[] messages, String text)
    {
        for (int i = 0; i < messages.length; i++)
        {
            if (messages[i] != null && text.equals(messages[i].text))
            {
                return messages[i];
            }
        }
        return null;
    }

    private static void check(String what, boolean ok)
    {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) { failures++; }
    }

    private static void fail(String what)
    {
        System.out.println("  FAIL " + what);
        failures++;
    }

    private static void finish()
    {
        System.out.println();
        System.out.println(failures == 0
                ? "=== SUCCESS: forum topics end to end ==="
                : "=== FAILED: " + failures + " check(s) ===");
        System.exit(failures == 0 ? 0 : 1);
    }
}
