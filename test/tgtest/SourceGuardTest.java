package tgtest;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Invariants about the device sources that no runtime check can express.
 *
 * {@code Rng.forTesting} produces a fully predictable stream from a published
 * seed. {@code Handshake} refuses one at auth time, so a mistake cannot produce
 * a real key - but a refusal at auth time is discovered by a user whose login
 * fails. This finds the same mistake at {@code ./tools/test.sh} time instead,
 * and it also catches the case the runtime check cannot see: a production path
 * using a deterministic pool for nonces, padding or {@code random_id}, where
 * nothing throws and everything looks fine.
 *
 * The one legitimate use inside src/ is the on-device crypto self-test, which
 * needs reproducibility and never negotiates anything.
 *
 * The second rule is newer and has the same shape.
 * {@code Entropy.estimatedBitsPerGather()} is one handset's measured figure, and
 * it used to divide into 256 to give the seeding barrier a gather count. Three
 * handsets showed that a per-device yield cannot be a compiled-in constant, so
 * the barrier measures its own instead (issue #2). The number survives because
 * the on-device reports quote it, and the failure mode is that some later
 * seeding path starts multiplying by it again - which nothing at runtime can
 * notice, because the result would look entirely reasonable.
 *
 * The third rule guards the claim rather than the material.
 * {@code AuthKey.fromHandshake} is the one factory that marks a key as seeded by
 * the current path, and what earns that mark is the barrier at the top of
 * {@code Handshake.run()}. Any other production caller would be stamping a key
 * with a path it did not take - a false reassurance that nothing at runtime can
 * detect, because a wrongly-marked key works perfectly.
 *
 * The fourth rule is about an answer nobody read. {@code Worker} runs one
 * network operation at a time and returns false rather than queueing, and
 * eighteen of its twenty-nine callers discarded that - so a keypress at the
 * wrong moment left a busy screen that never came down, a status line stuck at
 * "reacting...", or a password box cleared for a submission that was never made.
 * Every one of those is invisible at runtime, because the code that would have
 * noticed is the code that was not written. What each caller has to undo differs
 * too much to check mechanically; what can be checked is that the answer is
 * given a name, which is the line the recovery has to hang off.
 *
 * The fifth rule keeps one thread owning the screen. Everything that mutates the
 * model, the navigation stack or an lcdui object runs on the display thread, and
 * a producer that is on another one posts through {@code UiDispatcher}. Nineteen
 * scattered {@code callSerially} calls made that rule unreadable - some Worker
 * callbacks wrapped their body and some did not, and the ones that did wrapped
 * work that was already on the display thread, deferring it into a later turn
 * where a keypress could arrive first. So the call now lives in exactly one
 * file, and "which thread is this on" has one answer per call site instead of
 * one per reader.
 *
 * The sixth rule is the same shape one axis over. A result that arrives after
 * the reader has moved to another chat must not be applied to it, and what
 * decides that is a generation bumped when the open conversation changes - so
 * the open conversation has to change in exactly one place.
 */
public final class SourceGuardTest implements Test
{
    /**
     * The call forms, not the names.
     *
     * Production code is allowed to talk <em>about</em> either - Handshake's
     * refusal message names {@code forTesting}, and the comments that explain
     * these rules should not trip them. What is forbidden is invoking them, so
     * prose that means the method rather than the call writes the name without
     * its parentheses.
     */
    private static final String[][] RULES = {
        {
            "Rng.forTesting(",
            "src/tg/crypto/Rng.java",           // the declaration itself
            "src/tg/crypto/SelfTest.java"       // reproducible vectors, no traffic
        },
        {
            "estimatedBitsPerGather(",
            "src/tg/crypto/Entropy.java",       // the declaration itself
            "src/tg/plat/EntropyProbe.java"     // prints it in a report
        },
        {
            "AuthKey.fromHandshake(",
            "src/tg/mt/AuthKey.java",           // the declaration itself
            "src/tg/mt/Handshake.java"          // crossed the barrier, may say so
        },
        {
            "callSerially(",
            "src/tg/app/DisplayDispatcher.java" // the one implementation
        }
    };

    public String name() { return "src/source-guard"; }

    public void run() throws Exception
    {
        File root = new File("src");
        // A guard that quietly passes when it cannot find the sources is worse
        // than no guard: it reports green for a check it never ran.
        Assert.isTrue("src/ must be reachable from the working directory ("
                + new File(".").getAbsolutePath() + ")",
                root.isDirectory());

        List sources = new ArrayList();
        collect(root, sources);
        Assert.isTrue("found device sources, got " + sources.size(),
                sources.size() > 100);

        for (int r = 0; r < RULES.length; r++)
        {
            check(RULES[r], sources);
        }
        everySubmitAnswerIsRead(sources);
        theOpenChatHasOneAssignmentPoint(sources);
        theOpenThreadHasOneAssignmentPoint(sources);
        historyPrefetchStaysOffTheUserWorker();
        topicPagingStaysOffTheUserWorker();
        reactionUiDoesNotWaitOnForegroundWork();
    }

    /** Opening/inspecting reactions must never create an invisible busy task. */
    private static void reactionUiDoesNotWaitOnForegroundWork()
            throws IOException
    {
        String source = read(new File("src/tg/app/TgMidlet.java"));
        int palette = source.indexOf("private void showReactionPalette(");
        int ready = source.indexOf("private void showReactionPaletteReady(",
                palette);
        Assert.isTrue("reaction palette source markers",
                palette >= 0 && ready > palette);
        String open = source.substring(palette, ready);
        Assert.isTrue("opening the reaction palette is local",
                open.indexOf("submit(") < 0
                && open.indexOf("getAllowedReactions") < 0);

        int actors = source.indexOf("private void showReactionActors(");
        int forward = source.indexOf("private void openForwardSource(", actors);
        Assert.isTrue("reaction actors source markers",
                actors >= 0 && forward > actors);
        String details = source.substring(actors, forward);
        Assert.isTrue("reaction actors show Loading before submission",
                details.indexOf("pushScreen(actorsScreen)") >= 0);
        Assert.isTrue("reaction actors use the maintenance worker",
                details.indexOf("syncWorker.submit(") >= 0);
        Assert.isTrue("reaction actor contention waits without refusal",
                details.indexOf("reactionActorsRetry.schedule(") >= 0
                && details.indexOf("showRefused(") < 0);

        String api = read(new File("src/tg/api/Telegram.java"));
        Assert.isTrue("one slow RPC must not serialize unrelated user RPCs",
                api.indexOf("private synchronized byte[] invoke(") < 0
                && api.indexOf("MtClient active = client;") >= 0
                && api.indexOf("active.invokeWithSaltRetry(query)") >= 0);
    }

    /** Automatic history paging must not refuse a foreground keypress. */
    private static void historyPrefetchStaysOffTheUserWorker()
            throws IOException
    {
        String source = read(new File("src/tg/app/TgMidlet.java"));
        int dialogs = source.indexOf("private void loadDialogs()");
        int showDialogs = source.indexOf("private void showDialogList()",
                dialogs);
        Assert.isTrue("initial dialogs source markers", dialogs >= 0
                && showDialogs > dialogs);
        String initialDialogs = source.substring(dialogs, showDialogs);
        Assert.isTrue("initial/cached dialog refresh uses syncWorker",
                initialDialogs.indexOf("syncWorker.submit(") >= 0);
        Assert.isTrue("dialog refresh contention retries without a user alert",
                initialDialogs.indexOf("initialRefreshRetry.schedule(") >= 0
                && initialDialogs.indexOf("showRefused(") < 0);

        int open = source.indexOf("private void loadOpenHistory");
        int maybe = source.indexOf("private void maybeLoadHistory", open);
        Assert.isTrue("open history source markers", open >= 0 && maybe > open);
        String openHistory = source.substring(open, maybe);
        Assert.isTrue("initial/cached history refresh uses syncWorker",
                openHistory.indexOf("syncWorker.submit(") >= 0);
        Assert.isTrue("history refresh contention retries without a user alert",
                openHistory.indexOf("initialRefreshRetry.schedule(") >= 0
                && openHistory.indexOf("showRefused(") < 0);

        int older = source.indexOf("private void loadOlderPage");
        int merge = source.indexOf("private void mergeHistoryPage", older);
        int newer = source.indexOf("private void loadNewerPage");
        int newest = source.indexOf("private int newestOpenId", newer);
        Assert.isTrue("loadOlderPage source markers", older >= 0 && merge > older);
        Assert.isTrue("automatic older history uses syncWorker while manual"
                + " Older remains foreground",
                source.substring(older, merge).indexOf(
                        "manual ? worker : syncWorker") >= 0);
        Assert.isTrue("automatic newer history uses syncWorker",
                newer >= 0 && newest > newer
                && source.substring(newer, newest).indexOf(
                        "syncWorker.submit(") >= 0);

        int dialogsBack = source.indexOf("private void restoreDialogsAbove");
        int dialogsMore = source.indexOf("private void loadMoreDialogs",
                dialogsBack);
        int saved = source.indexOf("private void openSavedMessages",
                dialogsMore);
        Assert.isTrue("dialog paging source markers", dialogsBack >= 0
                && dialogsMore > dialogsBack && saved > dialogsMore);
        Assert.isTrue("automatic backwards dialog paging uses syncWorker",
                source.substring(dialogsBack, dialogsMore).indexOf(
                        "syncWorker.submit(") >= 0);
        Assert.isTrue("automatic further dialog paging uses syncWorker while"
                + " manual More remains foreground",
                source.substring(dialogsMore, saved).indexOf(
                        "manual ? worker : syncWorker") >= 0);
    }

    /**
     * The topic list follows the same lane discipline as the lists above it:
     * automatic pages on the maintenance lane, manual commands foreground,
     * contention retried through the bounded waker rather than alerted.
     */
    private static void topicPagingStaysOffTheUserWorker() throws IOException
    {
        String source = read(new File("src/tg/app/TgMidlet.java"));
        int load = source.indexOf("private void loadTopics");
        int refresh = source.indexOf("private void refreshTopics", load);
        Assert.isTrue("topic load source markers", load >= 0 && refresh > load);
        String initial = source.substring(load, refresh);
        Assert.isTrue("initial topic load uses syncWorker",
                initial.indexOf("syncWorker.submit(") >= 0);
        Assert.isTrue("topic load contention retries without a user alert",
                initial.indexOf("initialRefreshRetry.schedule(") >= 0
                && initial.indexOf("showRefused(") < 0);

        int more = source.indexOf("private void loadMoreTopics");
        int back = source.indexOf("private void restoreTopicsAbove", more);
        int append = source.indexOf("private void appendTopicPage", back);
        Assert.isTrue("topic paging source markers", more >= 0 && back > more
                && append > back);
        Assert.isTrue("automatic further topic paging uses syncWorker while"
                + " manual More remains foreground",
                source.substring(more, back).indexOf(
                        "manual ? worker : syncWorker") >= 0);
        Assert.isTrue("backwards topic paging uses syncWorker",
                source.substring(back, append).indexOf(
                        "syncWorker.submit(") >= 0);
    }

    /**
     * {@code openPeer} is assigned in exactly one place.
     *
     * That place is {@code TgMidlet.bindOpenPeer}, which bumps the chat
     * generation when the conversation really moved. An assignment anywhere
     * else changes which chat is open without the guards noticing, and the
     * result is a page merged into the wrong transcript - which looks like
     * nothing at all until it is a message in a conversation it was never
     * sent to.
     *
     * Counted rather than allow-listed by file, because the offender would be
     * in the same file as the legitimate one.
     */
    private static void theOpenChatHasOneAssignmentPoint(List sources)
            throws IOException
    {
        List found = new ArrayList();
        for (int i = 0; i < sources.size(); i++)
        {
            File f = (File) sources.get(i);
            String path = relative(f);
            String[] lines = read(f).split("\n", -1);
            for (int n = 0; n < lines.length; n++)
            {
                if (assignsOpenPeer(lines[n]))
                {
                    found.add(path + ":" + (n + 1));
                }
            }
        }

        Assert.equal("openPeer must be assigned only by bindOpenPeer, which is"
                + " what bumps the chat generation; assigned at " + found,
                1, found.size());
    }

    /**
     * The same rule for the thread half of the open transcript: two topics of
     * one forum are different conversations, and an {@code openThread}
     * assigned anywhere but beside {@code openPeer} moves the transcript
     * without bumping the chat generation.
     */
    private static void theOpenThreadHasOneAssignmentPoint(List sources)
            throws IOException
    {
        List found = new ArrayList();
        for (int i = 0; i < sources.size(); i++)
        {
            File f = (File) sources.get(i);
            String path = relative(f);
            String[] lines = read(f).split("\n", -1);
            for (int n = 0; n < lines.length; n++)
            {
                if (assigns(lines[n], "openThread"))
                {
                    found.add(path + ":" + (n + 1));
                }
            }
        }

        Assert.equal("openThread must be assigned only by bindOpenPeer, beside"
                + " the peer it qualifies; assigned at " + found,
                1, found.size());
    }

    /** {@code openPeer = x}, but not {@code openPeer == x} or a longer name. */
    private static boolean assignsOpenPeer(String line)
    {
        return assigns(line, "openPeer");
    }

    private static boolean assigns(String line, String field)
    {
        int at = line.indexOf(field);
        while (at >= 0)
        {
            int before = at - 1;
            boolean wordStart = before < 0
                    || !Character.isJavaIdentifierPart(line.charAt(before));
            int after = at + field.length();
            // Skip the spaces an assignment is allowed to have around it.
            while (after < line.length() && line.charAt(after) == ' ') { after++; }
            boolean assigns = after < line.length()
                    && line.charAt(after) == '='
                    && (after + 1 >= line.length() || line.charAt(after + 1) != '=');
            if (wordStart && assigns) { return true; }
            at = line.indexOf(field, at + 1);
        }
        return false;
    }

    /**
     * The call form again, and again for the reason the rules above give: the
     * comments that explain this one have to be able to name the method without
     * tripping it, so prose writes it without its parentheses.
     */
    private static final String SUBMIT_CALL = ".submit(";

    /**
     * Every {@code Worker} submission in production code assigns its answer.
     *
     * The receiver is not named, so a third worker added later is covered
     * without editing this. The declaration in {@code Worker} is not matched at
     * all - it has no receiver in front of it - which is why there is no
     * allow-list here to go stale.
     *
     * Requiring an assignment rather than any use of the value is deliberate.
     * The answer decides which of two paths the method takes, and every caller
     * in the client is a multi-line anonymous {@code Task} whose closing brace
     * is forty lines below its opening one: a name on the first line is what
     * makes the {@code if} at the bottom readable as belonging to it.
     */
    private static void everySubmitAnswerIsRead(List sources) throws IOException
    {
        List offenders = new ArrayList();
        for (int i = 0; i < sources.size(); i++)
        {
            File f = (File) sources.get(i);
            String path = relative(f);
            String[] lines = read(f).split("\n", -1);
            for (int n = 0; n < lines.length; n++)
            {
                int call = lines[n].indexOf(SUBMIT_CALL);
                if (call < 0) { continue; }
                if (lines[n].lastIndexOf('=', call) >= 0) { continue; }
                offenders.add(path + ":" + (n + 1));
            }
        }

        Assert.isTrue("a refused Worker submission is an ordinary outcome, so"
                + " every submission must name its answer; unnamed at "
                + offenders, offenders.isEmpty());
    }

    private void check(String[] rule, List sources) throws IOException
    {
        String call = rule[0];

        List offenders = new ArrayList();
        for (int i = 0; i < sources.size(); i++)
        {
            File f = (File) sources.get(i);
            String path = relative(f);
            if (allowed(rule, path)) { continue; }
            if (read(f).indexOf(call) >= 0) { offenders.add(path); }
        }

        Assert.isTrue(call + " must stay out of production paths; found in "
                + offenders, offenders.isEmpty());

        // The allow-list is only meaningful while its entries still exist and
        // still contain what it excuses them for.
        for (int i = 1; i < rule.length; i++)
        {
            File f = new File(rule[i]);
            Assert.isTrue("allow-listed file is missing: " + rule[i], f.isFile());
            Assert.isTrue("allow-list entry no longer needs an exemption: "
                    + rule[i], read(f).indexOf(bareName(call)) >= 0);
        }
    }

    /** "Rng.forTesting(" -> "forTesting"; the name a file must still mention. */
    private static String bareName(String call)
    {
        String name = call.substring(0, call.length() - 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static boolean allowed(String[] rule, String path)
    {
        for (int i = 1; i < rule.length; i++)
        {
            if (rule[i].equals(path)) { return true; }
        }
        return false;
    }

    private static void collect(File dir, List out)
    {
        File[] entries = dir.listFiles();
        if (entries == null) { return; }
        for (int i = 0; i < entries.length; i++)
        {
            if (entries[i].isDirectory()) { collect(entries[i], out); }
            else if (entries[i].getName().endsWith(".java")) { out.add(entries[i]); }
        }
    }

    private static String relative(File f)
    {
        return f.getPath().replace('\\', '/');
    }

    private static String read(File f) throws IOException
    {
        FileInputStream in = new FileInputStream(f);
        try
        {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length)
            {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) { break; }
                off += n;
            }
            return new String(buf, 0, off, "ISO-8859-1");
        }
        finally { in.close(); }
    }
}
