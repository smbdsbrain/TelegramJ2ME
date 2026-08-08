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

    /** {@code openPeer = x}, but not {@code openPeer == x} or a longer name. */
    private static boolean assignsOpenPeer(String line)
    {
        int at = line.indexOf("openPeer");
        while (at >= 0)
        {
            int before = at - 1;
            boolean wordStart = before < 0
                    || !Character.isJavaIdentifierPart(line.charAt(before));
            int after = at + "openPeer".length();
            // Skip the spaces an assignment is allowed to have around it.
            while (after < line.length() && line.charAt(after) == ' ') { after++; }
            boolean assigns = after < line.length()
                    && line.charAt(after) == '='
                    && (after + 1 >= line.length() || line.charAt(after + 1) != '=');
            if (wordStart && assigns) { return true; }
            at = line.indexOf("openPeer", at + 1);
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
